package com.smartfirehub.analytics.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.util.SqlValidationUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsQueryExecutionService {

  private final DSLContext dsl;

  public AnalyticsQueryExecutionService(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Execute SQL against the data schema.
   *
   * @param sql raw SQL from user
   * @param maxRows maximum rows to return (1–10000)
   * @param readOnly if true, only SELECT/WITH is allowed (used by MCP tools)
   */
  @Transactional
  public AnalyticsQueryResponse execute(String sql, int maxRows, boolean readOnly) {
    String stripped;
    String queryType;
    try {
      stripped = SqlValidationUtils.stripAndValidate(sql);
      queryType = SqlValidationUtils.detectQueryType(stripped);
    } catch (SqlQueryException e) {
      return errorResponse(e.getMessage());
    }

    if (readOnly && !"SELECT".equals(queryType)) {
      return errorResponse("AI 도구에서는 SELECT 쿼리만 실행할 수 있습니다. 데이터 수정은 웹 UI를 사용하세요.");
    }

    String cleanSql = SqlValidationUtils.removeTrailingSemicolon(stripped);

    long startTime = System.currentTimeMillis();

    dsl.execute("SET LOCAL search_path = 'data', 'public'");
    dsl.execute("SET LOCAL statement_timeout = '30s'");
    dsl.execute("SAVEPOINT analytics_query");

    try {
      AnalyticsQueryResponse response;

      if ("SELECT".equals(queryType)) {
        // Apply LIMIT if not already present
        String limitedSql = cleanSql;
        if (!limitedSql.toUpperCase().matches("(?s).*\\bLIMIT\\s+\\d+.*")) {
          limitedSql = limitedSql + " LIMIT " + maxRows;
        }

        var result = dsl.fetch(limitedSql);

        // Detect GEOMETRY/GEOGRAPHY columns and re-execute with ST_AsGeoJSON wrapping
        Set<String> geomColumns = detectGeometryColumns(result);
        if (!geomColumns.isEmpty()) {
          StringBuilder sb = new StringBuilder("WITH _src AS (");
          sb.append(cleanSql);
          sb.append(") SELECT ");
          boolean first = true;
          for (var field : result.fields()) {
            if (!first) sb.append(", ");
            first = false;
            String escaped = field.getName().replace("\"", "\"\"");
            if (geomColumns.contains(field.getName())) {
              // Qualify with public schema — search_path is set to 'data' only
              sb.append("public.ST_AsGeoJSON(\"")
                  .append(escaped)
                  .append("\") AS \"")
                  .append(escaped)
                  .append("\"");
            } else {
              sb.append("\"").append(escaped).append("\"");
            }
          }
          sb.append(" FROM _src");
          String wrappedSql = sb.toString();
          if (!cleanSql.toUpperCase().matches("(?s).*\\bLIMIT\\s+\\d+.*")) {
            wrappedSql = wrappedSql + " LIMIT " + maxRows;
          }
          result = dsl.fetch(wrappedSql);
        }

        long executionTimeMs = System.currentTimeMillis() - startTime;

        List<String> columns = new ArrayList<>();
        for (var field : result.fields()) {
          columns.add(field.getName());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var record : result) {
          Map<String, Object> row = new HashMap<>();
          for (var field : result.fields()) {
            row.put(field.getName(), record.get(field));
          }
          rows.add(row);
        }

        boolean truncated = rows.size() >= maxRows;
        response =
            new AnalyticsQueryResponse(
                queryType,
                columns,
                rows,
                rows.size(),
                executionTimeMs,
                rows.size(),
                truncated,
                null);
      } else {
        int affectedRows = dsl.execute(cleanSql);
        long executionTimeMs = System.currentTimeMillis() - startTime;
        response =
            new AnalyticsQueryResponse(
                queryType, List.of(), List.of(), affectedRows, executionTimeMs, 0, false, null);
      }

      dsl.execute("RELEASE SAVEPOINT analytics_query");
      return response;

    } catch (Exception e) {
      long executionTimeMs = System.currentTimeMillis() - startTime;
      dsl.execute("ROLLBACK TO SAVEPOINT analytics_query");
      return new AnalyticsQueryResponse(
          queryType, List.of(), List.of(), 0, executionTimeMs, 0, false, e.getMessage());
    } finally {
      try {
        dsl.execute("SET LOCAL search_path TO public, data");
      } catch (Exception ignored) {
        // May fail if connection is broken; non-critical
      }
    }
  }

  /**
   * Return table/column information for the data schema. Used for CodeMirror autocomplete and query
   * builder.
   */
  public SchemaInfoResponse getSchemaInfo() {
    // Query information_schema.columns for data schema
    var infoRecords =
        dsl.fetch(
            "SELECT c.table_name, c.column_name, c.data_type, "
                + "       d.id AS dataset_id, d.name AS dataset_name, "
                + "       dc.display_name "
                + "FROM information_schema.columns c "
                + "LEFT JOIN dataset d ON d.table_name = c.table_name "
                + "LEFT JOIN dataset_column dc "
                + "  ON dc.dataset_id = d.id AND dc.column_name = c.column_name "
                + "WHERE c.table_schema = 'data' "
                + "ORDER BY c.table_name, c.ordinal_position");

    Map<String, SchemaInfoResponse.TableInfo> tableMap = new LinkedHashMap<>();

    for (var r : infoRecords) {
      String tableName = r.get("table_name", String.class);
      Long datasetId = r.get("dataset_id", Long.class);
      String datasetName = r.get("dataset_name", String.class);

      tableMap.computeIfAbsent(
          tableName,
          k ->
              new SchemaInfoResponse.TableInfo(
                  tableName, datasetName, datasetId, new ArrayList<>()));

      var tableInfo = tableMap.get(tableName);
      // Add column info to the existing table entry
      var columns = new ArrayList<>(tableInfo.columns());
      columns.add(
          new SchemaInfoResponse.ColumnInfo(
              r.get("column_name", String.class),
              r.get("data_type", String.class),
              r.get("display_name", String.class)));

      tableMap.put(
          tableName,
          new SchemaInfoResponse.TableInfo(
              tableInfo.tableName(), tableInfo.datasetName(), tableInfo.datasetId(), columns));
    }

    return new SchemaInfoResponse(new ArrayList<>(tableMap.values()));
  }

  /**
   * Detect GEOMETRY/GEOGRAPHY columns by inspecting PGobject type via reflection. PostgreSQL driver
   * is runtime-only, so we cannot import PGobject directly.
   */
  private Set<String> detectGeometryColumns(org.jooq.Result<?> result) {
    Set<String> geomColumns = new LinkedHashSet<>();
    if (result.isEmpty()) return geomColumns;

    var firstRecord = result.get(0);
    for (var field : result.fields()) {
      Object val = firstRecord.get(field);
      if (val != null && "org.postgresql.util.PGobject".equals(val.getClass().getName())) {
        try {
          String pgType = (String) val.getClass().getMethod("getType").invoke(val);
          // PostGIS type can be "geometry", "geography", or schema-qualified like
          // "public"."geometry"
          if (pgType != null
              && (pgType.toLowerCase().contains("geometry")
                  || pgType.toLowerCase().contains("geography"))) {
            geomColumns.add(field.getName());
          }
        } catch (ReflectiveOperationException ignored) {
          // Not accessible — skip
        }
      }
    }
    return geomColumns;
  }

  private AnalyticsQueryResponse errorResponse(String message) {
    return new AnalyticsQueryResponse("UNKNOWN", List.of(), List.of(), 0, 0, 0, false, message);
  }
}
