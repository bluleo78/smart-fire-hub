package com.smartfirehub.analytics.service;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SchemaInfoResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.tenant.DataSchema;
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
   * 애널리틱스 경로 전용 인스턴스 — 스프링 빈이 아니라 {@link SqlValidator#forAdhocDataSchemaQueries()}로
   * 직접 생성한다(#385 Task 4, 팩터리 도입 근거는 R1). 스프링 컨텍스트의 무인자 {@link SqlValidator} 빈은
   * 파이프라인 경로 전용이라 여기서 재사용하면 안 된다.
   */
  private final SqlValidator sqlValidator = SqlValidator.forAdhocDataSchemaQueries();

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
   * 검증기는 {@code allowUnqualifiedTables=true}로 미한정 이름을 허용한다.
   *
   * <p><b>두 후속 검사의 적용 범위가 다르다 — 혼동하지 말 것.</b> {@code sqlValidator.validate(...)}(스키마 화이트리스트·차단
   * 함수)는 위에서 말한 대로 executor/direct 공통이다. 반면 {@link #rejectUnqualifiedNamesShadowedByPublic}(미한정
   * 이름이 {@code data}엔 없고 {@code public}에만 있어 조용히 새는 경로 차단)은 {@link #executeDirectly} 안에서만
   * 호출된다 — {@code search_path='data','public'}은 이 서비스가 직접 여는 Java/jOOQ 커넥션에만 세팅되는 세션 상태이고,
   * executor(Python) 경로는 별도 프로세스·별도 DB 커넥션으로 돌기 때문에 이 카탈로그 조회가 그 경로에는 애초에 적용될 수 없다.
   * executor 경로가 오늘 이 구멍에서 안전한 이유는 별도다 — executor 가 사용하는 DB 역할({@code pipeline_executor})은
   * {@code public.role}에 대한 SELECT 권한 자체가 없다({@code has_table_privilege('pipeline_executor',
   * 'public.role','SELECT')} 실측 = {@code false}). 즉 그 경로에서 미한정 {@code role}이 {@code public.role}로
   * 풀리더라도 GRANT 단계에서 permission denied 로 막힌다 — 이 판단이 뒤집히면(예: 향후 executor 역할에 더 넓은 public
   * 권한이 부여되면) 이 카탈로그 조회를 executor 경로에도 확장해야 한다.
   *
   * @param sql raw SQL from user
   * @param maxRows maximum rows to return (1–10000)
   * @param readOnly if true, only SELECT/WITH is allowed (used by MCP tools and Web UI ad-hoc
   *     query — same flag, shared by both callers, so its error message must stay caller-neutral)
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
      // #511: 이 readOnly 검사는 MCP AI 도구 호출과 웹 UI 애드혹 쿼리 실행이 동일 엔드포인트/플래그를
      // 공유하기 때문에 호출 맥락(AI vs 웹 UI)을 구분할 수 없다 — 메시지를 "AI 도구" 특정 문구가 아닌
      // 컨텍스트 중립적인 문구로 유지해야 웹 UI 사용자에게 혼란을 주지 않는다.
      return errorResponse("SELECT 쿼리만 실행할 수 있습니다. 데이터 수정은 데이터셋 상세의 '데이터' 탭을 이용하세요.");
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

  /**
   * 사용자 SQL 을 API 자신의 커넥션으로 직접 실행하는 경로(executor 를 쓰지 않는 폴백·local 경로).
   *
   * <p><b>⚠ 테넌트별 파이프라인 롤 격리는 이 경로에 적용되지 않는다(P3-b1 Task 3 Step 4b).</b>
   * 파이프라인 SQL 스텝은 P3-b1 부터 테넌트별 DB 롤({@code pipeline_executor_t{tenantId}}) 자격증명으로
   * 접속하므로, 잘못된 문장이 검증기를 빠져나가도 DB 권한이 두 번째 벽으로 남는다. 그러나 이 메서드는
   * 메인 애플리케이션 {@code DSLContext}(= {@code app_tenant} 롤) 로 실행한다 — 그 롤은 데이터셋 생성
   * 시 {@code data} 스키마에 런타임 DDL 을 하는 주체라 권한을 좁힐 수 없고, 좁히면 데이터셋 생성이
   * 깨진다. <b>따라서 이 경로의 통제는 RLS + {@link SqlValidator} 두 가지뿐이며, grant 계층의 이중
   * 방어가 없다.</b> 이 경로를 grant 계층으로 덮는 것은 별도 과제다(#383/#384).
   */
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
    // 데이터 스키마명은 현재 테넌트에서 파생시킨다(DataSchema.current() = 인용 없는 식별자).
    // search_path 는 한정 이름이 아니라 스키마 식별자 목록이므로 qualify() 가 아니라 current() 다.
    //
    // P3-b2 T4 — 무변경 판정(실측 근거). current() 가 숫자 접미사 스키마(data_t{id})를 돌려주게
    // 된 뒤에도 이 손 조립이 여전히 올바른지 PostgreSQL 로 직접 확인했다 — 실측은
    // data_t900000123 라는 한 이름으로만 확인했다(자릿수 자체는 식별자 해석에 아무 역할을 하지
    // 않으므로 다른 자릿수도 같은 결론이라고 추론한다). 작은따옴표로 인용한
    // 스키마명은 숫자를 포함해도 인용 없는 형태와 동일하게 해석되고,
    // 콤마로 이은 두 번째 스키마('public')도 문제없이 병기된다(실측: SHOW search_path 로 확인한
    // 문자열이 그대로 실제 테이블 조회에도 반영됨). AnalyticsQueryExecutionServiceTenantSchemaTest
    // 가 접미사 붙은 테넌트로 이 문장을 실제 프로덕션 경로로 재확인한다. 그래서 이 줄은 고치지
    // 않는다 — 인용이 이미 있어 안전하다.
    dsl.execute("SET LOCAL search_path = '" + DataSchema.current() + "', 'public'");
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
   *
   * <p><b>정본은 {@code pg_class}+{@code pg_namespace} 다 — {@code information_schema.tables} 가 아니다.</b>
   * {@code information_schema.tables} 는 relkind {@code r/p/v/f}(테이블·파티션·뷰·외래 테이블)만 담고
   * **시퀀스({@code S})는 담지 않는다**(리뷰어 실측: app_tenant 기준 public 가시/전체 = r 68/68, v 3/3,
   * S 0/49). 그 결과 {@code SELECT last_value, log_cnt FROM oauth_state_id_seq}처럼 시퀀스를 미한정으로
   * 참조하면 이전 구현(information_schema)에서는 검사 대상에 아예 안 잡혀 그대로 통과·실행됐다(실측: 값 반환
   * 확인). {@code pg_class.relkind}를 {@code r/p/v/m/f/S}(테이블/파티션/뷰/구체화 뷰/외래 테이블/시퀀스)로
   * 넓혀 이 사각을 없앤다 — 권한 때문에 {@code information_schema}에 안 보이던 객체도 이 카탈로그 조회는
   * 여전히 존재 자체는 볼 수 있다({@code has_table_privilege}가 아니라 오브젝트 존재만 확인하면 되므로).
   */
  private void rejectUnqualifiedNamesShadowedByPublic(String cleanSql) {
    Set<String> unqualified = sqlValidator.unqualifiedTableNames(cleanSql);
    if (unqualified.isEmpty()) {
      return;
    }

    // R2(#385 코드리뷰) — 손으로 "?,?,?" 자리표시자를 조립하는 대신 jOOQ 네이티브 컬렉션 바인딩
    // (field.in(Collection))을 쓴다. 동작은 동일(파라미터 바인딩, 인젝션 경로 아님)하고 문자열 조립
    // 실수(개수 불일치 등) 여지가 없다. pg_class/pg_namespace 는 jOOQ 코드젠 대상(public 스키마)이
    // 아니라 DSL.table/field 로 이름만 참조한다.
    // 카탈로그 필터의 스키마명 — 낡은 리터럴을 남기면 스키마가 분리되는 순간 이 조회가 **예외도
    // 로그도 없이 0행**을 돌려주고, 그러면 아래 그림자 판정이 "data 에도 public 에도 없음" 으로
    // 오판해 이 방어가 조용히 무력화된다. 그래서 현재 테넌트에서 파생시킨다.
    // (early return 뒤에 놓은 것은 의도적이다 — 미한정 참조가 없어 아무 일도 하지 않던 경로에
    //  테넌트 컨텍스트 요구를 새로 붙이지 않는다.)
    String dataSchema = DataSchema.current();

    var relnameField = field(name("c", "relname"), String.class);
    var nspnameField = field(name("n", "nspname"), String.class);
    var relkindField = field(name("c", "relkind"), String.class);
    var rows =
        dsl.select(nspnameField, relnameField)
            .from(table(name("pg_class")).as("c"))
            .join(table(name("pg_namespace")).as("n"))
            .on(field(name("n", "oid")).eq(field(name("c", "relnamespace"))))
            .where(relnameField.in(unqualified))
            .and(nspnameField.in(dataSchema, "public"))
            .and(relkindField.in("r", "p", "v", "m", "f", "S"))
            .fetch();

    Set<String> inData = new HashSet<>();
    Set<String> inPublic = new HashSet<>();
    for (var r : rows) {
      String schema = r.get(nspnameField);
      String name = r.get(relnameField);
      // 리터럴 대조가 아니라 위에서 파생시킨 스키마명과 비교한다 — 리터럴을 남기면 스키마가
      // 분리된 뒤 이 분기가 **던지지 않고 조용히 else 로 떨어져** 잘못된 경로를 탄다.
      if (dataSchema.equals(schema)) {
        inData.add(name);
      } else if ("public".equals(schema)) {
        inPublic.add(name);
      }
    }

    for (String name : unqualified) {
      if (!inData.contains(name) && inPublic.contains(name)) {
        // 사용자 노출 메시지의 스키마명도 파생시킨다 — 리터럴로 두면 개명 뒤 "존재하지 않는
        // 스키마를 쓰라고 안내하는" 틀린 문장이 된다.
        throw new UnsafeSqlException(
            "테이블 참조에 스키마가 없습니다: '"
                + name
                + "'. "
                + dataSchema
                + " 스키마에 존재하지 않아 public 스키마로 해석될 수 있어 거부합니다. "
                + DataSchema.qualify(name)
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
   *     <p>#596: {@code @Transactional}이 없으면 {@code TenantAwareTransactionManager.doBegin()}이
   *     실행되지 않아 {@code app.tenant_id} GUC가 주입되지 않는다. 그 결과 V107 이후 RLS가 걸린
   *     {@code dataset}/{@code dataset_column} 테이블에 대한 이 raw-SQL LEFT JOIN이 예외 없이 항상
   *     0행을 반환해(fail-closed) datasetId/datasetName이 늘 null이 된다(형제 메서드 {@link
   *     #execute}는 이미 {@code @Transactional}이 있어 문제가 없었다).
   */
  @Transactional(readOnly = true)
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
            // 스키마명은 현재 테넌트에서 파생 + 바인드 파라미터로 넘긴다. 낡은 리터럴을 남기면
            // 스키마 분리 후 이 조회가 예외도 로그도 없이 0행이 되어 스키마 정보가 빈 응답이 된다.
            .append("WHERE c.table_schema = ? ");

    if (datasetIds != null) {
      // datasetIds 는 컨트롤러에서 Long 타입으로 바인딩 — SQL injection 위험 없음
      String csv =
          datasetIds.stream()
              .map(String::valueOf)
              .collect(java.util.stream.Collectors.joining(","));
      sql.append("AND d.id IN (").append(csv).append(") ");
    }

    sql.append("ORDER BY c.table_name, c.ordinal_position");

    var infoRecords = dsl.fetch(sql.toString(), DataSchema.current());

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
