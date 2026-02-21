package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import com.smartfirehub.dataset.exception.RowNotFoundException;
import com.smartfirehub.dataset.exception.SqlQueryException;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

@Service
public class DataTableService {

    private final DSLContext dsl;
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * Result of an upsert batch operation.
     * inserted: number of rows newly inserted (xmax = 0 in PostgreSQL means the row was just inserted).
     * updated: number of rows that already existed and were updated.
     */
    public record UpsertResult(int inserted, int updated) {}

    public DataTableService(DSLContext dsl) {
        this.dsl = dsl;
    }

    private String mapDataType(String dataType, Integer maxLength) {
        return switch (dataType) {
            case "TEXT" -> "TEXT";
            case "VARCHAR" -> {
                int len = maxLength != null ? maxLength : 255;
                if (len < 1 || len > 10000) {
                    throw new IllegalArgumentException("VARCHAR length must be between 1 and 10000");
                }
                yield "VARCHAR(" + len + ")";
            }
            case "INTEGER" -> "BIGINT";
            case "DECIMAL" -> "NUMERIC(18,6)";
            case "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIMESTAMP" -> "TIMESTAMP";
            default -> throw new IllegalArgumentException("Unknown data type: " + dataType);
        };
    }

    public void validateName(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new InvalidTableNameException("Invalid name: " + name + ". Must match [a-z][a-z0-9_]*");
        }
    }

    public void createTable(String tableName, List<DatasetColumnRequest> columns) {
        validateName(tableName);

        // Drop orphaned table if it exists (metadata already verified no dataset references it)
        dsl.execute("DROP TABLE IF EXISTS data.\"" + tableName + "\"");

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE data.\"").append(tableName).append("\" (");
        sql.append("id BIGSERIAL PRIMARY KEY, ");
        sql.append("import_id BIGINT, ");

        for (DatasetColumnRequest col : columns) {
            validateName(col.columnName());
            sql.append("\"").append(col.columnName()).append("\" ");
            sql.append(mapDataType(col.dataType(), col.maxLength()));
            if (!col.isNullable()) {
                sql.append(" NOT NULL");
            }
            sql.append(", ");
        }

        sql.append("created_at TIMESTAMP DEFAULT NOW()");
        sql.append(")");

        dsl.execute(sql.toString());

        // Create indexes for indexed columns
        for (DatasetColumnRequest col : columns) {
            if (col.isIndexed()) {
                String indexName = "idx_" + tableName + "_" + col.columnName();
                String indexSql = "CREATE INDEX \"" + indexName + "\" ON data.\"" + tableName + "\" (\"" + col.columnName() + "\")";
                dsl.execute(indexSql);
            }
        }

        // Create unique index for primary key columns
        List<DatasetColumnRequest> pkColumns = columns.stream()
                .filter(DatasetColumnRequest::isPrimaryKey)
                .toList();
        if (!pkColumns.isEmpty()) {
            StringBuilder uniqueSql = new StringBuilder();
            uniqueSql.append("CREATE UNIQUE INDEX \"ux_").append(tableName).append("_pk\" ON data.\"")
                    .append(tableName).append("\" (");
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) uniqueSql.append(", ");
                uniqueSql.append("\"").append(pkColumns.get(i).columnName()).append("\"");
            }
            uniqueSql.append(")");
            dsl.execute(uniqueSql.toString());
        }
    }

    public void addColumn(String tableName, DatasetColumnRequest column) {
        validateName(tableName);
        validateName(column.columnName());

        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE data.\"").append(tableName).append("\" ");
        sql.append("ADD COLUMN \"").append(column.columnName()).append("\" ");
        sql.append(mapDataType(column.dataType(), column.maxLength()));
        if (!column.isNullable()) {
            sql.append(" NOT NULL");
        }

        dsl.execute(sql.toString());

        if (column.isIndexed()) {
            setColumnIndex(tableName, column.columnName(), true);
        }
    }

    public void setColumnIndex(String tableName, String columnName, boolean indexed) {
        validateName(tableName);
        validateName(columnName);

        String indexName = "idx_" + tableName + "_" + columnName;

        if (indexed) {
            String sql = "CREATE INDEX IF NOT EXISTS \"" + indexName + "\" ON data.\"" + tableName + "\" (\"" + columnName + "\")";
            dsl.execute(sql);
        } else {
            String sql = "DROP INDEX IF EXISTS data.\"" + indexName + "\"";
            dsl.execute(sql);
        }
    }

    public void recreatePrimaryKeyIndex(String tableName, List<String> pkColumnNames) {
        validateName(tableName);
        for (String col : pkColumnNames) {
            validateName(col);
        }
        // Drop existing PK index if exists
        String dropSql = "DROP INDEX IF EXISTS data.\"ux_" + tableName + "_pk\"";
        dsl.execute(dropSql);

        // Create new index if there are PK columns
        if (!pkColumnNames.isEmpty()) {
            StringBuilder sql = new StringBuilder();
            sql.append("CREATE UNIQUE INDEX \"ux_").append(tableName).append("_pk\" ON data.\"")
                    .append(tableName).append("\" (");
            for (int i = 0; i < pkColumnNames.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("\"").append(pkColumnNames.get(i)).append("\"");
            }
            sql.append(")");
            dsl.execute(sql.toString());
        }
    }

    public void createPrimaryKeyIndexConcurrently(String tableName, List<String> pkColumnNames) {
        validateName(tableName);
        for (String col : pkColumnNames) {
            validateName(col);
        }
        if (pkColumnNames.isEmpty()) return;

        // Drop existing PK index if exists
        String dropSql = "DROP INDEX IF EXISTS data.\"ux_" + tableName + "_pk\"";
        dsl.execute(dropSql);

        // CREATE INDEX CONCURRENTLY cannot run inside a transaction
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE UNIQUE INDEX CONCURRENTLY \"ux_").append(tableName).append("_pk\" ON data.\"")
                .append(tableName).append("\" (");
        for (int i = 0; i < pkColumnNames.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("\"").append(pkColumnNames.get(i)).append("\"");
        }
        sql.append(")");
        dsl.execute(sql.toString());
    }

    public boolean checkDataUniqueness(String tableName, List<String> pkColumnNames) {
        validateName(tableName);
        for (String col : pkColumnNames) {
            validateName(col);
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

    public List<Map<String, Object>> findDuplicateRows(String tableName, List<String> pkColumnNames, int limit) {
        validateName(tableName);
        for (String col : pkColumnNames) {
            validateName(col);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        for (int i = 0; i < pkColumnNames.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("\"").append(pkColumnNames.get(i)).append("\"");
        }
        sql.append(", COUNT(*) AS duplicate_count FROM data.\"").append(tableName).append("\" GROUP BY ");
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

    public void dropTable(String tableName) {
        validateName(tableName);
        String sql = "DROP TABLE IF EXISTS data.\"" + tableName + "\"";
        dsl.execute(sql);
    }

    public List<Map<String, Object>> queryData(String tableName, List<String> columns, String search, int page, int size) {
        return queryData(tableName, columns, search, page, size, null, "ASC");
    }

    public List<Map<String, Object>> queryData(String tableName, List<String> columns, String search, int page, int size, String sortBy, String sortDir) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
        }
        if (sortBy != null) {
            validateName(sortBy);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, ");
        if (columns.isEmpty()) {
            sql.append("*");
        } else {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("\"").append(columns.get(i)).append("\"");
            }
        }
        sql.append(" FROM data.\"").append(tableName).append("\"");

        Object[] params = buildSearchWhereClause(sql, columns, search);

        if (sortBy != null) {
            sql.append(" ORDER BY \"").append(sortBy).append("\" ").append(sortDir).append(" NULLS LAST, id ASC");
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
        validateName(tableName);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM data.\"").append(tableName).append("\"");

        Object[] params = buildSearchWhereClause(sql, columns, search);

        Long count = params.length > 0
                ? dsl.fetchOne(sql.toString(), params).get(0, Long.class)
                : dsl.fetchOne(sql.toString()).get(0, Long.class);
        return count != null ? count : 0L;
    }

    private Object[] buildSearchWhereClause(StringBuilder sql, List<String> columns, String search) {
        if (search == null || search.isBlank() || columns.isEmpty()) {
            return new Object[0];
        }

        sql.append(" WHERE (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("CAST(\"").append(columns.get(i)).append("\" AS TEXT) ILIKE ? ESCAPE '\\'");
        }
        sql.append(")");

        String pattern = "%" + escapeIlike(search) + "%";
        Object[] params = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            params[i] = pattern;
        }
        return params;
    }

    private String escapeIlike(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public void insertBatch(String tableName, List<String> columns, List<Map<String, Object>> rows) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
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

        String placeholders = "(" + "?, ".repeat(Math.max(0, columns.size() - 1)) + "?)";

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

    public void insertBatchWithProgress(String tableName, List<String> columns, List<Map<String, Object>> rows,
                                        BiConsumer<Integer, Integer> progressCallback) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
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

        String placeholders = "(" + "?, ".repeat(Math.max(0, columns.size() - 1)) + "?)";

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

    public void insertBatch(String tableName, List<String> columns, List<Map<String, Object>> rows, Long importId) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
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

        // import_id placeholder + column placeholders
        String placeholders = "(?, " + "?, ".repeat(Math.max(0, columns.size() - 1)) + "?)";

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

    public void insertBatchWithProgress(String tableName, List<String> columns, List<Map<String, Object>> rows,
                                        Long importId, BiConsumer<Integer, Integer> progressCallback) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
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

        // import_id placeholder + column placeholders
        String placeholders = "(?, " + "?, ".repeat(Math.max(0, columns.size() - 1)) + "?)";

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
     * The conflict target is the unique index on pkColumns (ux_{tableName}_pk).
     * created_at is intentionally excluded from DO UPDATE SET to preserve the
     * original creation timestamp on subsequent updates.
     *
     * PostgreSQL xmax trick: when a row is freshly inserted, its xmax system
     * column is 0. When it is updated (DELETE + re-insert under the hood),
     * xmax holds the transaction ID of the deleting transaction (non-zero).
     * By returning (xmax = 0) AS was_insert we can distinguish inserts from
     * updates without a separate SELECT.
     */
    public UpsertResult upsertBatch(String tableName, List<String> columns, List<String> pkColumns,
                                    List<Map<String, Object>> rows, Long importId) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
        }
        for (String pkCol : pkColumns) {
            validateName(pkCol);
        }

        if (rows.isEmpty()) {
            return new UpsertResult(0, 0);
        }

        if (pkColumns.isEmpty()) {
            throw new IllegalStateException("UPSERT mode requires at least one primary key column");
        }

        // Columns that go in DO UPDATE SET: import_id + non-PK data columns (exclude created_at)
        Set<String> pkSet = new HashSet<>(pkColumns);
        List<String> updateCols = columns.stream()
                .filter(c -> !pkSet.contains(c))
                .toList();

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

        // Placeholders: import_id + columns
        String placeholders = "(?, " + "?, ".repeat(Math.max(0, columns.size() - 1)) + "?)";

        String sqlTemplate = "INSERT INTO data.\"" + tableName + "\" (" + insertCols + ") VALUES "
                + "%ROWS%"
                + " ON CONFLICT " + conflictTarget
                + " DO UPDATE SET " + updateSet
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

    public UpsertResult upsertBatchWithProgress(String tableName, List<String> columns, List<String> pkColumns,
                                                List<Map<String, Object>> rows, Long importId,
                                                BiConsumer<Integer, Integer> progressCallback) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
        }
        for (String pkCol : pkColumns) {
            validateName(pkCol);
        }

        if (rows.isEmpty()) {
            return new UpsertResult(0, 0);
        }

        if (pkColumns.isEmpty()) {
            throw new IllegalStateException("UPSERT mode requires at least one primary key column");
        }

        Set<String> pkSet = new HashSet<>(pkColumns);
        List<String> updateCols = columns.stream()
                .filter(c -> !pkSet.contains(c))
                .toList();

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

        String placeholders = "(?, " + "?, ".repeat(Math.max(0, columns.size() - 1)) + "?)";

        String sqlTemplate = "INSERT INTO data.\"" + tableName + "\" (" + insertCols + ") VALUES "
                + "%ROWS%"
                + " ON CONFLICT " + conflictTarget
                + " DO UPDATE SET " + updateSet
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
        validateName(tableName);
        if (rowIds == null || rowIds.isEmpty()) return 0;
        String sql = "DELETE FROM data.\"" + tableName + "\" WHERE id = ANY(?)";
        Long[] idArray = rowIds.toArray(new Long[0]);
        return dsl.execute(sql, (Object) idArray);
    }

    public void truncateTable(String tableName) {
        validateName(tableName);
        String sql = "TRUNCATE TABLE data.\"" + tableName + "\"";
        dsl.execute(sql);
    }

    public void renameColumn(String tableName, String oldName, String newName) {
        validateName(tableName);
        validateName(oldName);
        validateName(newName);
        String sql = "ALTER TABLE data.\"" + tableName + "\" RENAME COLUMN \"" + oldName + "\" TO \"" + newName + "\"";
        dsl.execute(sql);
    }

    public void renameIndex(String tableName, String oldColName, String newColName) {
        validateName(tableName);
        validateName(oldColName);
        validateName(newColName);
        String oldIndex = "idx_" + tableName + "_" + oldColName;
        String newIndex = "idx_" + tableName + "_" + newColName;
        String sql = "ALTER INDEX IF EXISTS data.\"" + oldIndex + "\" RENAME TO \"" + newIndex + "\"";
        dsl.execute(sql);
    }

    public void alterColumnType(String tableName, String columnName, String dataType, Integer maxLength) {
        validateName(tableName);
        validateName(columnName);
        String newType = mapDataType(dataType, maxLength);
        String sql = "ALTER TABLE data.\"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" TYPE " + newType
                + " USING \"" + columnName + "\"::" + newType;
        dsl.execute(sql);
    }

    public void setColumnNullable(String tableName, String columnName, boolean nullable) {
        validateName(tableName);
        validateName(columnName);
        String sql = "ALTER TABLE data.\"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" " + (nullable ? "DROP NOT NULL" : "SET NOT NULL");
        dsl.execute(sql);
    }

    public void dropColumn(String tableName, String columnName) {
        validateName(tableName);
        validateName(columnName);
        String sql = "ALTER TABLE data.\"" + tableName + "\" DROP COLUMN \"" + columnName + "\"";
        dsl.execute(sql);
    }

    // ---------------------------------------------------------------------------
    // Phase 1: SQL Query execution
    // ---------------------------------------------------------------------------

    private static final Set<String> ALLOWED_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "WITH"
    );

    /**
     * Execute user-supplied SQL against the data schema.
     * Security: rejects DDL, multi-statement input, and restricts search_path to data schema.
     * Must be called within a @Transactional context for SET LOCAL to be effective.
     */
    public SqlQueryResponse executeQuery(String sql, int maxRows) {
        // Strip SQL comments (block and line) to prevent DDL bypass via comment injection
        // (?s) enables DOTALL mode so .* matches across newlines in block comments
        String stripped = sql.strip()
                .replaceAll("(?s)/\\*.*?\\*/", " ")    // block comments (multiline-safe)
                .replaceAll("--[^\n]*", " ")           // line comments
                .strip();

        // Reject multi-statement SQL (semicolons not at the very end)
        String withoutTrailingSemicolon = stripped.replaceAll(";\\s*$", "");
        if (withoutTrailingSemicolon.contains(";")) {
            throw new SqlQueryException("Multiple statements are not allowed.");
        }

        String firstWord = stripped.split("\\s+")[0].toUpperCase();

        if (!ALLOWED_KEYWORDS.contains(firstWord)) {
            throw new SqlQueryException("Only SELECT, INSERT, UPDATE, DELETE, and WITH statements are allowed.");
        }

        // WITH (CTE) can precede SELECT, INSERT, UPDATE, DELETE — detect the main verb
        // Use word boundary (\b) to avoid false positives on column/table names
        String queryType;
        if ("WITH".equals(firstWord)) {
            String upper = withoutTrailingSemicolon.toUpperCase();
            if (upper.matches("(?s).*\\bINSERT\\b.*")) queryType = "INSERT";
            else if (upper.matches("(?s).*\\bUPDATE\\b.*")) queryType = "UPDATE";
            else if (upper.matches("(?s).*\\bDELETE\\b.*")) queryType = "DELETE";
            else queryType = "SELECT";
        } else {
            queryType = firstWord;
        }

        long startTime = System.currentTimeMillis();

        // Restrict search_path to data schema only — prevents access to public schema tables
        dsl.execute("SET LOCAL search_path = 'data'");
        dsl.execute("SET LOCAL statement_timeout = '30s'");

        // Use SAVEPOINT so that SQL errors don't abort the outer transaction.
        // PostgreSQL marks the entire transaction as aborted on any error,
        // preventing subsequent commands (like saving query history).
        // Rolling back to a savepoint clears the error state.
        dsl.execute("SAVEPOINT user_query");

        try {
            SqlQueryResponse response;
            if ("SELECT".equals(queryType)) {
                // Apply LIMIT for SELECT queries (check for actual LIMIT clause, not substring match)
                String limitedSql = withoutTrailingSemicolon;
                if (!limitedSql.toUpperCase().matches("(?s).*\\bLIMIT\\s+\\d+.*")) {
                    limitedSql = limitedSql + " LIMIT " + maxRows;
                }

                var result = dsl.fetch(limitedSql);
                long executionTimeMs = System.currentTimeMillis() - startTime;

                // Filter out system columns (id, import_id, created_at)
                Set<String> systemColumns = Set.of("id", "import_id", "created_at");

                List<String> columns = new ArrayList<>();
                List<Integer> visibleIndices = new ArrayList<>();
                for (int i = 0; i < result.fields().length; i++) {
                    String colName = result.fields()[i].getName();
                    if (!systemColumns.contains(colName)) {
                        columns.add(colName);
                        visibleIndices.add(i);
                    }
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                for (var record : result) {
                    Map<String, Object> row = new HashMap<>();
                    for (int idx : visibleIndices) {
                        row.put(record.field(idx).getName(), record.get(idx));
                    }
                    rows.add(row);
                }

                response = new SqlQueryResponse(queryType, columns, rows, rows.size(), executionTimeMs, null);
            } else {
                // DML: INSERT, UPDATE, DELETE
                int affectedRows = dsl.execute(withoutTrailingSemicolon);
                long executionTimeMs = System.currentTimeMillis() - startTime;
                response = new SqlQueryResponse(queryType, List.of(), List.of(), affectedRows, executionTimeMs, null);
            }
            dsl.execute("RELEASE SAVEPOINT user_query");
            return response;
        } catch (Exception e) {
            long executionTimeMs = System.currentTimeMillis() - startTime;
            // Rollback to savepoint to clear the aborted transaction state
            dsl.execute("ROLLBACK TO SAVEPOINT user_query");
            String errorMessage = e.getMessage();
            return new SqlQueryResponse(queryType, List.of(), List.of(), 0, executionTimeMs, errorMessage);
        } finally {
            // Restore search_path so subsequent operations in the same transaction
            // (e.g. QueryHistoryRepository.save) can access public schema tables
            try {
                dsl.execute("SET LOCAL search_path TO public, data");
            } catch (Exception ignored) {
                // May fail if connection is broken; non-critical since transaction will end
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Phase 2: Manual Row Entry
    // ---------------------------------------------------------------------------

    public Long insertRow(String tableName, List<String> columns, Map<String, Object> row) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
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
            sql.append("?");
        }
        sql.append(") RETURNING id");

        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            values[i] = row.get(columns.get(i));
        }

        var record = dsl.fetchOne(sql.toString(), values);
        return record != null ? record.get(0, Long.class) : null;
    }

    public void updateRow(String tableName, long rowId, List<String> columns, Map<String, Object> row) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE data.\"").append(tableName).append("\" SET ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("\"").append(columns.get(i)).append("\" = ?");
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
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("\"").append(columns.get(i)).append("\"");
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
    // Phase 3: Clone Table
    // ---------------------------------------------------------------------------

    public void cloneTable(String sourceTable, String targetTable, List<String> userColumns,
                           List<DatasetColumnResponse> columnDefs) {
        validateName(sourceTable);
        validateName(targetTable);
        for (String col : userColumns) {
            validateName(col);
        }

        // Build column list for SELECT
        StringBuilder colList = new StringBuilder();
        for (int i = 0; i < userColumns.size(); i++) {
            if (i > 0) colList.append(", ");
            colList.append("\"").append(userColumns.get(i)).append("\"");
        }
        colList.append(", created_at");

        // CREATE TABLE AS SELECT (copies data + column types, but not constraints)
        String createSql = "CREATE TABLE data.\"" + targetTable + "\" AS SELECT " + colList
                + " FROM data.\"" + sourceTable + "\"";
        dsl.execute(createSql);

        // Add system columns
        dsl.execute("ALTER TABLE data.\"" + targetTable + "\" ADD COLUMN id BIGSERIAL PRIMARY KEY");
        dsl.execute("ALTER TABLE data.\"" + targetTable + "\" ADD COLUMN import_id BIGINT");

        // Re-apply NOT NULL constraints (CTAS does not preserve them)
        for (DatasetColumnResponse col : columnDefs) {
            if (!col.isNullable()) {
                dsl.execute("ALTER TABLE data.\"" + targetTable
                        + "\" ALTER COLUMN \"" + col.columnName() + "\" SET NOT NULL");
            }
        }
    }

    private static final Set<String> NUMERIC_TYPES = Set.of("INTEGER", "DECIMAL");

    public List<ColumnStatsResponse> getColumnStats(String tableName, List<DatasetColumnResponse> columns) {
        validateName(tableName);

        // Set statement_timeout to 30 seconds for profiling queries
        dsl.execute("SET LOCAL statement_timeout = '30s'");

        // Check row count to decide whether to sample
        long rowCount = countRows(tableName);
        boolean sampled = rowCount > 100_000;
        String fromClause = sampled
                ? "data.\"" + tableName + "\" TABLESAMPLE BERNOULLI(10)"
                : "data.\"" + tableName + "\"";

        List<ColumnStatsResponse> result = new ArrayList<>();

        for (DatasetColumnResponse col : columns) {
            validateName(col.columnName());
            String colName = col.columnName();
            String dataType = col.dataType();

            // Build aggregate stats query
            StringBuilder statsSql = new StringBuilder();
            statsSql.append("SELECT COUNT(*) AS total,")
                    .append(" COUNT(*) FILTER (WHERE \"").append(colName).append("\" IS NULL) AS null_count,")
                    .append(" COUNT(DISTINCT \"").append(colName).append("\") AS distinct_count,")
                    .append(" MIN(\"").append(colName).append("\"::text) AS min_val,")
                    .append(" MAX(\"").append(colName).append("\"::text) AS max_val");

            if (NUMERIC_TYPES.contains(dataType)) {
                statsSql.append(", AVG(\"").append(colName).append("\"::numeric) AS avg_val");
            }

            statsSql.append(" FROM ").append(fromClause);

            var statsRecord = dsl.fetchOne(statsSql.toString());

            long total = statsRecord.get("total", Long.class);
            long nullCount = statsRecord.get("null_count", Long.class);
            double nullPercent = total > 0 ? (double) nullCount / total * 100.0 : 0.0;
            long distinctCount = statsRecord.get("distinct_count", Long.class);
            String minVal = statsRecord.get("min_val", String.class);
            String maxVal = statsRecord.get("max_val", String.class);
            Double avgVal = null;
            if (NUMERIC_TYPES.contains(dataType)) {
                Object rawAvg = statsRecord.get("avg_val");
                if (rawAvg != null) {
                    avgVal = ((Number) rawAvg).doubleValue();
                }
            }

            // Get top 5 values
            String topSql = "SELECT \"" + colName + "\"::text AS val, COUNT(*) AS cnt"
                    + " FROM " + fromClause
                    + " WHERE \"" + colName + "\" IS NOT NULL"
                    + " GROUP BY \"" + colName + "\""
                    + " ORDER BY cnt DESC LIMIT 5";

            var topRecords = dsl.fetch(topSql);
            List<ColumnStatsResponse.ValueCount> topValues = new ArrayList<>();
            for (var rec : topRecords) {
                String val = rec.get("val", String.class);
                long cnt = rec.get("cnt", Long.class);
                topValues.add(new ColumnStatsResponse.ValueCount(val, cnt));
            }

            result.add(new ColumnStatsResponse(
                    colName,
                    dataType,
                    total,
                    nullCount,
                    nullPercent,
                    distinctCount,
                    minVal,
                    maxVal,
                    avgVal,
                    topValues,
                    sampled
            ));
        }

        return result;
    }
}
