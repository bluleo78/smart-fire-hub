package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DataTableService {

    private final DSLContext dsl;
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

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

    public void dropTable(String tableName) {
        validateName(tableName);
        String sql = "DROP TABLE IF EXISTS data.\"" + tableName + "\"";
        dsl.execute(sql);
    }

    public List<Map<String, Object>> queryData(String tableName, List<String> columns, String search, int page, int size) {
        validateName(tableName);
        for (String col : columns) {
            validateName(col);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
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

        sql.append(" ORDER BY id");
        sql.append(" LIMIT ").append(size);
        sql.append(" OFFSET ").append(page * size);

        var result = params.length > 0 ? dsl.fetch(sql.toString(), params) : dsl.fetch(sql.toString());
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
}
