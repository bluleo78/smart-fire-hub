package com.smartfirehub.dataset.service;

import com.smartfirehub.dataset.dto.ColumnStatsResponse;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.exception.InvalidTableNameException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

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
        String indexSql =
            "CREATE INDEX \""
                + indexName
                + "\" ON data.\""
                + tableName
                + "\" (\""
                + col.columnName()
                + "\")";
        dsl.execute(indexSql);
      }
    }

    // Create unique index for primary key columns
    List<DatasetColumnRequest> pkColumns =
        columns.stream().filter(DatasetColumnRequest::isPrimaryKey).toList();
    if (!pkColumns.isEmpty()) {
      StringBuilder uniqueSql = new StringBuilder();
      uniqueSql
          .append("CREATE UNIQUE INDEX \"ux_")
          .append(tableName)
          .append("_pk\" ON data.\"")
          .append(tableName)
          .append("\" (");
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
      String sql =
          "CREATE INDEX IF NOT EXISTS \""
              + indexName
              + "\" ON data.\""
              + tableName
              + "\" (\""
              + columnName
              + "\")";
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
      sql.append("CREATE UNIQUE INDEX \"ux_")
          .append(tableName)
          .append("_pk\" ON data.\"")
          .append(tableName)
          .append("\" (");
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
    sql.append("CREATE UNIQUE INDEX CONCURRENTLY \"ux_")
        .append(tableName)
        .append("_pk\" ON data.\"")
        .append(tableName)
        .append("\" (");
    for (int i = 0; i < pkColumnNames.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append("\"").append(pkColumnNames.get(i)).append("\"");
    }
    sql.append(")");
    dsl.execute(sql.toString());
  }

  public void dropTable(String tableName) {
    validateName(tableName);
    String sql = "DROP TABLE IF EXISTS data.\"" + tableName + "\"";
    dsl.execute(sql);
  }

  /**
   * Creates a temporary staging table {@code data."{tableName}_tmp"} with the same structure as the
   * original table (using PostgreSQL's LIKE ... INCLUDING ALL). Used by the REPLACE load strategy
   * to safely stage new data before swapping.
   */
  public void createTempTable(String tableName) {
    validateName(tableName);
    String tmpName = tableName + "_tmp";
    // Drop any leftover temp table from a previous failed run
    dsl.execute("DROP TABLE IF EXISTS data.\"" + tmpName + "\"");
    dsl.execute(
        "CREATE TABLE data.\"" + tmpName + "\" (LIKE data.\"" + tableName + "\" INCLUDING ALL)");
    // LIKE INCLUDING ALL shares the original SERIAL sequence, creating a dependency
    // that blocks DROP TABLE on the original. Give the temp table its own sequence.
    String tmpSeq = tmpName + "_id_seq";
    dsl.execute("DROP SEQUENCE IF EXISTS data.\"" + tmpSeq + "\"");
    dsl.execute("CREATE SEQUENCE data.\"" + tmpSeq + "\" OWNED BY data.\"" + tmpName + "\".id");
    dsl.execute(
        "ALTER TABLE data.\""
            + tmpName
            + "\" ALTER COLUMN id SET DEFAULT nextval('data.\""
            + tmpSeq
            + "\"')");
  }

  /**
   * Atomically replaces {@code data."{tableName}"} with {@code data."{tableName}_tmp"} by dropping
   * the original and renaming the tmp table inside a single transaction. Called only after all data
   * has been successfully inserted into the tmp table.
   */
  public void swapTable(String tableName) {
    validateName(tableName);
    String tmpName = tableName + "_tmp";
    dsl.transaction(
        cfg -> {
          var txDsl = org.jooq.impl.DSL.using(cfg);
          txDsl.execute("DROP TABLE data.\"" + tableName + "\"");
          txDsl.execute("ALTER TABLE data.\"" + tmpName + "\" RENAME TO \"" + tableName + "\"");
          // Rename the temp sequence to match the canonical naming convention
          String tmpSeq = tmpName + "_id_seq";
          String seq = tableName + "_id_seq";
          txDsl.execute(
              "ALTER SEQUENCE IF EXISTS data.\"" + tmpSeq + "\" RENAME TO \"" + seq + "\"");
        });
  }

  /**
   * Drops the temporary staging table {@code data."{tableName}_tmp"} if it exists. Called on
   * failure to preserve the original table's data.
   */
  public void dropTempTable(String tableName) {
    validateName(tableName);
    String tmpName = tableName + "_tmp";
    dsl.execute("DROP TABLE IF EXISTS data.\"" + tmpName + "\"");
  }

  public void renameColumn(String tableName, String oldName, String newName) {
    validateName(tableName);
    validateName(oldName);
    validateName(newName);
    String sql =
        "ALTER TABLE data.\""
            + tableName
            + "\" RENAME COLUMN \""
            + oldName
            + "\" TO \""
            + newName
            + "\"";
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

  public void alterColumnType(
      String tableName, String columnName, String dataType, Integer maxLength) {
    validateName(tableName);
    validateName(columnName);
    String newType = mapDataType(dataType, maxLength);
    String sql =
        "ALTER TABLE data.\""
            + tableName
            + "\" ALTER COLUMN \""
            + columnName
            + "\" TYPE "
            + newType
            + " USING \""
            + columnName
            + "\"::"
            + newType;
    dsl.execute(sql);
  }

  public void setColumnNullable(String tableName, String columnName, boolean nullable) {
    validateName(tableName);
    validateName(columnName);
    String sql =
        "ALTER TABLE data.\""
            + tableName
            + "\" ALTER COLUMN \""
            + columnName
            + "\" "
            + (nullable ? "DROP NOT NULL" : "SET NOT NULL");
    dsl.execute(sql);
  }

  public void dropColumn(String tableName, String columnName) {
    validateName(tableName);
    validateName(columnName);
    String sql = "ALTER TABLE data.\"" + tableName + "\" DROP COLUMN \"" + columnName + "\"";
    dsl.execute(sql);
  }

  public void cloneTable(
      String sourceTable,
      String targetTable,
      List<String> userColumns,
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
    String createSql =
        "CREATE TABLE data.\""
            + targetTable
            + "\" AS SELECT "
            + colList
            + " FROM data.\""
            + sourceTable
            + "\"";
    dsl.execute(createSql);

    // Add system columns
    dsl.execute("ALTER TABLE data.\"" + targetTable + "\" ADD COLUMN id BIGSERIAL PRIMARY KEY");
    dsl.execute("ALTER TABLE data.\"" + targetTable + "\" ADD COLUMN import_id BIGINT");

    // Re-apply NOT NULL constraints (CTAS does not preserve them)
    for (DatasetColumnResponse col : columnDefs) {
      if (!col.isNullable()) {
        dsl.execute(
            "ALTER TABLE data.\""
                + targetTable
                + "\" ALTER COLUMN \""
                + col.columnName()
                + "\" SET NOT NULL");
      }
    }
  }

  private static final Set<String> NUMERIC_TYPES = Set.of("INTEGER", "DECIMAL");

  public List<ColumnStatsResponse> getColumnStats(
      String tableName, List<DatasetColumnResponse> columns) {
    validateName(tableName);

    // Set statement_timeout to 30 seconds for profiling queries
    dsl.execute("SET LOCAL statement_timeout = '30s'");

    // Check row count to decide whether to sample
    long rowCount = countRowsInternal(tableName);
    boolean sampled = rowCount > 100_000;
    String fromClause =
        sampled
            ? "data.\"" + tableName + "\" TABLESAMPLE BERNOULLI(10)"
            : "data.\"" + tableName + "\"";

    List<ColumnStatsResponse> result = new ArrayList<>();

    for (DatasetColumnResponse col : columns) {
      validateName(col.columnName());
      String colName = col.columnName();
      String dataType = col.dataType();

      // Build aggregate stats query
      StringBuilder statsSql = new StringBuilder();
      statsSql
          .append("SELECT COUNT(*) AS total,")
          .append(" COUNT(*) FILTER (WHERE \"")
          .append(colName)
          .append("\" IS NULL) AS null_count,")
          .append(" COUNT(DISTINCT \"")
          .append(colName)
          .append("\") AS distinct_count,")
          .append(" MIN(\"")
          .append(colName)
          .append("\"::text) AS min_val,")
          .append(" MAX(\"")
          .append(colName)
          .append("\"::text) AS max_val");

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
      String topSql =
          "SELECT \""
              + colName
              + "\"::text AS val, COUNT(*) AS cnt"
              + " FROM "
              + fromClause
              + " WHERE \""
              + colName
              + "\" IS NOT NULL"
              + " GROUP BY \""
              + colName
              + "\""
              + " ORDER BY cnt DESC LIMIT 5";

      var topRecords = dsl.fetch(topSql);
      List<ColumnStatsResponse.ValueCount> topValues = new ArrayList<>();
      for (var rec : topRecords) {
        String val = rec.get("val", String.class);
        long cnt = rec.get("cnt", Long.class);
        topValues.add(new ColumnStatsResponse.ValueCount(val, cnt));
      }

      result.add(
          new ColumnStatsResponse(
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
              sampled));
    }

    return result;
  }

  /**
   * Internal row count used by getColumnStats to avoid circular dependency with
   * DataTableRowService.
   */
  private long countRowsInternal(String tableName) {
    String sql = "SELECT COUNT(*) FROM data.\"" + tableName + "\"";
    Long count = dsl.fetchOne(sql).get(0, Long.class);
    return count != null ? count : 0L;
  }
}
