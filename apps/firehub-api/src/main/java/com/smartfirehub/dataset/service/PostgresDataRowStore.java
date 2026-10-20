package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.storage.DataColumnTypes;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataTableNames;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.util.TemporalSafeFetch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 데이터셋 저장소의 행 조회·적재 구현(WD-72). 기존 {@code DataTableRowService} 의 SQL 실행 본문을 그대로 이사한 것이다 —
 * 동작 변경 없음.
 *
 * <p>호출자의 트랜잭션에 그대로 참여한다(별도 {@code @Transactional} 없음). 파사드({@code DataTableRowService})가 {@link
 * com.smartfirehub.dataset.storage.DataStoreResolver} 로 이 구현을 골라 위임한다. 이름 검증은 백엔드 중립 {@link
 * DataTableNames} 를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class PostgresDataRowStore implements DataRowStore {

  private final DSLContext dsl;

  @Override
  public DataStoreBackend backend() {
    return DataStoreBackend.POSTGRESQL;
  }

  // --- GEOMETRY helpers ---

  private static boolean isGeometry(String colName, Map<String, String> columnTypes) {
    return DataColumnTypes.isGeometry(colName, columnTypes);
  }

  /** Returns ST_AsGeoJSON("col") AS "col" for GEOMETRY, otherwise "col". */
  static String selectExpr(String colName, Map<String, String> columnTypes) {
    if (isGeometry(colName, columnTypes)) {
      return "ST_AsGeoJSON(\"" + colName + "\") AS \"" + colName + "\"";
    }
    return "\"" + colName + "\"";
  }

  /** Returns type-casting placeholder: ?::timestamp, ?::boolean, ST_GeomFromGeoJSON(?), etc. */
  private static String insertPlaceholder(String colName, Map<String, String> columnTypes) {
    if (columnTypes == null) return "?";
    String type = columnTypes.get(colName);
    if (type == null) return "?";
    String upper = type.toUpperCase();
    if (upper.contains("GEOMETRY")) return "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)";
    if (upper.contains("TIMESTAMP")) return "?::timestamp";
    if (upper.contains("BOOLEAN")) return "?::boolean";
    if (upper.contains("BIGINT")) return "?::bigint";
    // 데이터셋 INTEGER 의 물리 타입은 BIGINT(DataTableService#mapDataType) — int4 로 캐스트하면 2^31 이상이 넘친다.
    if (upper.contains("INTEGER") || upper.contains("INT")) return "?::bigint";
    if (upper.contains("NUMERIC")) return "?::numeric";
    if (upper.contains("DATE")) return "?::date";
    return "?";
  }

  @Override
  public List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      List<String> searchColumns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String col : searchColumns) {
      DataTableNames.validate(col);
    }
    if (sortBy != null) {
      DataTableNames.validate(sortBy);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT id, ");
    if (columns.isEmpty()) {
      sql.append("*");
    } else {
      for (int i = 0; i < columns.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append(selectExpr(columns.get(i), columnTypes));
      }
    }
    sql.append(" FROM ").append(DataSchema.qualify(tableName));

    Object[] params = buildSearchWhereClause(sql, searchColumns, search, columnTypes);

    if (sortBy != null) {
      // Skip sorting on GEOMETRY columns (not orderable)
      if (isGeometry(sortBy, columnTypes)) {
        sql.append(" ORDER BY id");
      } else {
        sql.append(" ORDER BY \"")
            .append(sortBy)
            .append("\" ")
            .append(sortDir)
            .append(" NULLS LAST, id ASC");
      }
    } else {
      sql.append(" ORDER BY id");
    }
    sql.append(" LIMIT ").append(size);
    sql.append(" OFFSET ").append(page * size);

    // 범위 밖 날짜·시각(10000년·BC·infinity·1582/DST 공백)은 Java 날짜 객체를 거치며 다른 값이 되므로 그 셀만 PG
    // 텍스트 원문으로 읽는다(#769). 정상 값은 예전과 같은 Java 값이라 응답·내보내기 형태가 같다.
    var result = TemporalSafeFetch.fetch(dsl, sql.toString(), params);
    List<Map<String, Object>> rows = new ArrayList<>();

    for (var record : result) {
      Map<String, Object> row = new HashMap<>();
      for (int i = 0; i < record.size(); i++) {
        String fieldName = record.field(i).getName();
        row.put(fieldName, record.get(i));
      }
      rows.add(row);
    }

    return rows;
  }

  /** 공간 필터를 포함한 queryData 오버로드 */
  @Override
  public List<Map<String, Object>> queryData(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      String sortBy,
      String sortDir,
      int page,
      int size,
      SpatialFilter spatialFilter) {
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    DataTableNames.validate(tableName);
    for (String col : columnNames) {
      DataTableNames.validate(col);
    }
    if (sortBy != null) {
      DataTableNames.validate(sortBy);
    }

    // Resolve geometry column if spatial filter present
    String geomCol = null;
    if (spatialFilter != null) {
      geomCol =
          DataColumnTypes.resolveGeometryColumn(
              columns, columnTypes, spatialFilter.geometryColumn());
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT id, ");
    if (columnNames.isEmpty()) {
      sql.append("*");
    } else {
      for (int i = 0; i < columnNames.size(); i++) {
        if (i > 0) sql.append(", ");
        sql.append(selectExpr(columnNames.get(i), columnTypes));
      }
    }
    // For Nearby: append distance column
    if (spatialFilter instanceof SpatialFilter.Nearby nearby) {
      sql.append(", ST_Distance(\"")
          .append(geomCol)
          .append("\"::geography, ST_SetSRID(ST_MakePoint(")
          .append(nearby.longitude())
          .append(", ")
          .append(nearby.latitude())
          .append("), 4326)::geography)::double precision AS \"_distance\"");
    }
    sql.append(" FROM ").append(DataSchema.qualify(tableName));

    // Build WHERE clause: search + spatial
    List<Object> paramList = new ArrayList<>();
    boolean hasWhere = false;

    // text search conditions
    List<String> searchableColumns = new ArrayList<>();
    if (search != null && !search.isBlank() && !columnNames.isEmpty()) {
      for (String col : columnNames) {
        if (!isGeometry(col, columnTypes)) {
          searchableColumns.add(col);
        }
      }
    }

    if (!searchableColumns.isEmpty()) {
      sql.append(" WHERE (");
      for (int i = 0; i < searchableColumns.size(); i++) {
        if (i > 0) sql.append(" OR ");
        sql.append("CAST(\"")
            .append(searchableColumns.get(i))
            .append("\" AS TEXT) ILIKE ? ESCAPE '\\'");
      }
      sql.append(")");
      String pattern = "%" + DataTableNames.escapeLike(search) + "%";
      for (int i = 0; i < searchableColumns.size(); i++) {
        paramList.add(pattern);
      }
      hasWhere = true;
    }

    // spatial conditions
    if (spatialFilter instanceof SpatialFilter.Nearby nearby) {
      sql.append(hasWhere ? " AND " : " WHERE ");
      sql.append("ST_DWithin(\"")
          .append(geomCol)
          .append("\"::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)");
      paramList.add(nearby.longitude());
      paramList.add(nearby.latitude());
      paramList.add(nearby.radiusMeters());
    } else if (spatialFilter instanceof SpatialFilter.Bbox bbox) {
      sql.append(hasWhere ? " AND " : " WHERE ");
      sql.append("ST_Intersects(\"")
          .append(geomCol)
          .append("\", ST_MakeEnvelope(?, ?, ?, ?, 4326))");
      paramList.add(bbox.minLongitude());
      paramList.add(bbox.minLatitude());
      paramList.add(bbox.maxLongitude());
      paramList.add(bbox.maxLatitude());
    }

    // ORDER BY
    if (spatialFilter instanceof SpatialFilter.Nearby) {
      sql.append(" ORDER BY \"_distance\"");
    } else if (sortBy != null) {
      if (isGeometry(sortBy, columnTypes)) {
        sql.append(" ORDER BY id");
      } else {
        sql.append(" ORDER BY \"")
            .append(sortBy)
            .append("\" ")
            .append(sortDir)
            .append(" NULLS LAST, id ASC");
      }
    } else {
      sql.append(" ORDER BY id");
    }
    sql.append(" LIMIT ").append(size);
    sql.append(" OFFSET ").append(page * size);

    Object[] params = paramList.toArray();
    // 범위 밖 날짜·시각(10000년·BC·infinity·1582/DST 공백)은 Java 날짜 객체를 거치며 다른 값이 되므로 그 셀만 PG
    // 텍스트 원문으로 읽는다(#769). 정상 값은 예전과 같은 Java 값이라 응답·내보내기 형태가 같다.
    var result = TemporalSafeFetch.fetch(dsl, sql.toString(), params);
    List<Map<String, Object>> rows = new ArrayList<>();

    for (var record : result) {
      Map<String, Object> row = new HashMap<>();
      for (int i = 0; i < record.size(); i++) {
        String fieldName = record.field(i).getName();
        row.put(fieldName, record.get(i));
      }
      rows.add(row);
    }

    return rows;
  }

  /** 공간 필터를 포함한 countRows 오버로드 */
  @Override
  public long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      SpatialFilter spatialFilter) {
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    DataTableNames.validate(tableName);

    // Resolve geometry column if spatial filter present
    String geomCol = null;
    if (spatialFilter != null) {
      geomCol =
          DataColumnTypes.resolveGeometryColumn(
              columns, columnTypes, spatialFilter.geometryColumn());
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(*) FROM ").append(DataSchema.qualify(tableName));

    List<Object> paramList = new ArrayList<>();
    boolean hasWhere = false;

    // text search conditions
    List<String> searchableColumns = new ArrayList<>();
    if (search != null && !search.isBlank() && !columnNames.isEmpty()) {
      for (String col : columnNames) {
        if (!isGeometry(col, columnTypes)) {
          searchableColumns.add(col);
        }
      }
    }

    if (!searchableColumns.isEmpty()) {
      sql.append(" WHERE (");
      for (int i = 0; i < searchableColumns.size(); i++) {
        if (i > 0) sql.append(" OR ");
        sql.append("CAST(\"")
            .append(searchableColumns.get(i))
            .append("\" AS TEXT) ILIKE ? ESCAPE '\\'");
      }
      sql.append(")");
      String pattern = "%" + DataTableNames.escapeLike(search) + "%";
      for (int i = 0; i < searchableColumns.size(); i++) {
        paramList.add(pattern);
      }
      hasWhere = true;
    }

    // spatial conditions
    if (spatialFilter instanceof SpatialFilter.Nearby nearby) {
      sql.append(hasWhere ? " AND " : " WHERE ");
      sql.append("ST_DWithin(\"")
          .append(geomCol)
          .append("\"::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)");
      paramList.add(nearby.longitude());
      paramList.add(nearby.latitude());
      paramList.add(nearby.radiusMeters());
    } else if (spatialFilter instanceof SpatialFilter.Bbox bbox) {
      sql.append(hasWhere ? " AND " : " WHERE ");
      sql.append("ST_Intersects(\"")
          .append(geomCol)
          .append("\", ST_MakeEnvelope(?, ?, ?, ?, 4326))");
      paramList.add(bbox.minLongitude());
      paramList.add(bbox.minLatitude());
      paramList.add(bbox.maxLongitude());
      paramList.add(bbox.maxLatitude());
    }

    Object[] params = paramList.toArray();
    Long count =
        params.length > 0
            ? dsl.fetchOne(sql.toString(), params).get(0, Long.class)
            : dsl.fetchOne(sql.toString()).get(0, Long.class);
    return count != null ? count : 0L;
  }

  @Override
  public long countRows(
      String tableName, List<String> columns, String search, Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(*) FROM ").append(DataSchema.qualify(tableName));

    Object[] params = buildSearchWhereClause(sql, columns, search, columnTypes);

    Long count =
        params.length > 0
            ? dsl.fetchOne(sql.toString(), params).get(0, Long.class)
            : dsl.fetchOne(sql.toString()).get(0, Long.class);
    return count != null ? count : 0L;
  }

  private Object[] buildSearchWhereClause(
      StringBuilder sql, List<String> columns, String search, Map<String, String> columnTypes) {
    if (search == null || search.isBlank() || columns.isEmpty()) {
      return new Object[0];
    }

    // Filter out GEOMETRY columns (not searchable via ILIKE)
    List<String> searchableColumns = new ArrayList<>();
    for (String col : columns) {
      if (!isGeometry(col, columnTypes)) {
        searchableColumns.add(col);
      }
    }

    if (searchableColumns.isEmpty()) {
      return new Object[0];
    }

    sql.append(" WHERE (");
    for (int i = 0; i < searchableColumns.size(); i++) {
      if (i > 0) sql.append(" OR ");
      sql.append("CAST(\"")
          .append(searchableColumns.get(i))
          .append("\" AS TEXT) ILIKE ? ESCAPE '\\'");
    }
    sql.append(")");

    String pattern = "%" + DataTableNames.escapeLike(search) + "%";
    Object[] params = new Object[searchableColumns.size()];
    for (int i = 0; i < searchableColumns.size(); i++) {
      params[i] = pattern;
    }
    return params;
  }

  @Override
  public boolean checkDataUniqueness(String tableName, List<String> pkColumnNames) {
    DataTableNames.validate(tableName);
    for (String col : pkColumnNames) {
      DataTableNames.validate(col);
    }
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(*) = COUNT(DISTINCT (");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(")) AS is_unique FROM ").append(DataSchema.qualify(tableName));
    return Boolean.TRUE.equals(dsl.fetchOne(sql.toString()).get(0, Boolean.class));
  }

  /** 컬럼에 NULL 이 한 건이라도 있는지 확인한다(EXISTS 라 첫 NULL 에서 멈춘다). */
  @Override
  public boolean hasNulls(String tableName, String columnName) {
    DataTableNames.validate(tableName);
    DataTableNames.validate(columnName);
    String sql =
        "SELECT EXISTS (SELECT 1 FROM "
            + DataSchema.qualify(tableName)
            + " WHERE \""
            + columnName
            + "\" IS NULL)";
    return Boolean.TRUE.equals(dsl.fetchOne(sql).get(0, Boolean.class));
  }

  @Override
  public List<Map<String, Object>> findDuplicateRows(
      String tableName, List<String> pkColumnNames, int limit) {
    DataTableNames.validate(tableName);
    for (String col : pkColumnNames) {
      DataTableNames.validate(col);
    }
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT ");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(", COUNT(*) AS duplicate_count FROM ")
        .append(DataSchema.qualify(tableName))
        .append(" GROUP BY ");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(" HAVING COUNT(*) > 1 LIMIT ").append(limit);

    // 중복 키가 DATE/TIMESTAMP 면 범위 밖 값이 다른 날짜로 보이지 않도록 PG 원문으로 읽는다(#769)
    var result = TemporalSafeFetch.fetch(dsl, sql.toString());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var record : result) {
      Map<String, Object> row = new HashMap<>();
      for (int i = 0; i < record.size(); i++) {
        row.put(record.field(i).getName(), record.get(i));
      }
      rows.add(row);
    }
    return rows;
  }

  @Override
  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement once
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO ").append(DataSchema.qualify(tableName)).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) baseSql.append(", ");
      baseSql.append("\"").append(columns.get(i)).append("\"");
    }
    baseSql.append(") VALUES ");

    // Build placeholders with GEOMETRY-aware expressions
    StringBuilder ph = new StringBuilder("(");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    // Batch in chunks of 500 rows
    int batchSize = 500;
    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder sql = new StringBuilder(baseSql);
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) sql.append(", ");
        sql.append(placeholders);
      }

      Object[] values = new Object[chunk.size() * columns.size()];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }
      dsl.execute(sql.toString(), values);
    }
  }

  @Override
  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement once
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO ").append(DataSchema.qualify(tableName)).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) baseSql.append(", ");
      baseSql.append("\"").append(columns.get(i)).append("\"");
    }
    baseSql.append(") VALUES ");

    StringBuilder ph = new StringBuilder("(");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    int totalRows = rows.size();
    int batchSize = 500;
    int processedRows = 0;

    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder sql = new StringBuilder(baseSql);
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) sql.append(", ");
        sql.append(placeholders);
      }

      Object[] values = new Object[chunk.size() * columns.size()];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }
      dsl.execute(sql.toString(), values);

      processedRows += chunk.size();
      progressCallback.accept(processedRows, totalRows);
    }
  }

  @Override
  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement with import_id prepended
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO ").append(DataSchema.qualify(tableName)).append(" (import_id, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) baseSql.append(", ");
      baseSql.append("\"").append(columns.get(i)).append("\"");
    }
    baseSql.append(") VALUES ");

    // import_id placeholder + column placeholders (GEOMETRY-aware)
    StringBuilder ph = new StringBuilder("(?, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    int batchSize = 500;
    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder sql = new StringBuilder(baseSql);
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) sql.append(", ");
        sql.append(placeholders);
      }

      Object[] values = new Object[chunk.size() * (columns.size() + 1)];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        values[idx++] = importId;
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }
      dsl.execute(sql.toString(), values);
    }
  }

  @Override
  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement with import_id prepended
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO ").append(DataSchema.qualify(tableName)).append(" (import_id, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) baseSql.append(", ");
      baseSql.append("\"").append(columns.get(i)).append("\"");
    }
    baseSql.append(") VALUES ");

    StringBuilder ph = new StringBuilder("(?, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    int totalRows = rows.size();
    int batchSize = 500;
    int processedRows = 0;

    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder sql = new StringBuilder(baseSql);
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) sql.append(", ");
        sql.append(placeholders);
      }

      Object[] values = new Object[chunk.size() * (columns.size() + 1)];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        values[idx++] = importId;
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }
      dsl.execute(sql.toString(), values);

      processedRows += chunk.size();
      progressCallback.accept(processedRows, totalRows);
    }
  }

  @Override
  public DataTableRowService.UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }

    if (rows.isEmpty()) {
      return new DataTableRowService.UpsertResult(0, 0);
    }

    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("UPSERT mode requires at least one primary key column");
    }

    // Columns that go in DO UPDATE SET: import_id + non-PK data columns (exclude created_at)
    Set<String> pkSet = new HashSet<>(pkColumns);
    List<String> updateCols = columns.stream().filter(c -> !pkSet.contains(c)).toList();

    // Build conflict target: (pkCol1, pkCol2, ...)
    StringBuilder conflictTarget = new StringBuilder("(");
    for (int i = 0; i < pkColumns.size(); i++) {
      if (i > 0) conflictTarget.append(", ");
      conflictTarget.append("\"").append(pkColumns.get(i)).append("\"");
    }
    conflictTarget.append(")");

    // Build DO UPDATE SET clause: import_id, then non-PK cols (created_at excluded)
    StringBuilder updateSet = new StringBuilder("import_id = EXCLUDED.import_id");
    for (String col : updateCols) {
      updateSet.append(", \"").append(col).append("\" = EXCLUDED.\"").append(col).append("\"");
    }

    // INSERT columns: import_id + all data columns
    StringBuilder insertCols = new StringBuilder("import_id, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) insertCols.append(", ");
      insertCols.append("\"").append(columns.get(i)).append("\"");
    }

    // Placeholders: import_id + columns (GEOMETRY-aware)
    StringBuilder ph = new StringBuilder("(?, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    String sqlTemplate =
        "INSERT INTO "
            + DataSchema.qualify(tableName)
            + " ("
            + insertCols
            + ") VALUES "
            + "%ROWS%"
            + " ON CONFLICT "
            + conflictTarget
            + " DO UPDATE SET "
            + updateSet
            + " RETURNING (xmax = 0) AS was_insert";

    int totalInserted = 0;
    int totalUpdated = 0;
    int batchSize = 500;

    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder rowsFragment = new StringBuilder();
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) rowsFragment.append(", ");
        rowsFragment.append(placeholders);
      }

      String sql = sqlTemplate.replace("%ROWS%", rowsFragment.toString());

      Object[] values = new Object[chunk.size() * (columns.size() + 1)];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        values[idx++] = importId;
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }

      var result = dsl.fetch(sql, values);
      for (var record : result) {
        Boolean wasInsert = record.get("was_insert", Boolean.class);
        if (Boolean.TRUE.equals(wasInsert)) {
          totalInserted++;
        } else {
          totalUpdated++;
        }
      }
    }

    return new DataTableRowService.UpsertResult(totalInserted, totalUpdated);
  }

  @Override
  public DataTableRowService.UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }

    if (rows.isEmpty()) {
      return new DataTableRowService.UpsertResult(0, 0);
    }

    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("UPSERT mode requires at least one primary key column");
    }

    Set<String> pkSet = new HashSet<>(pkColumns);
    List<String> updateCols = columns.stream().filter(c -> !pkSet.contains(c)).toList();

    StringBuilder conflictTarget = new StringBuilder("(");
    for (int i = 0; i < pkColumns.size(); i++) {
      if (i > 0) conflictTarget.append(", ");
      conflictTarget.append("\"").append(pkColumns.get(i)).append("\"");
    }
    conflictTarget.append(")");

    StringBuilder updateSet = new StringBuilder("import_id = EXCLUDED.import_id");
    for (String col : updateCols) {
      updateSet.append(", \"").append(col).append("\" = EXCLUDED.\"").append(col).append("\"");
    }

    StringBuilder insertCols = new StringBuilder("import_id, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) insertCols.append(", ");
      insertCols.append("\"").append(columns.get(i)).append("\"");
    }

    StringBuilder ph = new StringBuilder("(?, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    String sqlTemplate =
        "INSERT INTO "
            + DataSchema.qualify(tableName)
            + " ("
            + insertCols
            + ") VALUES "
            + "%ROWS%"
            + " ON CONFLICT "
            + conflictTarget
            + " DO UPDATE SET "
            + updateSet
            + " RETURNING (xmax = 0) AS was_insert";

    int totalInserted = 0;
    int totalUpdated = 0;
    int totalRows = rows.size();
    int batchSize = 500;
    int processedRows = 0;

    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder rowsFragment = new StringBuilder();
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) rowsFragment.append(", ");
        rowsFragment.append(placeholders);
      }

      String sql = sqlTemplate.replace("%ROWS%", rowsFragment.toString());

      Object[] values = new Object[chunk.size() * (columns.size() + 1)];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        values[idx++] = importId;
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }

      var result = dsl.fetch(sql, values);
      for (var record : result) {
        Boolean wasInsert = record.get("was_insert", Boolean.class);
        if (Boolean.TRUE.equals(wasInsert)) {
          totalInserted++;
        } else {
          totalUpdated++;
        }
      }

      processedRows += chunk.size();
      progressCallback.accept(processedRows, totalRows);
    }

    return new DataTableRowService.UpsertResult(totalInserted, totalUpdated);
  }

  @Override
  public int deleteRows(String tableName, List<Long> rowIds) {
    DataTableNames.validate(tableName);
    if (rowIds == null || rowIds.isEmpty()) return 0;
    String sql = "DELETE FROM " + DataSchema.qualify(tableName) + " WHERE id = ANY(?)";
    Long[] idArray = rowIds.toArray(new Long[0]);
    return dsl.execute(sql, (Object) idArray);
  }

  @Override
  public void truncateTable(String tableName) {
    DataTableNames.validate(tableName);
    // 파괴 연산 — 스키마를 손으로 적지 않고 헬퍼로 한정한다(잘못 해석되면 교차 테넌트 삭제).
    String sql = "TRUNCATE TABLE " + DataSchema.qualify(tableName);
    dsl.execute(sql);
  }

  @Override
  public Long insertRow(
      String tableName,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("INSERT INTO ").append(DataSchema.qualify(tableName)).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(columns.get(i)).append("\"");
    }
    sql.append(") VALUES (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    sql.append(") RETURNING id");

    Object[] values = new Object[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      values[i] = row.get(columns.get(i));
    }

    var record = dsl.fetchOne(sql.toString(), values);
    return record != null ? record.get(0, Long.class) : null;
  }

  @Override
  public void updateRow(
      String tableName,
      long rowId,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE ").append(DataSchema.qualify(tableName)).append(" SET ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"")
          .append(columns.get(i))
          .append("\" = ")
          .append(insertPlaceholder(columns.get(i), columnTypes));
    }
    sql.append(" WHERE id = ?");

    Object[] values = new Object[columns.size() + 1];
    for (int i = 0; i < columns.size(); i++) {
      values[i] = row.get(columns.get(i));
    }
    values[columns.size()] = rowId;

    int affected = dsl.execute(sql.toString(), values);
    if (affected == 0) {
      throw new RowNotFoundException("Row not found: " + rowId);
    }
  }

  @Override
  public Map<String, Object> getRow(
      String tableName, List<String> columns, long rowId, Map<String, String> columnTypes) {
    DataTableNames.validate(tableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT id, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append(selectExpr(columns.get(i), columnTypes));
    }
    sql.append(", created_at FROM ").append(DataSchema.qualify(tableName)).append(" WHERE id = ?");

    // 단건 조회도 데이터 탭과 같은 계약 — 범위 밖 날짜·시각은 PG 원문(#769)
    var record = TemporalSafeFetch.fetchOne(dsl, sql.toString(), rowId);
    if (record == null) {
      throw new RowNotFoundException("Row not found: " + rowId);
    }

    Map<String, Object> result = new HashMap<>();
    for (int i = 0; i < record.size(); i++) {
      result.put(record.field(i).getName(), record.get(i));
    }
    return result;
  }

  /**
   * target 테이블의 데이터 컬럼 타입을 information_schema에서 복제해 data 스키마에 staging용 실제 테이블을 생성한다.
   *
   * <p>테이블명은 {@code stg_import_} + UUID hex(하이픈 제거, 소문자)로 생성되며 항상 {@code
   * DataTableService.validateName}을 통과한다. id/import_id/created_at 등 target의 시스템 컬럼은 복제하지 않고, 대신 파일
   * 내 삽입 순서를 기록하는 {@code _seq BIGSERIAL}을 추가한다. staging 테이블에는 PK unique 제약을 두지 않아 동일 PK가 여러 배치에 걸쳐
   * 중복 삽입되는 것을 허용한다(이게 이 설계의 핵심 — dedup을 promote 시점 SQL로 미룬다).
   *
   * <p>CREATE TABLE ... LIKE target INCLUDING ALL을 쓰지 않는 이유: LIKE INCLUDING ALL은 target의 PK unique
   * index까지 복제하므로, staging에 같은 PK를 두 번 삽입하는 순간 unique violation이 발생해 이 설계의 전제(중복 허용)가 깨진다.
   *
   * @return 생성된 staging 테이블명
   */
  @Override
  public String createStagingTable(String targetTableName, List<String> columns) {
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    // UUID hex(32자, [0-9a-f])는 항상 [a-z][a-z0-9_]* 패턴을 만족한다.
    String stagingTable = "stg_import_" + UUID.randomUUID().toString().replace("-", "");
    DataTableNames.validate(stagingTable);

    Map<String, String> columnDdl = introspectColumnDdl(targetTableName, columns);

    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE ").append(DataSchema.qualify(stagingTable)).append(" (");
    sql.append("_seq BIGSERIAL");
    for (String col : columns) {
      sql.append(", \"").append(col).append("\" ").append(columnDdl.get(col));
    }
    sql.append(")");
    dsl.execute(sql.toString());

    return stagingTable;
  }

  /**
   * information_schema.columns를 조회해 대상 테이블의 컬럼별 DDL 타입 문자열을 만든다. GEOMETRY 컬럼은
   * DataTableService.createTable이 항상 GEOMETRY(Geometry, 4326)로만 생성하므로 그 형태를 그대로 재현한다.
   *
   * <p>반환된 DDL 문자열은 CREATE TABLE에 쓰일 뿐 아니라, GEOMETRY/TIMESTAMP/BOOLEAN/BIGINT/NUMERIC/DATE 키워드를
   * 포함하고 있어 기존 insertPlaceholder(colName, columnTypes)의 타입 판별(부분 문자열 매칭)에도 그대로 재사용할 수 있다.
   */
  private Map<String, String> introspectColumnDdl(String tableName, List<String> columns) {
    String sql =
        "SELECT column_name, data_type, udt_name, character_maximum_length, "
            + "numeric_precision, numeric_scale "
            + "FROM information_schema.columns "
            + "WHERE table_schema = ? AND table_name = ?";
    // 스키마명을 리터럴로 박으면 개명 후 조용히 0행이 되고, 그 결과가 "Column not found" 로 둔갑한다.
    var result = dsl.fetch(sql, DataSchema.current(), tableName);

    Map<String, String> ddlByColumn = new HashMap<>();
    for (var record : result) {
      String colName = record.get("column_name", String.class);
      String dataType = record.get("data_type", String.class);
      String udtName = record.get("udt_name", String.class);
      Integer maxLen = record.get("character_maximum_length", Integer.class);
      Integer precision = record.get("numeric_precision", Integer.class);
      Integer scale = record.get("numeric_scale", Integer.class);
      ddlByColumn.put(colName, toDdlType(dataType, udtName, maxLen, precision, scale));
    }

    Map<String, String> ddlByRequestedColumn = new HashMap<>();
    for (String col : columns) {
      String ddl = ddlByColumn.get(col);
      if (ddl == null) {
        throw new IllegalStateException(
            "Column '" + col + "' not found on table '" + tableName + "'");
      }
      ddlByRequestedColumn.put(col, ddl);
    }
    return ddlByRequestedColumn;
  }

  private String toDdlType(
      String dataType, String udtName, Integer maxLen, Integer precision, Integer scale) {
    if ("geometry".equalsIgnoreCase(udtName)) {
      return "GEOMETRY(Geometry, 4326)";
    }
    return switch (dataType) {
      case "text" -> "TEXT";
      case "character varying" -> "VARCHAR(" + (maxLen != null ? maxLen : 255) + ")";
      case "bigint" -> "BIGINT";
      case "numeric" -> "NUMERIC(" + precision + "," + scale + ")";
      case "boolean" -> "BOOLEAN";
      case "date" -> "DATE";
      case "timestamp without time zone" -> "TIMESTAMP";
      default -> "TEXT";
    };
  }

  /**
   * staging 테이블에 rows를 500행 청크로 스트리밍 삽입한다. 기존 insertBatchWithProgress와 동일한 청크/progress 패턴을 따르되,
   * import_id는 다루지 않는다. {@code _seq}는 BIGSERIAL DEFAULT로 자동 채번되므로 데이터 컬럼만 명시적으로 INSERT하며, 배치는 호출
   * 순서대로 실행되므로 {@code _seq}가 파일 내 등장 순서를 그대로 반영한다(promote 시 LWW 판정 근거).
   */
  @Override
  public void insertStagingBatchWithProgress(
      String stagingTable,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    DataTableNames.validate(stagingTable);
    for (String col : columns) {
      DataTableNames.validate(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // staging 테이블은 target과 동일한 컬럼 타입으로 생성되었으므로, staging 자신을 introspect해
    // GEOMETRY 등 타입-인지 placeholder(ST_GeomFromGeoJSON 등)를 그대로 재사용한다.
    Map<String, String> columnTypes = introspectColumnDdl(stagingTable, columns);

    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO ").append(DataSchema.qualify(stagingTable)).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) baseSql.append(", ");
      baseSql.append("\"").append(columns.get(i)).append("\"");
    }
    baseSql.append(") VALUES ");

    StringBuilder ph = new StringBuilder("(");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) ph.append(", ");
      ph.append(insertPlaceholder(columns.get(i), columnTypes));
    }
    ph.append(")");
    String placeholders = ph.toString();

    int totalRows = rows.size();
    int batchSize = 500;
    int processedRows = 0;

    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      List<Map<String, Object>> chunk = rows.subList(start, end);

      StringBuilder sql = new StringBuilder(baseSql);
      for (int r = 0; r < chunk.size(); r++) {
        if (r > 0) sql.append(", ");
        sql.append(placeholders);
      }

      Object[] values = new Object[chunk.size() * columns.size()];
      int idx = 0;
      for (Map<String, Object> row : chunk) {
        for (String col : columns) {
          values[idx++] = row.get(col);
        }
      }
      dsl.execute(sql.toString(), values);

      processedRows += chunk.size();
      progressCallback.accept(processedRows, totalRows);
    }
  }

  /**
   * staging 테이블의 내용을 target 테이블로 promote(UPSERT)한다. {@code DISTINCT ON (pk) ORDER BY pk, _seq
   * DESC}로 각 PK별 파일 내 마지막 등장 행만 선택한 뒤, 기존 upsertBatch와 동일한 {@code ON CONFLICT ... DO UPDATE}로
   * target에 반영한다.
   *
   * <p>inserted/updated 카운트는 기존과 동일하게 PostgreSQL xmax 트릭(RETURNING (xmax = 0))으로 판별하되, 대량 upsert 시
   * 결과 row 전체를 JVM으로 fetch하면(예: 수백만 건) 스트리밍으로 없앤 OOM을 카운팅 단계에서 재도입하게 된다. 이를 피하기 위해 data-modifying
   * CTE로 INSERT를 감싸고, 바깥 SELECT에서 count(*) FILTER로 서버 사이드에서 집계해 최종적으로 단 한 행(inserted, updated)만
   * JVM으로 가져온다.
   */
  @Override
  public DataTableRowService.UpsertResult promoteStagingToUpsert(
      String stagingTable,
      String targetTableName,
      List<String> columns,
      List<String> pkColumns,
      Long importId) {
    DataTableNames.validate(stagingTable);
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }

    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("UPSERT mode requires at least one primary key column");
    }

    Set<String> pkSet = new HashSet<>(pkColumns);
    List<String> updateCols = columns.stream().filter(c -> !pkSet.contains(c)).toList();

    StringBuilder pkList = new StringBuilder();
    for (int i = 0; i < pkColumns.size(); i++) {
      if (i > 0) pkList.append(", ");
      pkList.append("\"").append(pkColumns.get(i)).append("\"");
    }

    StringBuilder colsList = new StringBuilder();
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) colsList.append(", ");
      colsList.append("\"").append(columns.get(i)).append("\"");
    }

    StringBuilder conflictTarget = new StringBuilder("(").append(pkList).append(")");

    StringBuilder updateSet = new StringBuilder("import_id = EXCLUDED.import_id");
    for (String col : updateCols) {
      updateSet.append(", \"").append(col).append("\" = EXCLUDED.\"").append(col).append("\"");
    }

    String sql =
        "WITH promoted AS ("
            + "INSERT INTO "
            + DataSchema.qualify(targetTableName)
            + " (import_id, "
            + colsList
            + ") "
            + "SELECT DISTINCT ON ("
            + pkList
            + ") ?::bigint, "
            + colsList
            + " FROM "
            + DataSchema.qualify(stagingTable)
            + " "
            + "ORDER BY "
            + pkList
            + ", _seq DESC "
            + "ON CONFLICT "
            + conflictTarget
            + " DO UPDATE SET "
            + updateSet
            + " RETURNING (xmax = 0) AS was_insert"
            + ") "
            + "SELECT count(*) FILTER (WHERE was_insert) AS inserted, "
            + "count(*) FILTER (WHERE NOT was_insert) AS updated "
            + "FROM promoted";

    var record = dsl.fetchOne(sql, importId);
    long inserted = record != null ? record.get("inserted", Long.class) : 0L;
    long updated = record != null ? record.get("updated", Long.class) : 0L;

    return new DataTableRowService.UpsertResult((int) inserted, (int) updated);
  }

  /**
   * staging 테이블 내용으로 target 테이블을 완전히 교체(REPLACE)한다. target을 TRUNCATE한 뒤, {@code DISTINCT ON (pk)
   * ORDER BY pk, _seq DESC}로 PK별 파일 내 마지막 등장 행만 삽입한다.
   *
   * <p>TRUNCATE + INSERT가 원자적으로 처리되어야 하므로, 호출자가 {@code transactionTemplate} 등으로 감싼 트랜잭션 내에서 호출해야
   * 한다(기존 DataImportService의 REPLACE 처리 패턴과 동일).
   */
  @Override
  public void promoteStagingToReplace(
      String stagingTable, String targetTableName, List<String> columns, List<String> pkColumns) {
    DataTableNames.validate(stagingTable);
    DataTableNames.validate(targetTableName);
    for (String col : columns) {
      DataTableNames.validate(col);
    }
    for (String pkCol : pkColumns) {
      DataTableNames.validate(pkCol);
    }

    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("REPLACE promote requires at least one primary key column");
    }

    // 파괴 연산 — target 을 통째로 비운다. 반드시 헬퍼로 현재 테넌트 스키마에 한정한다.
    dsl.execute("TRUNCATE TABLE " + DataSchema.qualify(targetTableName));

    StringBuilder pkList = new StringBuilder();
    for (int i = 0; i < pkColumns.size(); i++) {
      if (i > 0) pkList.append(", ");
      pkList.append("\"").append(pkColumns.get(i)).append("\"");
    }

    StringBuilder colsList = new StringBuilder();
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) colsList.append(", ");
      colsList.append("\"").append(columns.get(i)).append("\"");
    }

    String sql =
        "INSERT INTO "
            + DataSchema.qualify(targetTableName)
            + " ("
            + colsList
            + ") "
            + "SELECT DISTINCT ON ("
            + pkList
            + ") "
            + colsList
            + " FROM "
            + DataSchema.qualify(stagingTable)
            + " "
            + "ORDER BY "
            + pkList
            + ", _seq DESC";

    dsl.execute(sql);
  }

  /** staging 테이블을 삭제한다. import job 완료(성공/실패 무관) 후 항상 호출되어야 한다. */
  @Override
  public void dropStagingTable(String stagingTable) {
    DataTableNames.validate(stagingTable);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(stagingTable));
  }

  /**
   * PostgreSQL 은 0 을 돌려준다 — PG 의 고아 staging 은 {@code StagingTableCleanupService} 가 테넌트 {@code data}
   * 스키마를 직접 열거해 정리하며(나이 기준 없이 활성 작업 게이트만), 그 경로는 그대로 둔다. 이 메서드는 외부 저장소 백엔드용이다.
   */
  @Override
  public int dropStaleStagingTables(long tenantId, Duration minAge) {
    return 0;
  }
}
