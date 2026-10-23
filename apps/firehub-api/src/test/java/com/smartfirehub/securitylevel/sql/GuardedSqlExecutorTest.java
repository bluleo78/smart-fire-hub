package com.smartfirehub.securitylevel.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.dataset.service.DataTableQueryService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.service.SecurityAuditRecorder;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 애드혹 SQL 단일 관문(스펙 §4.1): 판정한 문자열 = 실행한 문자열, 열람 거부 403, 오류 계약(데이터셋 400 예외·애널리틱스 200 error).
 *
 * <p>실제 data 테이블을 만들어 실행까지 확인한다 — 판정만 보면 "판정은 통과했는데 실행은 다른 문자열" 결함을 못 잡는다.
 */
class GuardedSqlExecutorTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private GuardedSqlExecutor gate;
  @Autowired private DatasetDataService datasetDataService;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private PlatformTransactionManager transactionManager;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private String schema;
  private String pub;
  private String hidden;
  private long pubId;
  private long userId;
  private long creator;
  private long hiddenId;
  private final List<Long> savedQueries = new ArrayList<>();
  private Clearance viewer;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creator = fx.createUser("gse_c");
    users.add(creator);
    schema = DataSchema.current();
    String m = "gse" + System.nanoTime();
    pub = m + "_pub";
    hidden = m + "_hidden";
    // 실행까지 확인하므로 물리 테이블도 만든다. hidden 의 값(4242)이 결과에 나오면 누출이다.
    dsl.execute("CREATE TABLE " + schema + "." + pub + " (a int)");
    dsl.execute("INSERT INTO " + schema + "." + pub + " VALUES (1)");
    dsl.execute("CREATE TABLE " + schema + "." + hidden + " (a int)");
    dsl.execute("INSERT INTO " + schema + "." + hidden + " VALUES (4242)");
    pubId = fx.createDatasetRow(pub, fx.levelId("공개"), creator);
    datasets.add(pubId);
    hiddenId = fx.createDatasetRow(hidden, fx.levelId("기밀"), creator);
    datasets.add(hiddenId);

    // '민감' 자격 — 공개는 보고 기밀(허용 목록 필요)은 못 본다.
    userId = fx.createUser("gse_u");
    users.add(userId);
    fx.removeUserRole(userId);
    long rid = fx.createRole("gse_r_" + System.nanoTime(), fx.levelId("민감"), "dataset:read");
    roles.add(rid);
    fx.assignRole(userId, rid);
    viewer = clearanceResolver.resolve(userId);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    savedQueries.forEach(id -> savedQueryService.delete(id, creator));
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + pub);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + hidden);
  }

  /**
   * 원문은 pub 만 참조하지만, 리터럴을 인지하지 못하는 주석 제거(stripAndValidate)를 거친 실행 문자열은 hidden 을 참조한다. 관문이 원문을 판정하면
   * 통과 후 hidden 이 실행된다 — 정규화본을 판정해야 거부된다.
   */
  private String strippedOnlyLeak() {
    return "SELECT '/*' /* */ || ', (SELECT a FROM "
        + hidden
        + " LIMIT 1) AS y FROM "
        + pub
        + " --' AS x FROM "
        + pub;
  }

  /** 전제 확인 — 원문은 hidden 을 숨기고, 정규화본(실행 문자열)은 드러낸다. 이 전제가 깨지면 아래 테스트가 공허해진다. */
  @Test
  void leakString_rawHidesHiddenButNormalizedReferencesIt() {
    String raw = strippedOnlyLeak();
    String normalized = NormalizedSql.of(raw).text();
    assertThat(normalized).contains("FROM " + hidden);
    assertThat(normalized).doesNotContain("--");
    // 원문을 판정하면 pub 만 보이므로 허용된다 — 관문이 원문을 판정하면 새는 이유.
    assertThat(guard.checkSql(viewer, raw, SqlAccessMode.INTERACTIVE).allowed()).isTrue();
  }

  @Test
  void analytics_judgesTheExecutedString_notTheRawInput() {
    assertThatThrownBy(() -> gate.executeAnalytics(viewer, strippedOnlyLeak(), 100, true))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  /**
   * 차트·대시보드 사전 판정(Task 16)도 실행 문자열(정규화본)을 판정한다 — 원문을 판정하면 사전 판정은 통과하고 실행 관문만 거부해 위젯 denied 가 아니라 요청
   * 실패가 된다. 파싱 불가 SQL 은 거부가 아니다(실행이 200 + error 로 바꾼다).
   */
  @Test
  void analyticsDeniedPrecheck_judgesTheExecutedString_notTheRawInput() {
    assertThat(gate.judgeAnalytics(viewer, strippedOnlyLeak()).denied()).isTrue();
    assertThat(gate.judgeAnalytics(viewer, "SELECT a FROM " + pub).denied()).isFalse();
    assertThat(gate.judgeAnalytics(viewer, "SELEC a FRM x").denied()).isFalse();
  }

  @Test
  void datasetQuery_judgesTheExecutedString_notTheRawInput() {
    asUser(userId);
    assertThatThrownBy(
            () ->
                datasetDataService.executeQuery(
                    pubId, new SqlQueryRequest(strippedOnlyLeak(), 100), userId))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  /** 양성 대조 — 볼 수 있는 테이블만 참조하면 실제로 실행된다(관문이 모든 것을 거부해서 통과하는 공허한 테스트가 아님). */
  @Test
  void readableSql_executesThroughBothPaths() {
    AnalyticsQueryResponse a = gate.executeAnalytics(viewer, "SELECT a FROM " + pub, 100, true);
    assertThat(a.error()).isNull();
    assertThat(a.rows()).hasSize(1);
    assertThat(a.rows().get(0).get("a")).isEqualTo(1);

    asUser(userId);
    SqlQueryResponse d =
        datasetDataService.executeQuery(
            pubId, new SqlQueryRequest("SELECT a FROM " + pub + ";", 100), userId);
    assertThat(d.error()).isNull();
    assertThat(d.rows()).hasSize(1);
  }

  /**
   * 보안(WD-91 리뷰): 분석 관문의 읽기 전용은 CTE 이름·따옴표 식별자에 SELECT 가 들어 있는 DML 본문을 거부한다. 예전엔 본문 키워드를 단어 경계 없이
   * 찾아 SELECT 로 분류했고, 관문을 지나 DELETE·UPDATE 가 실제로 실행됐다.
   */
  @Test
  void analyticsReadOnly_rejectsDmlBodyBehindCteNamedLikeSelect_andNothingChanges() {
    List<String> dml =
        List.of(
            "WITH selected AS (SELECT 1) DELETE FROM " + pub,
            "WITH \"select\" AS (SELECT 1) UPDATE " + pub + " SET a = 2",
            "WITH\"selected\"AS(SELECT 1)DELETE FROM " + pub,
            // 최상위 LIMIT 이 있으면 행 제한을 덧붙이지 않아 문법 오류로 우연히 막히지도 않는다.
            "WITH selected AS (SELECT 1) INSERT INTO " + pub + " SELECT 7 LIMIT 1 RETURNING *");
    List<String> errors = new ArrayList<>();
    for (String sql : dml) {
      errors.add(gate.executeAnalytics(viewer, sql, 100, true).error());
    }
    // 행 변화를 먼저 본다 — 분류가 틀리면 실제로 일어난 쓰기가 결함의 실체다.
    assertThat(dsl.fetchOne("SELECT count(*) FROM " + schema + "." + pub).get(0, Integer.class))
        .isEqualTo(1);
    assertThat(dsl.fetchOne("SELECT a FROM " + schema + "." + pub).get(0, Integer.class))
        .isEqualTo(1);
    for (int k = 0; k < dml.size(); k++) {
      assertThat(errors.get(k)).as(dml.get(k)).contains("SELECT 쿼리만");
    }
  }

  /**
   * WD-91: 첫 키워드 바로 뒤에 구두점이 붙은 SELECT({@code SELECT*FROM}, {@code select(a)})도 관문(열람 판정 포함)을 지나 두
   * 경로에서 실행된다. 열람 판정은 그대로라 숨은 테이블은 붙여 써도 거부되고, 붙여 쓴 CTE 안 DELETE 는 읽기 전용에서 실행되지 않는다.
   */
  @Test
  void punctuationRightAfterKeyword_executesThroughBothPaths_andGatesStillHold() {
    AnalyticsQueryResponse a = gate.executeAnalytics(viewer, "SELECT*FROM " + pub, 100, true);
    assertThat(a.error()).isNull();
    assertThat(a.rows()).hasSize(1);
    assertThat(a.rows().get(0).get("a")).isEqualTo(1);

    assertThatThrownBy(() -> gate.executeAnalytics(viewer, "SELECT*FROM " + hidden, 100, true))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");

    AnalyticsQueryResponse cte =
        gate.executeAnalytics(
            viewer, "WITH\"d\"AS(DELETE FROM " + pub + " RETURNING *)SELECT*FROM\"d\"", 100, true);
    assertThat(cte.error()).isNotNull();
    assertThat(dsl.fetchOne("SELECT count(*) FROM " + schema + "." + pub).get(0, Integer.class))
        .isEqualTo(1);

    asUser(userId);
    SqlQueryResponse d =
        datasetDataService.executeQuery(
            pubId, new SqlQueryRequest("select(a) from " + pub, 100), userId);
    assertThat(d.error()).isNull();
    assertThat(d.rows()).hasSize(1);
  }

  @Test
  void hiddenTable_isDeniedOnBothPaths() {
    assertThatThrownBy(() -> gate.executeAnalytics(viewer, "SELECT a FROM " + hidden, 100, true))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    asUser(userId);
    assertThatThrownBy(
            () ->
                datasetDataService.executeQuery(
                    pubId, new SqlQueryRequest("SELECT a FROM " + hidden, 100), userId))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  /** 인증 없는 요청 경로는 아무것도 못 보는 자격이다(fail-closed) — 공개 데이터셋도 거부. */
  @Test
  void datasetQuery_withoutAuthentication_isDenied() {
    assertThatThrownBy(
            () ->
                datasetDataService.executeQuery(
                    pubId, new SqlQueryRequest("SELECT a FROM " + pub, 100), userId))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
  }

  /**
   * 감사 등급 접근(DATASET_ACCESS)은 허용 판정이 아니라 실행이 성공해 결과가 나간 뒤에 남는다(코드리뷰 3 과 같은 규칙). 판정은 통과하지만 실행이 실패하는
   * SQL(0 으로 나누기 → 200 + error)·판정 거부(403)는 0건, 같은 데이터셋을 성공적으로 읽으면 1건. 실패 → 성공 순서인 이유: 레코더의 1분 병합
   * 창이 성공 뒤 같은 사용자·데이터셋 기록을 합치므로, 반대 순서면 "0건"이 공허하게 통과한다.
   */
  @Test
  void datasetQuery_recordsAuditAccessOnlyAfterSuccessfulExecution() {
    String sens = "gse" + System.nanoTime() + "_sens";
    dsl.execute("CREATE TABLE " + schema + "." + sens + " (a int)");
    try {
      dsl.execute("INSERT INTO " + schema + "." + sens + " VALUES (1)");
      long sensId = fx.createDatasetRow(sens, fx.levelId("민감"), creator);
      datasets.add(sensId);
      asUser(userId);

      SqlQueryResponse failed =
          datasetDataService.executeQuery(
              sensId, new SqlQueryRequest("SELECT a / 0 AS x FROM " + sens, 100), userId);
      assertThat(failed.error()).as("전제: 판정은 통과하고 실행이 실패한다").isNotBlank();
      assertThatThrownBy(
              () ->
                  datasetDataService.executeQuery(
                      sensId,
                      new SqlQueryRequest("SELECT s.a FROM " + sens + " s, " + hidden + " h", 100),
                      userId))
          .isInstanceOf(CodedApiException.class);
      assertThat(accessRows(userId, sensId)).isZero();

      SqlQueryResponse ok =
          datasetDataService.executeQuery(
              sensId, new SqlQueryRequest("SELECT a FROM " + sens, 100), userId);
      assertThat(ok.error()).isNull();
      assertThat(accessRows(userId, sensId)).isEqualTo(1);
    } finally {
      dsl.execute("DROP TABLE IF EXISTS " + schema + "." + sens);
    }
  }

  /** 이 사용자·데이터셋의 감사 등급 접근(DATASET_ACCESS) 행 수(비동기 기록이 끝난 뒤). */
  private int accessRows(long uid, long datasetId) {
    awaitSecurityAudit();
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                        + " 'DATASET_ACCESS' AND resource_id = ?",
                    uid,
                    String.valueOf(datasetId))
                .get(0, Integer.class));
  }

  /** 애널리틱스 오류 계약 — 정규화·판정 전 파싱 오류는 예외가 아니라 200 + error 필드(웹 쿼리 편집기 표시 유지). */
  @Test
  void analytics_syntaxAndNormalizationErrors_returnErrorField() {
    AnalyticsQueryResponse multi = gate.executeAnalytics(viewer, "SELECT 1; SELECT 2", 100, true);
    assertThat(multi.queryType()).isEqualTo("UNKNOWN");
    assertThat(multi.error()).isNotBlank();

    AnalyticsQueryResponse unparsable =
        gate.executeAnalytics(viewer, "SELECT FROM WHERE " + pub, 100, true);
    assertThat(unparsable.queryType()).isEqualTo("UNKNOWN");
    assertThat(unparsable.error()).isNotBlank();
  }

  /** 데이터셋 /query 오류 계약 — 정규화 실패는 기존과 같이 예외(400). */
  @Test
  void datasetQuery_normalizationError_throws() {
    asUser(userId);
    assertThatThrownBy(
            () ->
                datasetDataService.executeQuery(
                    pubId, new SqlQueryRequest("SELECT 1; SELECT 2", 100), userId))
        .isInstanceOf(SqlQueryException.class);
  }

  /**
   * 저장 쿼리 실행은 소유자가 아니라 <b>실행자</b> 자격으로 판정한다(스펙 §4.2 3행) — 공유된 쿼리가 숨김 데이터셋을 읽으면 자격 없는 실행자는 403. 양성
   * 대조로 같은 실행자가 볼 수 있는 테이블만 읽는 공유 쿼리는 실행된다.
   */
  @Test
  void savedQuery_isJudgedWithTheExecutorsClearance_notTheOwners() {
    // 소유자는 숨김(기밀·허용 목록) 데이터셋을 볼 수 있게 만든다 — 그래야 "소유자 자격으로 판정" 변이가 이 테스트를 깬다.
    long ownerRole = fx.createRole("gse_o_" + System.nanoTime(), fx.levelId("기밀"), "dataset:read");
    roles.add(ownerRole);
    fx.assignRole(creator, ownerRole);
    fx.grantUser(hiddenId, creator);
    assertThat(
            guard
                .checkSql(
                    clearanceResolver.resolve(creator),
                    "SELECT a FROM " + hidden,
                    SqlAccessMode.INTERACTIVE)
                .allowed())
        .as("전제: 소유자는 숨김 데이터셋을 볼 수 있다")
        .isTrue();

    long hiddenQuery = sharedQuery("SELECT a FROM " + hidden);
    long pubQuery = sharedQuery("SELECT a FROM " + pub);

    assertThatThrownBy(() -> savedQueryService.executeById(hiddenQuery, 100, true, userId))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");

    AnalyticsQueryResponse ok = savedQueryService.executeById(pubQuery, 100, true, userId);
    assertThat(ok.error()).isNull();
    assertThat(ok.rows()).hasSize(1);
  }

  /** 소유자(creator)가 만든 공유 저장 쿼리. */
  private long sharedQuery(String sql) {
    long id =
        savedQueryService
            .create(
                new CreateSavedQueryRequest(
                    "gse_" + System.nanoTime(), null, sql, null, null, true),
                creator)
            .id();
    savedQueries.add(id);
    return id;
  }

  /**
   * 웹 쿼리 편집기 경로(읽기 전용 애널리틱스)의 오류가 200 + error 필드로 원인별 한국어 메시지를 싣는다. 문구 상세는 SqlValidationUtilsTest·
   * SqlValidatorTest 가 본다 — 여기서는 경로 연결만 확인한다.
   */
  @Test
  void analytics_rejectedSql_returnsCauseSpecificErrorField() {
    AnalyticsQueryResponse typo = gate.executeAnalytics(viewer, "SELEC a FROM " + pub, 100, true);
    assertThat(typo.queryType()).isEqualTo("UNKNOWN");
    assertThat(typo.error()).startsWith("SQL 문법 오류").contains("'SELEC'");

    AnalyticsQueryResponse multi = gate.executeAnalytics(viewer, "SELECT 1; SELECT 2", 100, true);
    assertThat(multi.error()).startsWith("SQL 문장은 한 번에 하나만");

    AnalyticsQueryResponse cte =
        gate.executeAnalytics(
            viewer, "WITH d AS (DELETE FROM " + pub + " RETURNING *) SELECT * FROM d", 100, true);
    assertThat(cte.queryType()).isEqualTo("UNKNOWN");
    assertThat(cte.error()).startsWith("WITH 절 안에서").contains("DELETE");
    // 거부만 됐는지 — CTE 의 DELETE 가 실행되지 않았다(행이 그대로 남아 있다).
    assertThat(dsl.fetchOne("SELECT count(*) FROM " + schema + "." + pub).get(0, Integer.class))
        .isEqualTo(1);

    AnalyticsQueryResponse drop = gate.executeAnalytics(viewer, "DROP TABLE " + pub, 100, true);
    assertThat(drop.error()).startsWith("DROP 문은");
  }

  /** 요청 경로의 principal(Long userId)을 세운다 — ClearanceResolver.current() 가 읽는다. */
  private static void asUser(long uid) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(uid, null, List.of()));
  }

  // ---------------------------------------------------------------------------------------------
  // 지표 수집(WD-76) — 판정은 그대로, 실행만 Tibero 테넌트면 db-executor 읽기 전용 조회(한 행)로 한다. 판정 가드는 허용을 돌려주는
  // 목이다(판정 규칙 자체는 SqlAccessGuardTest 가 본다). 스프링 컨텍스트를 늘리지 않게 관문을 직접 만든다.
  // ---------------------------------------------------------------------------------------------

  /** 지표 관문과 협력자 목. tibero=true 면 실제 TiberoQueryService 에 Tibero 스키마·실행기 목을 꽂는다. */
  private record MetricSetup(
      GuardedSqlExecutor gate,
      ExecutorClient executorClient,
      DbExecutorClient dbExecutorClient,
      TiberoQueryService tibero) {}

  private static MetricSetup metricSetup(boolean tiberoTenant, String sql) {
    DatasetAccessGuard allowAll = mock(DatasetAccessGuard.class);
    when(allowAll.judgeSql(any(), eq(sql), eq(SqlAccessMode.INTERACTIVE)))
        .thenReturn(
            new DatasetAccessGuard.SqlVerdict(
                new SqlAccessResult(true, null, null, null, Set.of(), Set.of(), true), null));
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tiberoTenant);
    when(schema.validator()).thenReturn(SqlValidator.forTiberoSchema("GENIA_T5"));
    DbExecutorClient db = mock(DbExecutorClient.class);
    ExecutorClient executor = mock(ExecutorClient.class);
    TiberoQueryService tibero = spy(new TiberoQueryService(schema, db));
    GuardedSqlExecutor gate =
        new GuardedSqlExecutor(
            allowAll,
            mock(DataTableQueryService.class),
            mock(AnalyticsQueryExecutionService.class),
            executor,
            mock(SecurityAuditRecorder.class),
            tibero);
    return new MetricSetup(gate, executor, db, tibero);
  }

  /** Review Focus 4 — Tibero 지표: PG executor 를 부르지 않고 run(sql, 1). 1행이면 truncated=true, 첫 칸은 숫자. */
  @Test
  void metric_tiberoTenant_runsOnDbExecutorWithOneRow() {
    String sql = "SELECT COUNT(*) AS cnt FROM genia_t5.orders";
    MetricSetup m = metricSetup(true, sql);
    // db-executor 는 행 수가 max_rows 에 닿으면 truncated=true 로 답한다(행 상한 1 → 1행이면 true).
    when(m.dbExecutorClient().executeQuery(sql, 1))
        .thenReturn(
            new DbExecutorClient.QueryResult(
                true,
                List.of("cnt"),
                List.of(Map.of("cnt", new java.math.BigDecimal("42"))),
                1,
                true,
                null,
                null));

    ExecutorClient.QueryExecuteResult r = m.gate().executeMetricQuery(null, NormalizedSql.of(sql));

    verify(m.tibero()).run(sql, 1);
    verifyNoInteractions(m.executorClient());
    assertThat(r.success()).isTrue();
    assertThat(r.queryType()).isEqualTo("SELECT");
    assertThat(r.columns()).containsExactly("cnt");
    assertThat(r.rowCount()).isEqualTo(1);
    assertThat(r.affectedRows()).isZero();
    assertThat(r.truncated()).isTrue();
    assertThat(r.error()).isNull();
    // 폴러의 기존 규칙(첫 행 첫 칸 Number → doubleValue)으로 변환된다.
    Object firstCell = r.rows().get(0).values().iterator().next();
    assertThat(firstCell).isInstanceOf(Number.class);
    assertThat(((Number) firstCell).doubleValue()).isEqualTo(42.0);
  }

  /** Review Focus 4 — 0행이면 truncated=false, 성공이지만 rows 가 비어 폴러가 "no data" 로 건너뛴다. */
  @Test
  void metric_tiberoTenant_zeroRows_isNotTruncated() {
    String sql = "SELECT amount FROM genia_t5.orders WHERE 1 = 0";
    MetricSetup m = metricSetup(true, sql);
    when(m.dbExecutorClient().executeQuery(sql, 1))
        .thenReturn(
            new DbExecutorClient.QueryResult(
                true, List.of("amount"), List.of(), 0, false, null, null));

    ExecutorClient.QueryExecuteResult r = m.gate().executeMetricQuery(null, NormalizedSql.of(sql));

    assertThat(r.success()).isTrue();
    assertThat(r.rows()).isEmpty();
    assertThat(r.rowCount()).isZero();
    assertThat(r.truncated()).isFalse();
    verifyNoInteractions(m.executorClient());
  }

  /** Tibero 지표 실패는 success=false + 사용자 문구 — PG 로 폴백하지 않는다. */
  @Test
  void metric_tiberoTenant_failure_isNotSuccess_andNoPgFallback() {
    String sql = "SELECT COUNT(*) FROM genia_t5.orders";
    MetricSetup m = metricSetup(true, sql);
    when(m.dbExecutorClient().executeQuery(sql, 1))
        .thenThrow(new ScriptExecutionException("Tibero 실행기에 연결하지 못했습니다."));

    ExecutorClient.QueryExecuteResult r = m.gate().executeMetricQuery(null, NormalizedSql.of(sql));

    assertThat(r.success()).isFalse();
    assertThat(r.error()).isEqualTo("Tibero 실행기에 연결하지 못했습니다.");
    assertThat(r.rows()).isEmpty();
    verifyNoInteractions(m.executorClient());
  }

  /** PG 테넌트 지표는 기존대로 Python executor(readOnly, 한 행) — Tibero 조회는 부르지 않는다. */
  @Test
  void metric_pgTenant_keepsExecutorPath() {
    String sql = "SELECT COUNT(*) FROM data.orders";
    MetricSetup m = metricSetup(false, sql);
    ExecutorClient.QueryExecuteResult pgResult =
        new ExecutorClient.QueryExecuteResult(
            true, "SELECT", List.of("count"), List.of(Map.of("count", 3)), 1, 0, 5L, true, null);
    when(m.executorClient().executeQuery(sql, 1, true)).thenReturn(pgResult);

    assertThat(m.gate().executeMetricQuery(null, NormalizedSql.of(sql))).isSameAs(pgResult);
    verify(m.tibero(), never()).run(anyString(), anyInt());
    verifyNoInteractions(m.dbExecutorClient());
  }

  // ---------------------------------------------------------------------------------------------
  // Tibero 애널리틱스는 PG 트랜잭션 밖에서 실행한다(WD-76 F6). @Transactional execute 안에서 db-executor 를 부르면
  // TenantAwareTransactionManager 가 잡은 PG 커넥션을 응답(최대 35초) 내내 쥔다. 실제 @Transactional 프록시(앱의 @Primary
  // 트랜잭션
  // 매니저 + 어노테이션 속성)로 분석 실행 서비스를 감싸, 프록시를 지났다면 트랜잭션이 보이는 조건에서 확인한다.
  // ---------------------------------------------------------------------------------------------

  /** 트랜잭션 프록시로 감싼 분석 실행 서비스와 관문. txSeen 은 실행 지점에서 본 "실제 트랜잭션 진행 중" 여부. */
  private record AnalyticsTxSetup(
      GuardedSqlExecutor gate,
      AnalyticsQueryExecutionService target,
      AnalyticsQueryExecutionService proxied,
      DbExecutorClient dbExecutorClient,
      TiberoQueryService tibero,
      List<Boolean> txSeen) {}

  private AnalyticsTxSetup analyticsTxSetup(boolean tiberoTenant) {
    DatasetAccessGuard allowAll = mock(DatasetAccessGuard.class);
    when(allowAll.judgeSql(any(), anyString(), eq(SqlAccessMode.INTERACTIVE)))
        .thenReturn(
            new DatasetAccessGuard.SqlVerdict(
                new SqlAccessResult(true, null, null, null, Set.of(), Set.of(), true), null));
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tiberoTenant);
    when(schema.validator()).thenReturn(SqlValidator.forTiberoSchema("GENIA_T5"));
    List<Boolean> txSeen = new ArrayList<>();
    DbExecutorClient db = mock(DbExecutorClient.class);
    when(db.executeQuery(anyString(), anyInt()))
        .thenAnswer(
            inv -> {
              txSeen.add(TransactionSynchronizationManager.isActualTransactionActive());
              return new DbExecutorClient.QueryResult(
                  true, List.of("a"), List.of(Map.of("a", 1)), 1, false, null, null);
            });
    TiberoQueryService tibero = spy(new TiberoQueryService(schema, db));
    AnalyticsQueryExecutionService target =
        spy(new AnalyticsQueryExecutionService(dsl, mock(ExecutorClient.class), tibero));
    ProxyFactory pf = new ProxyFactory(target);
    pf.setProxyTargetClass(true);
    pf.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AnalyticsQueryExecutionService proxied = (AnalyticsQueryExecutionService) pf.getProxy();
    GuardedSqlExecutor gate =
        new GuardedSqlExecutor(
            allowAll,
            mock(DataTableQueryService.class),
            proxied,
            mock(ExecutorClient.class),
            mock(SecurityAuditRecorder.class),
            tibero);
    return new AnalyticsTxSetup(gate, target, proxied, db, tibero, txSeen);
  }

  /** Tibero 테넌트: 관문은 판정 뒤 트랜잭션 없는 진입으로 실행한다 — db-executor 호출 시점에 PG 트랜잭션이 없다. */
  @Test
  void analytics_tiberoTenant_runsOutsidePgTransaction() {
    String sql = "SELECT A FROM GENIA_T5.T";
    AnalyticsTxSetup t = analyticsTxSetup(true);

    AnalyticsQueryResponse r =
        t.gate().executeJudgedAnalytics(t.gate().judgeAnalytics(viewer, sql), 100, true);

    assertThat(r.error()).isNull();
    assertThat(r.rows()).hasSize(1);
    assertThat(t.txSeen()).containsExactly(false);
    verify(t.target(), never()).execute(any(NormalizedSql.class), anyInt(), anyBoolean());
  }

  /** 대조군: 같은 구성에서 @Transactional execute 로 들어가면 트랜잭션이 보인다(위 테스트가 트랜잭션을 감지할 수 있음을 보인다). */
  @Test
  void analytics_tiberoTenant_viaTransactionalExecute_wouldHoldTransaction() {
    AnalyticsTxSetup t = analyticsTxSetup(true);

    t.proxied().execute(NormalizedSql.of("SELECT A FROM GENIA_T5.T"), 100, true);

    assertThat(t.txSeen()).containsExactly(true);
  }

  /** PG 테넌트: 기존대로 @Transactional execute(트랜잭션 안)로 실행하고 Tibero 조회는 부르지 않는다. */
  @Test
  void analytics_pgTenant_keepsTransactionalExecute() {
    AnalyticsTxSetup t = analyticsTxSetup(false);
    AnalyticsQueryResponse canned =
        new AnalyticsQueryResponse("SELECT", List.of("a"), List.of(), 0, 0L, 0, false, null);
    doAnswer(
            inv -> {
              t.txSeen().add(TransactionSynchronizationManager.isActualTransactionActive());
              return canned;
            })
        .when(t.target())
        .execute(any(NormalizedSql.class), anyInt(), anyBoolean());

    AnalyticsQueryResponse r =
        t.gate()
            .executeJudgedAnalytics(
                t.gate().judgeAnalytics(viewer, "SELECT a FROM data.t"), 100, true);

    assertThat(r).isSameAs(canned);
    assertThat(t.txSeen()).containsExactly(true);
    verify(t.target()).execute(any(NormalizedSql.class), eq(100), eq(true));
    verify(t.tibero(), never()).run(anyString(), anyInt());
    verifyNoInteractions(t.dbExecutorClient());
  }
}
