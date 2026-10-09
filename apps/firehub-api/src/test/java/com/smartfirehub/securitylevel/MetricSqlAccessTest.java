package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.QueryExecuteResult;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.service.MetricPollerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 스펙 §4.2 6행 + 판단 사항 9: 메트릭 SQL 은 생성·수정 시 작성자 기준, 폴링 시 소유자 기준. 폴링 수집 값은 이상 감지 시 리포트로 외부 LLM·발송에
 * 실리므로(§4.2/§4.3) 폴링 실행은 VIEW 뒤에 AI(forShare 호스팅)+SHARE 도 통과해야 한다(WD-39).
 */
class MetricSqlAccessTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ProactiveJobService jobService;
  @Autowired private GuardedSqlExecutor guardedSqlExecutor;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private MetricPollerService poller;
  @Autowired private TenantSettingsRepository tenantSettings;
  @MockitoBean private ExecutorClient executorClient;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String pub;
  private String sec;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    long creator = fx.createUser("ms_c");
    users.add(creator);
    String m = "ms" + System.nanoTime();
    pub = m + "_pub";
    sec = m + "_sec";
    datasets.add(fx.createDatasetRow(pub, fx.levelId("공개"), creator));
    datasets.add(fx.createDatasetRow(sec, fx.levelId("민감"), creator));
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
  }

  /** 호스팅 선언 없음 = 외부 호스팅(기본). */
  private void clearHosting() {
    tenantSettings.delete(AiCredentialSlot.CHAT.key());
    tenantSettings.delete(EmbeddingConfigService.KEY);
  }

  /** 채팅·임베딩을 자체 호스팅으로 선언한다 — forShare 는 둘 다 자체 호스팅이어야 자체 호스팅이다. */
  private void declareSelfHosted() {
    tenantSettings.upsert(
        AiCredentialSlot.CHAT.key(),
        "{\"v\":1,\"agentType\":\"opencode\",\"payload\":{\"providerId\":\"corp\","
            + "\"baseURL\":\"http://10.0.0.5/v1\",\"hosting\":\"SELF_HOSTED\"},\"secret\":{}}",
        null);
    tenantSettings.upsert(
        EmbeddingConfigService.KEY,
        "{\"v\":1,\"provider\":\"OLLAMA\",\"model\":\"bge-m3\",\"baseUrl\":\"http://ollama:11434\","
            + "\"dimension\":1024,\"hosting\":\"SELF_HOSTED\",\"secret\":{\"apiKey\":\"\"}}",
        null);
  }

  private void stubExecutor() {
    reset(executorClient);
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean()))
        .thenReturn(
            new QueryExecuteResult(
                true, "SELECT", List.of("c"), List.of(Map.of("c", 1)), 1, 0, 0L, false, null));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
    for (long u : users) {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          DEFAULT_TEST_TENANT_ID,
          () -> dsl.execute("delete from proactive_job where user_id = ?", u));
    }
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private long userAt(String level) {
    long uid = fx.createUser("ms_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("ms_r_" + System.nanoTime(), fx.levelId(level), "proactive:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private static Map<String, Object> config(String table) {
    return Map.of(
        "anomaly",
        Map.of(
            "metrics",
            List.of(
                Map.of(
                    "id",
                    "m1",
                    "name",
                    "건수",
                    "source",
                    "dataset",
                    "query",
                    "SELECT count(*) FROM " + table))));
  }

  private CreateProactiveJobRequest create(String table) {
    return new CreateProactiveJobRequest(
        "ms-job", "보고", null, null, null, false, "ANOMALY", config(table));
  }

  @Test
  void create_withHiddenMetricTable_isRejected() {
    long u = userAt("공개");
    assertThatThrownBy(() -> jobService.createJob(create(sec), u))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertThat(jobService.createJob(create(pub), u).id()).isPositive();
  }

  @Test
  void update_cannotSwapInHiddenTable() {
    long u = userAt("공개");
    long jobId = jobService.createJob(create(pub), u).id();
    assertThatThrownBy(
            () ->
                jobService.updateJob(
                    jobId,
                    new UpdateProactiveJobRequest(
                        null, null, null, null, null, null, null, config(sec)),
                    u))
        .isInstanceOf(CodedApiException.class);
  }

  /** 판단 사항 19 — 파싱 불가 메트릭 SQL 은 저장 계약 불변(폴러가 건너뛴다). 트랜잭션 오염으로 저장이 500 이 되면 안 된다. */
  @Test
  void create_withUnparseableMetricQuery_stillSaves() {
    long u = userAt("공개");
    var req =
        new CreateProactiveJobRequest(
            "ms-bad",
            "보고",
            null,
            null,
            null,
            false,
            "ANOMALY",
            Map.of(
                "anomaly",
                Map.of(
                    "metrics",
                    List.of(Map.of("id", "m1", "source", "dataset", "query", "SELEC 1")))));
    assertThat(jobService.createJob(req, u).id()).isPositive();
  }

  /** 폴링 시점 판정은 실행 관문(GuardedSqlExecutor#executeMetricQuery)이 소유자 자격으로 한다 — 실제 관문을 직접 부른다. */
  @Test
  void pollTime_reJudgesOwner() {
    reset(executorClient);
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean()))
        .thenReturn(
            new QueryExecuteResult(
                true, "SELECT", List.of("c"), List.of(Map.of("c", 1)), 1, 0, 0L, false, null));
    NormalizedSql q = NormalizedSql.of("SELECT count(*) FROM " + sec);
    assertThatThrownBy(
            () -> guardedSqlExecutor.executeMetricQuery(clearanceResolver.resolve(userAt("공개")), q))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    verify(executorClient, never()).executeQuery(anyString(), anyInt(), anyBoolean());
    assertThat(
            guardedSqlExecutor
                .executeMetricQuery(clearanceResolver.resolve(userAt("민감")), q)
                .success())
        .isTrue();
  }

  /**
   * 폴러 판정은 사용자 요청이 아닌 내부 값 판정이다(설계 결정 3) — 30초마다 같은 거부가 반복되므로 감사하면 폭주·오탐이 된다. 거부(403)는 그대로 던지되
   * DATASET_ACCESS_DENIED 행은 남기지 않는다.
   */
  @Test
  void pollTimeDenial_isNotAudited() {
    reset(executorClient);
    long low = userAt("공개");
    NormalizedSql q = NormalizedSql.of("SELECT count(*) FROM " + sec);
    assertThatThrownBy(
            () -> guardedSqlExecutor.executeMetricQuery(clearanceResolver.resolve(low), q))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    awaitSecurityAudit();
    Integer n =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchOne(
                        "SELECT count(*) FROM audit_log WHERE user_id = ?"
                            + " AND action_type = 'DATASET_ACCESS_DENIED'",
                        low)
                    .get(0, Integer.class));
    assertThat(n).isZero();
  }

  /** 폴러 실행 경로 — 자격이 낮아진 소유자의 메트릭은 executor 로 가지 않고, 충분한 소유자는 간다. */
  @Test
  void poller_skipsDeniedOwner_andRunsAllowedOwner() {
    reset(executorClient);
    when(executorClient.executeQuery(anyString(), anyInt(), anyBoolean()))
        .thenReturn(
            new QueryExecuteResult(
                true, "SELECT", List.of("c"), List.of(Map.of("c", 1)), 1, 0, 0L, false, null));
    // 숨김 테이블 메트릭을 가진 "공개" 소유자 잡 — 생성 이후 자격이 낮아진 상황을 직접 삽입으로 재현한다.
    insertAnomalyJob(userAt("공개"), "SELECT count(*) FROM " + sec);

    poller.poll();
    verify(executorClient, never()).executeQuery(anyString(), anyInt(), anyBoolean());

    // 같은 SQL 이라도 자격이 충분한 소유자는 수집된다(판정이 SQL 이 아니라 소유자에 달렸음을 확인). 민감은 AI 가
    // SELF_HOSTED_ONLY 라 폴링의 공유 범위 판정을 통과하도록 자체 호스팅을 선언한다.
    declareSelfHosted();
    insertAnomalyJob(userAt("민감"), "SELECT count(*) FROM " + sec);
    poller.poll();
    verify(executorClient).executeQuery("SELECT count(*) FROM " + sec, 1, true);
  }

  /** WD-39: 외부 호스팅(선언 없음)에서는 볼 수 있는 민감 메트릭도 AI 불허라 executor 로 가지 않는다 — 저장 시 판정 없던 기존 잡 재현. */
  @Test
  void poller_externalHosting_skipsSensitiveMetric() {
    stubExecutor();
    insertAnomalyJob(userAt("민감"), "SELECT count(*) FROM " + sec);

    poller.poll();
    verify(executorClient, never()).executeQuery(anyString(), anyInt(), anyBoolean());
  }

  /** WD-39: 자체 호스팅이라 AI 는 통과해도 기밀(share_policy DENY)은 공유 불허라 수집하지 않는다. */
  @Test
  void poller_selfHosted_skipsShareDeniedMetric() {
    stubExecutor();
    declareSelfHosted();
    String conf = "ms" + System.nanoTime() + "_conf";
    long confId = fx.createDatasetRow(conf, fx.levelId("기밀"), users.get(0));
    datasets.add(confId);
    long owner = userAt("기밀");
    fx.grantUser(confId, owner); // 기밀은 허용 목록 필수 — VIEW 는 통과시킨다
    insertAnomalyJob(owner, "SELECT count(*) FROM " + conf);

    poller.poll();
    verify(executorClient, never()).executeQuery(anyString(), anyInt(), anyBoolean());
  }

  /** 대조군: 공개 메트릭은 외부 호스팅에서도 AI·SHARE 허용이라 수집된다. */
  @Test
  void poller_publicMetric_runsUnderExternalHosting() {
    stubExecutor();
    insertAnomalyJob(userAt("공개"), "SELECT count(*) FROM " + pub);

    poller.poll();
    verify(executorClient).executeQuery("SELECT count(*) FROM " + pub, 1, true);
  }

  /** 소유자가 ACTIVE 멤버십이 없으면(역할 제거) fail-closed 로 수집하지 않는다. */
  @Test
  void poller_ownerWithoutMembership_failsClosed() {
    reset(executorClient);
    long u = userAt("민감");
    // 모든 역할 멤버십 제거(removeUserRole 은 기본 USER 역할만 뗀다) — 자격 없음 = 아무것도 볼 수 없음.
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("delete from user_role where user_id = ?", u));
    insertAnomalyJob(u, "SELECT count(*) FROM " + pub);

    poller.poll();
    verify(executorClient, never()).executeQuery(anyString(), anyInt(), anyBoolean());
  }

  private void insertAnomalyJob(long ownerId, String query) {
    String cfg =
        "{\"anomaly\":{\"metrics\":[{\"id\":\"m1\",\"source\":\"dataset\",\"query\":\""
            + query
            + "\"}]}}";
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "insert into proactive_job(user_id,name,prompt,cron_expression,enabled,trigger_type,config)"
                    + " values (?,?,?,?,true,'ANOMALY',?::jsonb)",
                ownerId,
                "ms-poll",
                "p",
                "0 * * * *",
                cfg));
  }
}
