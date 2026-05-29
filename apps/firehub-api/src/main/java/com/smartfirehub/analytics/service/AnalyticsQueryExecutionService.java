package com.smartfirehub.analytics.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AnalyticsQueryExecutionService {

  private final DSLContext dsl;
  private final ExecutorClient executorClient;

  @Value("${app.executor.enabled:false}")
  private boolean executorEnabled;

  public AnalyticsQueryExecutionService(DSLContext dsl, ExecutorClient executorClient) {
    this.dsl = dsl;
    this.executorClient = executorClient;
  }

  /**
   * Execute SQL against the data schema. Routes to executor service when executorEnabled=true,
   * otherwise executes directly via jOOQ.
   *
   * <p>시스템 스키마 접근 차단은 executor/direct 경로 공통으로 이 메서드에서 수행한다. executor 경로는 Python 유효성 검사기를 사용하므로 Java
   * 측 스키마 차단을 우회할 수 있다.
   *
   * @param sql raw SQL from user
   * @param maxRows maximum rows to return (1–10000)
   * @param readOnly if true, only SELECT/WITH is allowed (used by MCP tools)
   */
  @Transactional
  public AnalyticsQueryResponse execute(String sql, int maxRows, boolean readOnly) {
    // 시스템 스키마/함수 직접 참조 차단 — executor/direct 경로 모두 적용 (#33/#34/#86/#90)
    // executor 경로는 Python 측 차단만 있어 public 스키마 접근이 가능하므로 여기서 공통 차단
    String upperSql = sql.toUpperCase();
    if (upperSql.contains("PUBLIC.")
        || upperSql.contains("INFORMATION_SCHEMA")
        || upperSql.contains("PG_CATALOG")
        || upperSql.contains("PG_READ_FILE")
        || upperSql.contains("PG_EXECUTE")) {
      return errorResponse("보안 정책상 public 스키마 또는 시스템 스키마에 직접 접근할 수 없습니다.");
    }

    if (executorEnabled) {
      return executeViaExecutor(sql, maxRows, readOnly);
    }
    return executeDirectly(sql, maxRows, readOnly);
  }

  private AnalyticsQueryResponse executeViaExecutor(String sql, int maxRows, boolean readOnly) {
    try {
      var result = executorClient.executeQuery(sql, maxRows, readOnly);
      if (!result.success()) {
        return errorResponse(result.error());
      }
      return new AnalyticsQueryResponse(
          result.queryType(),
          result.columns(),
          result.rows(),
          result.rowCount(),
          result.executionTimeMs(),
          result.rows().size(),
          result.truncated(),
          null);
    } catch (Exception e) {
      log.error("Executor query execution failed", e);
      return errorResponse("Executor 연결 실패: " + e.getMessage());
    }
  }

  private AnalyticsQueryResponse executeDirectly(String sql, int maxRows, boolean readOnly) {
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

    // 시스템 스키마/함수 직접 참조 차단 (#33/#34/#86 보안: 비밀번호 해시 유출, 파일 읽기 방지)
    String upperSql = cleanSql.toUpperCase();
    if (upperSql.contains("PUBLIC.")
        || upperSql.contains("INFORMATION_SCHEMA")
        || upperSql.contains("PG_CATALOG")
        || upperSql.contains("PG_READ_FILE")
        || upperSql.contains("PG_EXECUTE")) {
      return errorResponse("보안 정책상 public 스키마 또는 시스템 스키마에 직접 접근할 수 없습니다.");
    }

    long startTime = System.currentTimeMillis();

    // search_path에 data 스키마와 public 스키마(PostGIS 확장 함수 위치) 포함 (#121)
    // public 스키마 직접 참조(PUBLIC. prefix)는 보안상 여전히 차단되지만,
    // ST_AsGeoJSON 등 PostGIS 함수는 search_path를 통한 암묵적 참조로 사용 가능하도록 허용
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

        org.jooq.Result<?> result;
        try {
          result = dsl.fetch(limitedSql);
        } catch (Exception fetchEx) {
          // jOOQ/JDBC may fail to read raw GEOMETRY/GEOGRAPHY binary data.
          // Rollback to savepoint, detect geometry columns via JDBC metadata, wrap with
          // ST_AsGeoJSON, and retry.
          dsl.execute("ROLLBACK TO SAVEPOINT analytics_query");
          dsl.execute("SAVEPOINT analytics_query");

          List<ColumnMeta> columnMetas;
          try {
            columnMetas = detectColumnsViaMetadata(cleanSql);
          } catch (Exception metaEx) {
            throw fetchEx; // Metadata detection also failed — rethrow original
          }

          boolean hasGeometry = columnMetas.stream().anyMatch(ColumnMeta::isGeometry);
          if (!hasGeometry) {
            throw fetchEx; // Not a geometry issue — rethrow original
          }

          String wrappedSql = buildGeoJsonWrappedSql(cleanSql, columnMetas);
          if (!cleanSql.toUpperCase().matches("(?s).*\\bLIMIT\\s+\\d+.*")) {
            wrappedSql = wrappedSql + " LIMIT " + maxRows;
          }
          result = dsl.fetch(wrappedSql);
        }

        // Detect GEOMETRY/GEOGRAPHY columns from successfully fetched PGobject data
        // (covers cases where JDBC read succeeded but data is raw binary)
        Set<String> geomColumns = detectGeometryColumns(result);
        if (!geomColumns.isEmpty()) {
          List<ColumnMeta> metas = new ArrayList<>();
          for (var field : result.fields()) {
            metas.add(new ColumnMeta(field.getName(), geomColumns.contains(field.getName())));
          }
          String wrappedSql = buildGeoJsonWrappedSql(cleanSql, metas);
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
          queryType, List.of(), List.of(), 0, executionTimeMs, 0, false, formatExecutionError(e));
    } finally {
      try {
        dsl.execute("SET LOCAL search_path TO public");
      } catch (Exception ignored) {
        // May fail if connection is broken; non-critical
      }
    }
  }

  /** BC 진입점 — 인자 없이 호출되는 기존 외부 호출자(Web UI, 컨트롤러 BC)를 위해 유지. 내부적으로 datasetIds=null 오버로드에 위임한다. */
  public SchemaInfoResponse getSchemaInfo() {
    return getSchemaInfo(null);
  }

  /**
   * data 스키마의 테이블·컬럼 정보를 반환한다.
   *
   * @param datasetIds 필터링할 dataset id 목록.
   *     <ul>
   *       <li>{@code null} — 전체 반환 (BC: 기존 인자 없는 호출과 동일)
   *       <li>비어있음 — 빈 응답 (defensive: 외부에서 ?datasetIds= 빈값으로 호출 시 전체 폴백 방지)
   *       <li>값 있음 — 해당 id 들만 필터
   *     </ul>
   */
  public SchemaInfoResponse getSchemaInfo(List<Long> datasetIds) {
    if (datasetIds != null && datasetIds.isEmpty()) {
      return new SchemaInfoResponse(List.of());
    }

    StringBuilder sql =
        new StringBuilder()
            .append("SELECT c.table_name, c.column_name, c.data_type, ")
            .append("       d.id AS dataset_id, d.name AS dataset_name, ")
            .append("       dc.display_name ")
            .append("FROM information_schema.columns c ")
            .append("LEFT JOIN dataset d ON d.table_name = c.table_name ")
            .append("LEFT JOIN dataset_column dc ")
            .append("  ON dc.dataset_id = d.id AND dc.column_name = c.column_name ")
            .append("WHERE c.table_schema = 'data' ");

    if (datasetIds != null) {
      // datasetIds 는 컨트롤러에서 Long 타입으로 바인딩 — SQL injection 위험 없음
      String csv =
          datasetIds.stream()
              .map(String::valueOf)
              .collect(java.util.stream.Collectors.joining(","));
      sql.append("AND d.id IN (").append(csv).append(") ");
    }

    sql.append("ORDER BY c.table_name, c.ordinal_position");

    var infoRecords = dsl.fetch(sql.toString());

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
      // 기존 테이블 항목에 컬럼 정보 추가
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

  /** Column name + whether it is a GEOMETRY/GEOGRAPHY type. */
  private record ColumnMeta(String name, boolean isGeometry) {}

  /**
   * Detect column names and geometry types via JDBC ResultSetMetaData using a LIMIT 0 query. This
   * avoids reading actual row data, which can fail for GEOMETRY columns.
   */
  private List<ColumnMeta> detectColumnsViaMetadata(String sql) {
    List<ColumnMeta> columns = new ArrayList<>();
    String metaSql = "SELECT * FROM (" + sql + ") _geom_detect LIMIT 0";
    dsl.connection(
        conn -> {
          try (var ps = conn.prepareStatement(metaSql);
              var rs = ps.executeQuery()) {
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
              String typeName = meta.getColumnTypeName(i);
              boolean isGeom =
                  typeName != null
                      && (typeName.equalsIgnoreCase("geometry")
                          || typeName.equalsIgnoreCase("geography"));
              columns.add(new ColumnMeta(meta.getColumnLabel(i), isGeom));
            }
          }
        });
    return columns;
  }

  /**
   * Build a CTE-wrapped SQL that replaces GEOMETRY columns with public.ST_AsGeoJSON() calls. Does
   * NOT append LIMIT — caller adds it if needed.
   */
  private String buildGeoJsonWrappedSql(String originalSql, List<ColumnMeta> columns) {
    StringBuilder sb = new StringBuilder("WITH _src AS (");
    sb.append(originalSql);
    sb.append(") SELECT ");
    boolean first = true;
    for (var col : columns) {
      if (!first) sb.append(", ");
      first = false;
      String escaped = col.name().replace("\"", "\"\"");
      if (col.isGeometry()) {
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
    return sb.toString();
  }

  private AnalyticsQueryResponse errorResponse(String message) {
    return new AnalyticsQueryResponse("UNKNOWN", List.of(), List.of(), 0, 0, 0, false, message);
  }

  // ============================================================
  // SQL 실행 에러 포맷터 (PR-2, refs #267, #272)
  //  - PSQLException.getServerErrorMessage() 분해로 LLM 친화적 자연어 인라인 포맷 반환
  //  - jOOQ 가 prefix 로 echo 하는 SQL 본문 제거 (회당 6KB → ~150B)
  //  - 모든 경로 2000자 truncate 가드
  // ============================================================

  private static final int ERROR_MAX_LEN = 2000;

  /**
   * 실행 catch 블록에서 응답 error 필드 문자열을 생성한다.
   *
   * <p>분해 우선순위:
   *
   * <ol>
   *   <li>cause 체인 unwrap → PSQLException
   *   <li>ServerErrorMessage 있으면 MESSAGE / HINT / DETAIL / SQLState / Position 조립
   *   <li>PSQL 인데 sem 없으면 psql.getMessage() 만
   *   <li>PSQL 아니면 원본 메시지 (또는 toString)
   * </ol>
   */
  private String formatExecutionError(Exception e) {
    Throwable cause = e;
    while (cause.getCause() != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }

    if (cause instanceof PSQLException psql) {
      ServerErrorMessage sem = psql.getServerErrorMessage();
      if (sem != null) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: ").append(nullToEmpty(sem.getMessage()));
        appendIfPresent(sb, "\nHINT: ", sem.getHint());
        appendIfPresent(sb, "\nDETAIL: ", sem.getDetail());
        appendIfPresent(sb, "\nSQLState: ", sem.getSQLState());
        if (sem.getPosition() > 0) {
          sb.append("\nPosition: ").append(sem.getPosition());
        }
        return truncate(sb.toString());
      }
      return truncate("ERROR: " + nullToEmpty(psql.getMessage()));
    }

    String msg = cause.getMessage();
    return truncate(msg != null ? msg : e.toString());
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static void appendIfPresent(StringBuilder sb, String prefix, String value) {
    if (value != null && !value.isBlank()) {
      sb.append(prefix).append(value);
    }
  }

  private static String truncate(String s) {
    if (s == null) return "";
    if (s.length() <= ERROR_MAX_LEN) return s;
    return s.substring(0, ERROR_MAX_LEN - 20) + "... [truncated]";
  }
}
