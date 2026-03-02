package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.exception.RowNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

@Service
public class DataTableRowService {

  private final DSLContext dsl;
  private final DataTableService dataTableService;

  /**
   * Result of an upsert batch operation. inserted: number of rows newly inserted (xmax = 0 in
   * PostgreSQL means the row was just inserted). updated: number of rows that already existed and
   * were updated.
   */
  public record UpsertResult(int inserted, int updated) {}

  public DataTableRowService(DSLContext dsl, DataTableService dataTableService) {
    this.dsl = dsl;
    this.dataTableService = dataTableService;
  }

  // --- GEOMETRY helpers ---

  private static boolean isGeometry(String colName, Map<String, String> columnTypes) {
    return columnTypes != null && "GEOMETRY".equalsIgnoreCase(columnTypes.get(colName));
  }

  /** Returns ST_AsGeoJSON("col") AS "col" for GEOMETRY, otherwise "col". */
  private static String selectExpr(String colName, Map<String, String> columnTypes) {
    if (isGeometry(colName, columnTypes)) {
      return "ST_AsGeoJSON(\"" + colName + "\") AS \"" + colName + "\"";
    }
    return "\"" + colName + "\"";
  }

  /** Returns ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) for GEOMETRY, otherwise ?. */
  private static String insertPlaceholder(String colName, Map<String, String> columnTypes) {
    if (isGeometry(colName, columnTypes)) {
      return "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)";
    }
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
        if ("id".equals(fieldName)) {
          row.put("_id", record.get(i));
        } else {
          row.put(fieldName, record.get(i));
        }
      }
      rows.add(row);
    }

    return rows;
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
      sql.append("\"").append(columns.get(i)).append("\" = ")
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
}
