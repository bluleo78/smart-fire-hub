package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V104 가 켠 프로액티브 5테이블 + AI 2테이블의 테넌트 격리를 양방향으로 검증한다.
 *
 * <p>왜 양방향인가: "다른 테넌트에서 0행" 단방향 단언은 빈 테이블에서 공허하게 통과한다(P1 에서
 * 실제로 결함을 통과시킨 전례가 있다). 소유 테넌트에서 실제로 보이는 것 + {@code tenant_id} DEFAULT
 * 가 GUC 에서 채워지는 것을 함께 확인해야 의미가 있다({@code assertTwoSidedIsolation} 의 3다리).
 *
 * <p>이 클래스에 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러
 * 트랜잭션을 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다. 더 중요하게는
 * 테스트 트랜잭션이 GUC 를 공급해 프로덕션 배선 결함을 영구히 가린다.
 *
 * <p>테스트 커넥션은 비특권 롤 {@code app_tenant}(NOBYPASSRLS, V83)로 접속한다 — 픽스처 생성·정리·
 * 검증 조회에도 정책이 적용되므로 그 셋 모두 테넌트 트랜잭션 안에서 한다.
 *
 * <p>공유 테스트 DB 라 전체 카운트 비교 단언은 쓸 수 없다(다른 세션이 동시에 쓴다). 실행마다 고유한
 * 테넌트 두 개를 만들어 그 범위에서만 단언한다.
 */
class ProactiveAiRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  private long tenantA;
  private long tenantB;
  private Long userA;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "proai-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "proai-b");
    // proactive_job / proactive_message / ai_session 이 user FK 를 요구한다.
    // "user" 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만든다.
    userA = TenantRlsTestSupport.insertUser(dsl, "proai");
  }

  @AfterEach
  void tearDown() {
    // RLS 스코프 안에서 지운다 — 밖에서 지우면 0행이 되어 픽스처가 조용히 누적된다.
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    // ai_session 의 user FK 는 CASCADE 가 아니므로 위 정리가 먼저 성공해야 한다.
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  private void deleteOwnRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantId, () -> TenantRlsTestSupport.deleteProactiveAiCascade(dsl, tenantId));
  }

  // ── 7테이블 양방향 격리 ─────────────────────────────────────────────────

  @Test
  @DisplayName("proactive_job 은 테넌트 간 양방향으로 격리된다")
  void proactiveJobIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "proactive_job", this::insertJob);
  }

  @Test
  @DisplayName("proactive_job_execution 은 테넌트 간 양방향으로 격리된다")
  void executionIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "proactive_job_execution", () -> insertExecution(insertJob()));
  }

  @Test
  @DisplayName("proactive_message 는 테넌트 간 양방향으로 격리된다")
  void messageIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "proactive_message", () -> insertMessage(null));
  }

  @Test
  @DisplayName("metric_snapshot 은 테넌트 간 양방향으로 격리된다")
  void metricSnapshotIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "metric_snapshot", () -> insertMetricSnapshot(insertJob()));
  }

  @Test
  @DisplayName("anomaly_event 는 테넌트 간 양방향으로 격리된다")
  void anomalyEventIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "anomaly_event", () -> insertAnomalyEvent(insertJob()));
  }

  @Test
  @DisplayName("ai_session 은 테넌트 간 양방향으로 격리된다")
  void aiSessionIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "ai_session", () -> insertAiSession(newSessionId()));
  }

  @Test
  @DisplayName("ai_inference_cache 는 테넌트 간 양방향으로 격리된다")
  void inferenceCacheIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "ai_inference_cache", () -> insertCache(newRowHash()));
  }

  // ── 접은 유니크 / fail-closed / WITH CHECK ──────────────────────────────

  @Test
  @DisplayName("같은 session_id 를 두 테넌트가 각각 가질 수 있다 (V103 의 유니크 접기)")
  void sameSessionIdAllowedAcrossTenants() {
    // 유니크 인덱스는 RLS 와 무관하게 전역 적용된다 — V103 이 ai_session_session_id_key 를
    // (tenant_id, session_id) 로 접지 않았다면 여기서 "보이지도 않는 행"과 충돌해, 존재 여부가
    // 누출되고 정상 생성이 원인 불명으로 거부된다. 값을 고정해야 의미가 있다.
    String shared = newSessionId();

    Long a = TenantRlsTestSupport.runInTenantTransaction(tx, tenantA, () -> insertAiSession(shared));
    Long b = TenantRlsTestSupport.runInTenantTransaction(tx, tenantB, () -> insertAiSession(shared));

    assertThat(a).isNotNull();
    assertThat(b).isNotNull().isNotEqualTo(a);
  }

  @Test
  @DisplayName("같은 (row_hash, prompt_version) 을 두 테넌트가 각각 캐시할 수 있다 (R2 파티션)")
  void sameCacheKeyAllowedAcrossTenants() {
    String shared = newRowHash();

    Long a = TenantRlsTestSupport.runInTenantTransaction(tx, tenantA, () -> insertCache(shared));
    Long b = TenantRlsTestSupport.runInTenantTransaction(tx, tenantB, () -> insertCache(shared));

    assertThat(a).isNotNull();
    assertThat(b).isNotNull().isNotEqualTo(a);
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 proactive_job 도 보이지 않는다 (fail-closed)")
  void jobFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "proactive_job", this::insertJob);
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 proactive_job_execution 도 보이지 않는다 (fail-closed)")
  void executionFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "proactive_job_execution", () -> insertExecution(insertJob()));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 proactive_message 도 보이지 않는다 (fail-closed)")
  void messageFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "proactive_message", () -> insertMessage(null));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 metric_snapshot 도 보이지 않는다 (fail-closed)")
  void metricSnapshotFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "metric_snapshot", () -> insertMetricSnapshot(insertJob()));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 anomaly_event 도 보이지 않는다 (fail-closed)")
  void anomalyEventFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "anomaly_event", () -> insertAnomalyEvent(insertJob()));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 ai_session 도 보이지 않는다 (fail-closed)")
  void aiSessionFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "ai_session", () -> insertAiSession(newSessionId()));
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 ai_inference_cache 도 보이지 않는다 (fail-closed)")
  void cacheFailsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "ai_inference_cache", () -> insertCache(newRowHash()));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 proactive_job 을 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantJobInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "proactive_job",
        () ->
            dsl.execute(
                "insert into proactive_job (user_id, name, prompt, tenant_id)"
                    + " values (?, '교차삽입시도', 'p', ?)",
                userA,
                tenantB));
  }

  // 자식 4테이블의 교차삽입은 부모를 acting 테넌트(tenantA)에 두고 자식만 tenantB 로 심는다.
  // FK 무결성 검사는 RLS 를 우회하므로 부모 참조는 해결되고, 거부하는 주체가 정확히 자식 테이블의
  // WITH CHECK 라는 것이 드러난다. 부모까지 tenantB 로 두면 FK 냐 정책이냐가 섞여 판별력이 흐려진다.

  @Test
  @DisplayName("다른 테넌트 id 로 proactive_job_execution 을 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantExecutionInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "proactive_job_execution",
        () ->
            dsl.execute(
                "insert into proactive_job_execution (job_id, status, tenant_id)"
                    + " values (?, 'SUCCESS', ?)",
                insertJob(),
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 proactive_message 를 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantMessageInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "proactive_message",
        () ->
            dsl.execute(
                "insert into proactive_message (user_id, title, tenant_id) values (?, ?, ?)",
                userA,
                "교차삽입시도-" + TenantRlsTestSupport.nextTenantId(),
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 metric_snapshot 을 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantMetricSnapshotInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "metric_snapshot",
        () ->
            dsl.execute(
                "insert into metric_snapshot (job_id, metric_id, value, tenant_id)"
                    + " values (?, ?, 1.0, ?)",
                insertJob(),
                "m" + TenantRlsTestSupport.nextTenantId(),
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 anomaly_event 를 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantAnomalyEventInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "anomaly_event",
        () ->
            dsl.execute(
                "insert into anomaly_event"
                    + " (job_id, metric_id, metric_name, current_value, mean, stddev, deviation,"
                    + "  sensitivity, tenant_id)"
                    + " values (?, ?, '교차삽입시도', 10.0, 5.0, 1.0, 5.0, 'medium', ?)",
                insertJob(),
                "m" + TenantRlsTestSupport.nextTenantId(),
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 ai_session 을 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantAiSessionInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "ai_session",
        () ->
            dsl.execute(
                "insert into ai_session (user_id, session_id, tenant_id) values (?, ?, ?)",
                userA,
                newSessionId(),
                tenantB));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 ai_inference_cache 를 심으려 하면 WITH CHECK 가 거부한다")
  void crossTenantCacheInsertIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "ai_inference_cache",
        () ->
            dsl.execute(
                "insert into ai_inference_cache (row_hash, prompt_version, result_json, tenant_id)"
                    + " values (?, 'v1', '{}'::jsonb, ?)",
                newRowHash(),
                tenantB));
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────
  //
  // 전부 bare 삽입이다 — 호출자(assertTwoSidedIsolation 또는 runInTenantTransaction)가 이미 소유
  // 테넌트 트랜잭션을 열어 둔 안에서 실행된다. tenant_id 는 어디에서도 명시하지 않는다 — V103 이
  // 심은 컬럼 DEFAULT 가 GUC 에서 채우는 것을 함께 검증하기 위함이다.

  private static String newSessionId() {
    return "sess-" + TenantRlsTestSupport.nextTenantId();
  }

  private static String newRowHash() {
    return "hash-" + TenantRlsTestSupport.nextTenantId();
  }

  private Long insertJob() {
    return TenantRlsTestSupport.insertProactiveJob(dsl, userA, "격리검증잡");
  }

  private Long insertExecution(Long jobId) {
    return TenantRlsTestSupport.insertProactiveExecution(dsl, jobId);
  }

  /** execution_id 는 nullable 이다 — null 이면 수신자만으로 테넌트가 정해지는 행이 된다. */
  private Long insertMessage(Long executionId) {
    return (Long)
        dsl.fetchValue(
            "insert into proactive_message (user_id, execution_id, title) values (?, ?, ?)"
                + " returning id",
            userA,
            executionId,
            "격리검증메시지-" + TenantRlsTestSupport.nextTenantId());
  }

  private Long insertMetricSnapshot(Long jobId) {
    return (Long)
        dsl.fetchValue(
            "insert into metric_snapshot (job_id, metric_id, value) values (?, ?, 1.0)"
                + " returning id",
            jobId,
            "m" + TenantRlsTestSupport.nextTenantId());
  }

  private Long insertAnomalyEvent(Long jobId) {
    return (Long)
        dsl.fetchValue(
            "insert into anomaly_event"
                + " (job_id, metric_id, metric_name, current_value, mean, stddev, deviation,"
                + "  sensitivity)"
                + " values (?, ?, '격리 검증용', 10.0, 5.0, 1.0, 5.0, 'medium') returning id",
            jobId,
            "m" + TenantRlsTestSupport.nextTenantId());
  }

  private Long insertAiSession(String sessionId) {
    return (Long)
        dsl.fetchValue(
            "insert into ai_session (user_id, session_id) values (?, ?) returning id",
            userA,
            sessionId);
  }

  private Long insertCache(String rowHash) {
    return (Long)
        dsl.fetchValue(
            "insert into ai_inference_cache (row_hash, prompt_version, result_json)"
                + " values (?, 'v1', '{}'::jsonb) returning id",
            rowHash);
  }
}
