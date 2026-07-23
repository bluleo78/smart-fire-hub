package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SpatialFilter;
import com.smartfirehub.dataset.exception.RowNotFoundException;
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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DataTableRowService {

  private final DSLContext dsl;
  private final DataTableService dataTableService;

  /**
   * Result of an upsert batch operation. inserted: number of rows newly inserted (xmax = 0 in
   * PostgreSQL means the row was just inserted). updated: number of rows that already existed and
   * were updated.
   */
  public record UpsertResult(int inserted, int updated) {}

  // --- GEOMETRY helpers ---

  static boolean isGeometry(String colName, Map<String, String> columnTypes) {
    return columnTypes != null && "GEOMETRY".equalsIgnoreCase(columnTypes.get(colName));
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
    if (upper.contains("INTEGER") || upper.contains("INT")) return "?::integer";
    if (upper.contains("NUMERIC")) return "?::numeric";
    if (upper.contains("DATE")) return "?::date";
    return "?";
  }

  public List<Map<String, Object>> queryData(
      String tableName, List<String> columns, String search, int page, int size) {
    return queryData(tableName, columns, search, page, size, null, "ASC", null);
  }

  public List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir) {
    return queryData(tableName, columns, search, page, size, sortBy, sortDir, null);
  }

  public List<Map<String, Object>> queryData(
      String tableName,
      List<String> columns,
      String search,
      int page,
      int size,
      String sortBy,
      String sortDir,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }
    if (sortBy != null) {
      dataTableService.validateName(sortBy);
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
    sql.append(" FROM data.\"").append(tableName).append("\"");

    Object[] params = buildSearchWhereClause(sql, columns, search, columnTypes);

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

    var result = params.length > 0 ? dsl.fetch(sql.toString(), params) : dsl.fetch(sql.toString());
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

  // --- Spatial query overloads ---

  /**
   * GEOMETRY 컬럼 이름 해결. geometryColumn이 null이면 첫 번째 GEOMETRY 컬럼 자동 선택.
   *
   * @throws IllegalArgumentException GEOMETRY 컬럼이 없거나 지정 컬럼이 GEOMETRY가 아닌 경우
   */
  public static String resolveGeometryColumn(
      List<DatasetColumnResponse> columns, Map<String, String> columnTypes, String geometryColumn) {
    if (geometryColumn != null) {
      if (!isGeometry(geometryColumn, columnTypes)) {
        throw new IllegalArgumentException("컬럼 '" + geometryColumn + "'은 GEOMETRY 타입이 아닙니다.");
      }
      return geometryColumn;
    }
    // auto-select first GEOMETRY column
    for (DatasetColumnResponse col : columns) {
      if (isGeometry(col.columnName(), columnTypes)) {
        return col.columnName();
      }
    }
    throw new IllegalArgumentException("데이터셋에 GEOMETRY 컬럼이 없습니다.");
  }

  /** 기존 시그니처 유지 (하위 호환) */
  public List<Map<String, Object>> queryData(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      String sortBy,
      String sortDir,
      int page,
      int size) {
    return queryData(tableName, columns, columnTypes, search, sortBy, sortDir, page, size, null);
  }

  /** 공간 필터를 포함한 queryData 오버로드 */
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
    dataTableService.validateName(tableName);
    for (String col : columnNames) {
      dataTableService.validateName(col);
    }
    if (sortBy != null) {
      dataTableService.validateName(sortBy);
    }

    // Resolve geometry column if spatial filter present
    String geomCol = null;
    if (spatialFilter != null) {
      geomCol = resolveGeometryColumn(columns, columnTypes, spatialFilter.geometryColumn());
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
    sql.append(" FROM data.\"").append(tableName).append("\"");

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
      String pattern = "%" + escapeIlike(search) + "%";
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
    var result = params.length > 0 ? dsl.fetch(sql.toString(), params) : dsl.fetch(sql.toString());
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

  /** 기존 시그니처 유지 (하위 호환) */
  public long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search) {
    return countRows(tableName, columns, columnTypes, search, null);
  }

  /** 공간 필터를 포함한 countRows 오버로드 */
  public long countRows(
      String tableName,
      List<DatasetColumnResponse> columns,
      Map<String, String> columnTypes,
      String search,
      SpatialFilter spatialFilter) {
    List<String> columnNames = columns.stream().map(DatasetColumnResponse::columnName).toList();
    dataTableService.validateName(tableName);

    // Resolve geometry column if spatial filter present
    String geomCol = null;
    if (spatialFilter != null) {
      geomCol = resolveGeometryColumn(columns, columnTypes, spatialFilter.geometryColumn());
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(*) FROM data.\"").append(tableName).append("\"");

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
      String pattern = "%" + escapeIlike(search) + "%";
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

  public long countRows(String tableName) {
    return countRows(tableName, List.of(), null);
  }

  public long countRows(String tableName, List<String> columns, String search) {
    return countRows(tableName, columns, search, null);
  }

  public long countRows(
      String tableName, List<String> columns, String search, Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(*) FROM data.\"").append(tableName).append("\"");

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

    String pattern = "%" + escapeIlike(search) + "%";
    Object[] params = new Object[searchableColumns.size()];
    for (int i = 0; i < searchableColumns.size(); i++) {
      params[i] = pattern;
    }
    return params;
  }

  private String escapeIlike(String input) {
    return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  public boolean checkDataUniqueness(String tableName, List<String> pkColumnNames) {
    dataTableService.validateName(tableName);
    for (String col : pkColumnNames) {
      dataTableService.validateName(col);
    }
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT COUNT(*) = COUNT(DISTINCT (");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(")) AS is_unique FROM data.\"").append(tableName).append("\"");
    return Boolean.TRUE.equals(dsl.fetchOne(sql.toString()).get(0, Boolean.class));
  }

  public List<Map<String, Object>> findDuplicateRows(
      String tableName, List<String> pkColumnNames, int limit) {
    dataTableService.validateName(tableName);
    for (String col : pkColumnNames) {
      dataTableService.validateName(col);
    }
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT ");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(", COUNT(*) AS duplicate_count FROM data.\"")
        .append(tableName)
        .append("\" GROUP BY ");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(" HAVING COUNT(*) > 1 LIMIT ").append(limit);

    var result = dsl.fetch(sql.toString());
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

  public void insertBatch(String tableName, List<String> columns, List<Map<String, Object>> rows) {
    insertBatch(tableName, columns, rows, (Map<String, String>) null);
  }

  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement once
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO data.\"").append(tableName).append("\" (");
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

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    insertBatchWithProgress(tableName, columns, rows, progressCallback, null);
  }

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement once
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO data.\"").append(tableName).append("\" (");
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

  // ---------------------------------------------------------------------------
  // Overloaded insertBatch / insertBatchWithProgress that also populate import_id
  // ---------------------------------------------------------------------------

  public void insertBatch(
      String tableName, List<String> columns, List<Map<String, Object>> rows, Long importId) {
    insertBatch(tableName, columns, rows, importId, null);
  }

  public void insertBatch(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement with import_id prepended
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO data.\"").append(tableName).append("\" (import_id, ");
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

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback) {
    insertBatchWithProgress(tableName, columns, rows, importId, progressCallback, null);
  }

  public void insertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // Build base INSERT statement with import_id prepended
    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO data.\"").append(tableName).append("\" (import_id, ");
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

  // ---------------------------------------------------------------------------
  // upsertBatch / upsertBatchWithProgress
  // ---------------------------------------------------------------------------

  /**
   * Upsert a batch of rows using PostgreSQL's ON CONFLICT DO UPDATE.
   *
   * <p>The conflict target is the unique index on pkColumns (ux_{tableName}_pk). created_at is
   * intentionally excluded from DO UPDATE SET to preserve the original creation timestamp on
   * subsequent updates.
   *
   * <p>PostgreSQL xmax trick: when a row is freshly inserted, its xmax system column is 0. When it
   * is updated (DELETE + re-insert under the hood), xmax holds the transaction ID of the deleting
   * transaction (non-zero). By returning (xmax = 0) AS was_insert we can distinguish inserts from
   * updates without a separate SELECT.
   */
  public UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId) {
    return upsertBatch(tableName, columns, pkColumns, rows, importId, null);
  }

  public UpsertResult upsertBatch(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }
    for (String pkCol : pkColumns) {
      dataTableService.validateName(pkCol);
    }

    if (rows.isEmpty()) {
      return new UpsertResult(0, 0);
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
        "INSERT INTO data.\""
            + tableName
            + "\" ("
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

    return new UpsertResult(totalInserted, totalUpdated);
  }

  public UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback) {
    return upsertBatchWithProgress(
        tableName, columns, pkColumns, rows, importId, progressCallback, null);
  }

  public UpsertResult upsertBatchWithProgress(
      String tableName,
      List<String> columns,
      List<String> pkColumns,
      List<Map<String, Object>> rows,
      Long importId,
      BiConsumer<Integer, Integer> progressCallback,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }
    for (String pkCol : pkColumns) {
      dataTableService.validateName(pkCol);
    }

    if (rows.isEmpty()) {
      return new UpsertResult(0, 0);
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
        "INSERT INTO data.\""
            + tableName
            + "\" ("
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

    return new UpsertResult(totalInserted, totalUpdated);
  }

  public int deleteRows(String tableName, List<Long> rowIds) {
    dataTableService.validateName(tableName);
    if (rowIds == null || rowIds.isEmpty()) return 0;
    String sql = "DELETE FROM data.\"" + tableName + "\" WHERE id = ANY(?)";
    Long[] idArray = rowIds.toArray(new Long[0]);
    return dsl.execute(sql, (Object) idArray);
  }

  public void truncateTable(String tableName) {
    dataTableService.validateName(tableName);
    String sql = "TRUNCATE TABLE data.\"" + tableName + "\"";
    dsl.execute(sql);
  }

  public Long insertRow(String tableName, List<String> columns, Map<String, Object> row) {
    return insertRow(tableName, columns, row, null);
  }

  public Long insertRow(
      String tableName,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("INSERT INTO data.\"").append(tableName).append("\" (");
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

  public void updateRow(
      String tableName, long rowId, List<String> columns, Map<String, Object> row) {
    updateRow(tableName, rowId, columns, row, null);
  }

  public void updateRow(
      String tableName,
      long rowId,
      List<String> columns,
      Map<String, Object> row,
      Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE data.\"").append(tableName).append("\" SET ");
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

  public Map<String, Object> getRow(String tableName, List<String> columns, long rowId) {
    return getRow(tableName, columns, rowId, null);
  }

  public Map<String, Object> getRow(
      String tableName, List<String> columns, long rowId, Map<String, String> columnTypes) {
    dataTableService.validateName(tableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT id, ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append(selectExpr(columns.get(i), columnTypes));
    }
    sql.append(", created_at FROM data.\"").append(tableName).append("\" WHERE id = ?");

    var record = dsl.fetchOne(sql.toString(), rowId);
    if (record == null) {
      throw new RowNotFoundException("Row not found: " + rowId);
    }

    Map<String, Object> result = new HashMap<>();
    for (int i = 0; i < record.size(); i++) {
      result.put(record.field(i).getName(), record.get(i));
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Staging table lifecycle — 대용량 파일 스트리밍 UPSERT/REPLACE를 위한 SQL측 dedup
  //
  // 기존 upsertBatch/insertBatch는 파일 전체를 메모리에 올려 in-memory
  // dedupeByPrimaryKeysLastWins(LinkedHashMap)로 파일 내 중복 PK를 제거한 뒤 적재했다.
  // 100~400MB급 파일은 이 전체 로딩이 OOM을 유발한다.
  //
  // 대안: 배치 단위로 실제 테이블(staging)에 순서대로 스트리밍 삽입(_seq BIGSERIAL로 순서 기록)한 뒤,
  // promote 시점에 SQL의 DISTINCT ON (pk) ORDER BY pk, _seq DESC로 "파일 내 마지막 등장 값만" 뽑아
  // target에 반영한다. dedup 로직 자체를 DB 엔진으로 위임해 JVM 힙에 전체 rows를 올리지 않는다.
  //
  // TEMP TABLE을 쓰지 않는 이유: TEMP TABLE은 커넥션/트랜잭션 스코프이므로, 배치마다 커밋하며
  // 스트리밍하려면 하나의 거대한 트랜잭션(=커넥션 점유)을 강제하게 되어 스트리밍의 이점이 사라진다.
  // 따라서 data 스키마에 실제(영구) 테이블을 만들고 완료 후 명시적으로 DROP한다.
  // ---------------------------------------------------------------------------

  /**
   * target 테이블의 데이터 컬럼 타입을 information_schema에서 복제해 data 스키마에 staging용 실제 테이블을 생성한다.
   *
   * <p>테이블명은 {@code stg_import_} + UUID hex(하이픈 제거, 소문자)로 생성되며 항상 {@code
   * DataTableService.validateName}을 통과한다. id/import_id/created_at 등 target의 시스템 컬럼은 복제하지 않고, 대신
   * 파일 내 삽입 순서를 기록하는 {@code _seq BIGSERIAL}을 추가한다. staging 테이블에는 PK unique 제약을 두지 않아 동일 PK가 여러 배치에
   * 걸쳐 중복 삽입되는 것을 허용한다(이게 이 설계의 핵심 — dedup을 promote 시점 SQL로 미룬다).
   *
   * <p>CREATE TABLE ... LIKE target INCLUDING ALL을 쓰지 않는 이유: LIKE INCLUDING ALL은 target의 PK unique
   * index까지 복제하므로, staging에 같은 PK를 두 번 삽입하는 순간 unique violation이 발생해 이 설계의 전제(중복 허용)가 깨진다.
   *
   * @return 생성된 staging 테이블명
   */
  public String createStagingTable(String targetTableName, List<String> columns) {
    dataTableService.validateName(targetTableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    // UUID hex(32자, [0-9a-f])는 항상 [a-z][a-z0-9_]* 패턴을 만족한다.
    String stagingTable = "stg_import_" + UUID.randomUUID().toString().replace("-", "");
    dataTableService.validateName(stagingTable);

    Map<String, String> columnDdl = introspectColumnDdl(targetTableName, columns);

    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE data.\"").append(stagingTable).append("\" (");
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
            + "WHERE table_schema = 'data' AND table_name = ?";
    var result = dsl.fetch(sql, tableName);

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
  public void insertStagingBatchWithProgress(
      String stagingTable,
      List<String> columns,
      List<Map<String, Object>> rows,
      BiConsumer<Integer, Integer> progressCallback) {
    dataTableService.validateName(stagingTable);
    for (String col : columns) {
      dataTableService.validateName(col);
    }

    if (rows.isEmpty()) {
      return;
    }

    // staging 테이블은 target과 동일한 컬럼 타입으로 생성되었으므로, staging 자신을 introspect해
    // GEOMETRY 등 타입-인지 placeholder(ST_GeomFromGeoJSON 등)를 그대로 재사용한다.
    Map<String, String> columnTypes = introspectColumnDdl(stagingTable, columns);

    StringBuilder baseSql = new StringBuilder();
    baseSql.append("INSERT INTO data.\"").append(stagingTable).append("\" (");
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
   * <p>inserted/updated 카운트는 기존과 동일하게 PostgreSQL xmax 트릭(RETURNING (xmax = 0))으로 판별하되, 대량 upsert
   * 시 결과 row 전체를 JVM으로 fetch하면(예: 수백만 건) 스트리밍으로 없앤 OOM을 카운팅 단계에서 재도입하게 된다. 이를 피하기 위해
   * data-modifying CTE로 INSERT를 감싸고, 바깥 SELECT에서 count(*) FILTER로 서버 사이드에서 집계해 최종적으로 단 한 행(inserted,
   * updated)만 JVM으로 가져온다.
   */
  public UpsertResult promoteStagingToUpsert(
      String stagingTable,
      String targetTableName,
      List<String> columns,
      List<String> pkColumns,
      Long importId) {
    dataTableService.validateName(stagingTable);
    dataTableService.validateName(targetTableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }
    for (String pkCol : pkColumns) {
      dataTableService.validateName(pkCol);
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
            + "INSERT INTO data.\""
            + targetTableName
            + "\" (import_id, "
            + colsList
            + ") "
            + "SELECT DISTINCT ON ("
            + pkList
            + ") ?::bigint, "
            + colsList
            + " FROM data.\""
            + stagingTable
            + "\" "
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

    return new UpsertResult((int) inserted, (int) updated);
  }

  /**
   * staging 테이블 내용으로 target 테이블을 완전히 교체(REPLACE)한다. target을 TRUNCATE한 뒤, {@code DISTINCT ON
   * (pk) ORDER BY pk, _seq DESC}로 PK별 파일 내 마지막 등장 행만 삽입한다.
   *
   * <p>TRUNCATE + INSERT가 원자적으로 처리되어야 하므로, 호출자가 {@code transactionTemplate} 등으로 감싼 트랜잭션 내에서
   * 호출해야 한다(기존 DataImportService의 REPLACE 처리 패턴과 동일).
   */
  public void promoteStagingToReplace(
      String stagingTable, String targetTableName, List<String> columns, List<String> pkColumns) {
    dataTableService.validateName(stagingTable);
    dataTableService.validateName(targetTableName);
    for (String col : columns) {
      dataTableService.validateName(col);
    }
    for (String pkCol : pkColumns) {
      dataTableService.validateName(pkCol);
    }

    if (pkColumns.isEmpty()) {
      throw new IllegalStateException("REPLACE promote requires at least one primary key column");
    }

    dsl.execute("TRUNCATE TABLE data.\"" + targetTableName + "\"");

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
        "INSERT INTO data.\""
            + targetTableName
            + "\" ("
            + colsList
            + ") "
            + "SELECT DISTINCT ON ("
            + pkList
            + ") "
            + colsList
            + " FROM data.\""
            + stagingTable
            + "\" "
            + "ORDER BY "
            + pkList
            + ", _seq DESC";

    dsl.execute(sql);
  }

  /** staging 테이블을 삭제한다. import job 완료(성공/실패 무관) 후 항상 호출되어야 한다. */
  public void dropStagingTable(String stagingTable) {
    dataTableService.validateName(stagingTable);
    dsl.execute("DROP TABLE IF EXISTS data.\"" + stagingTable + "\"");
  }
}
