package com.smartfirehub.securitylevel.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.service.SavedQueryService;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

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

  /** 요청 경로의 principal(Long userId)을 세운다 — ClearanceResolver.current() 가 읽는다. */
  private static void asUser(long uid) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(uid, null, List.of()));
  }
}
