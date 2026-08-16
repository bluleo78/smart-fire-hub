package com.smartfirehub.analytics.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.util.SqlValidationUtils;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

  /**
   * 애널리틱스 경로 전용 인스턴스 — 스프링 빈이 아니라 {@code new}로 직접 생성한다(#385 Task 4). 스프링 컨텍스트의 무인자 {@link
   * SqlValidator} 빈은 파이프라인 경로({@code allowedSchema="data"}, 미한정 거부)를 위한 것이라 여기서 재사용하면 안 된다 —
   * 이 경로는 {@code allowUnqualifiedTables=true}가 필요하다(dev 실사용 쿼리 다수가 미한정, Task 2 실측).
   */
  private final SqlValidator sqlValidator = new SqlValidator("data", true);

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
   * <p>AST 기반 스키마/함수 화이트리스트 검증({@link SqlValidator})은 executor/direct 두 경로로 갈리기 **이전**에 이 메서드에서
   * 공통 수행한다 — 기존 부분문자열 대조({@code contains("PUBLIC.")} 등)가 있던 자리다. 그 대조는 executor 경로에도 걸려 있었으므로(Python
   * 유효성 검사기는 Java 측 스키마 차단을 우회할 수 있다), 자리를 그대로 지켜 executor 경로의 방어가 사라지지 않게 한다. 부분문자열 대조는
   * PostgreSQL 이 허용하는 동등 표기 변형(따옴표, 점 주변 공백)에 뚫린다는 것이 실측됐다(#385 Task 3/4) — AST 스키마 화이트리스트가 정본이다.
   *
   * <p>{@code search_path}는 여전히 {@code 'data', 'public'} 두 스키마를 세운다({@link #executeDirectly} 참고) —
   * {@code 'data'} 단독으로 좁히면 PostGIS 함수 해석이 깨진다는 것을 직접 실측했다({@code function
   * st_asgeojson(public.geometry) does not exist}, geometry 컬럼 타입 자체도 {@code public} 스키마 소속). 따라서 이
   * 검증기는 {@code allowUnqualifiedTables=true}로 미한정 이름을 허용하되, 미한정 이름이 {@code data}에 없고 {@code
   * public}에만 있어 조용히 {@code public}으로 새는 경로는 {@link #executeDirectly}의 카탈로그 조회가 별도로 막는다(AST 는 이름
   * 해석을 못 하므로 검증기 혼자서는 이 판단을 할 수 없다).
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

    try {
      sqlValidator.validate(cleanSql);
    } catch (UnsafeSqlException e) {
      return errorResponse(e.getMessage());
    }

    if (executorEnabled) {
      return executeViaExecutor(cleanSql, maxRows, readOnly);
    }
    return executeDirectly(cleanSql, queryType, maxRows, readOnly);
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

  private AnalyticsQueryResponse executeDirectly(
      String cleanSql, String queryType, int maxRows, boolean readOnly) {
    // 미한정 이름의 public 그림자 차단 — search_path='data','public' 이므로 AST 검증기
    // (allowUnqualifiedTables=true)를 통과한 미한정 이름이 data 스키마엔 없고 public 스키마에만
    // 있으면 조용히 public.<name>으로 해석된다(#385 R1). AST 는 이름 해석을 못 하므로 카탈로그
    // 조회로만 판단 가능 — SET LOCAL search_path 를 세우기 전에 먼저 걸러 불필요한 savepoint를
    // 만들지 않는다.
    try {
      rejectUnqualifiedNamesShadowedByPublic(cleanSql);
    } catch (UnsafeSqlException e) {
      return errorResponse(e.getMessage());
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

  /**
   * 미한정 테이블 이름이 {@code data} 스키마엔 없고 {@code public} 스키마에만 있으면 거부한다 (#385 R1).
   *
   * <p>{@code search_path = 'data', 'public'}에서 미한정 이름은 {@code data}에 동명 테이블이 있으면 그쪽이 먼저 해석되어
   * 안전하다. 문제는 {@code data}엔 없고 {@code public}에만 있는 경우 — {@link SqlValidator}의 {@code
   * allowUnqualifiedTables=true}는 이름 해석을 하지 않으므로 이 쿼리를 그대로 통과시키고, 실행 시점에 조용히 {@code
   * public.<name>}으로 풀려 다른 도메인 테이블(예: {@code public.role}, {@code public."user"})이 새는 경로가 된다.
   *
   * <p>반대로 {@code data}에도 {@code public}에도 없는 이름은 여기서 막지 않는다 — Postgres 가 그대로 "relation does
   * not exist"(42P01)를 던지며, 그 결과는 새로운 노출이 아니다({@code
   * executeDirectly_undefinedTable_returnsCleanErrorWithSqlState42P01} 계약 유지).
   *
   * <p>미한정 참조가 없는 쿼리(가장 흔한 경우 — 대부분의 프로그래밍 방식 쿼리는 {@code data.*}로 명시)는 카탈로그 조회를 아예
   * 건너뛴다. 미한정 참조가 있는 쿼리만 쿼리 실행 전 1회 추가 카탈로그 조회 비용이 붙는다 — 애널리틱스 쿼리 UI는 초당 다건이 아닌
   * 사용자 상호작용 경로라 이 비용은 무시할 만하다.
   */
  private void rejectUnqualifiedNamesShadowedByPublic(String cleanSql) {
    Set<String> unqualified = sqlValidator.unqualifiedTableNames(cleanSql);
    if (unqualified.isEmpty()) {
      return;
    }

    String placeholders = String.join(",", java.util.Collections.nCopies(unqualified.size(), "?"));
    String catalogSql =
        "SELECT table_schema, table_name FROM information_schema.tables "
            + "WHERE table_name IN ("
            + placeholders
            + ") AND table_schema IN ('data', 'public')";
    var rows = dsl.fetch(catalogSql, unqualified.toArray());

    Set<String> inData = new HashSet<>();
    Set<String> inPublic = new HashSet<>();
    for (var r : rows) {
      String schema = r.get("table_schema", String.class);
      String name = r.get("table_name", String.class);
      if ("data".equals(schema)) {
        inData.add(name);
      } else if ("public".equals(schema)) {
        inPublic.add(name);
      }
    }

    for (String name : unqualified) {
      if (!inData.contains(name) && inPublic.contains(name)) {
        throw new UnsafeSqlException(
            "테이블 참조에 스키마가 없습니다: '"
                + name
                + "'. data 스키마에 존재하지 않아 public 스키마로 해석될 수 있어 거부합니다. data."
                + name
                + " 형식으로 명시하세요.");
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
   *
   * <p>package-private — same-package 테스트에서 분기 커버리지(truncate, sem==null fallback) 직접 검증을 위해 노출. 외부 호출은
   * 위 catch 블록 한 곳뿐.
   */
  String formatExecutionError(Exception e) {
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
