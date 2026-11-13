package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.smartfirehub.global.platformdb.mybatis.LongTextAssertions;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 스펙 4장·5장 OutboxRepository 라이프사이클 통합 검증.
 *
 * <p><b>테넌트(P2-f)</b>: 기본 테넌트(1)가 아니라 <b>이 테스트가 직접 만든 테넌트</b>에서 돈다. 공유 테스트 DB 의 {@code
 * notification_outbox} 3883행이 전부 tenant_id=1 이라, 기본 테넌트로 정리하면 다른 세션이 만든 행까지 지운다({@code
 * deleteChannelCascade} javadoc 참조). 스크래치 테넌트를 쓰면 (a) 정리가 안전해지고 (b) {@code tenant_id} 값 자체를 단언할 수
 * 있어 정책이 켜지기 전에도 배선 판별력이 생긴다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485).
 */
public abstract class NotificationOutboxRepositoryContractTest extends IntegrationTestBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract NotificationOutboxRepository repository();

  @Autowired private DSLContext dsl;

  private long tenantId;

  /** 재투입 시험에서 id → 상관 id (리포지토리에 id 조회가 없어 상관 id 로 다시 읽는다). */
  private final Map<Long, UUID> corrById = new HashMap<>();

  @BeforeEach
  void createScratchTenant() {
    // IntegrationTestBase 의 @BeforeEach 가 먼저 기본 테넌트를 세우므로 여기서 덮어쓴다.
    tenantId = createTenant("outbox-repo");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupScratchTenant() {
    cleanupTenant(tenantId);
  }

  // ── 픽스처 seam(WD-487) ── 테넌트 만들기·지우기·원시 갱신·원시 조회를 아래 protected 메서드로 모은다. 기본 구현은 PG(지금 코드
  // 그대로)이고 Tibero 하위 클래스가 Tibero DataSource 위에서 같은 의미로 다시 구현한다. 테스트 본문의 단언은 바꾸지 않는다.

  /** 활성 테넌트를 새로 만들고 id 를 돌려준다. */
  protected long createTenant(String slugPrefix) {
    return TenantRlsTestSupport.createActiveTenant(dsl, slugPrefix);
  }

  /** 테넌트의 outbox 행(채널 도메인)과 테넌트 자체를 지운다. 지우는 대상은 내가 만든 테넌트뿐. */
  protected void cleanupTenant(long id) {
    // RLS 대상 테이블이므로 테넌트 컨텍스트 + 트랜잭션 안에서 지운다.
    inTenantFixture(id, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, id));
    TenantRlsTestSupport.deleteTenants(dsl, id);
  }

  /** 행 없이 테넌트만 지운다(다른 테넌트 시험용). */
  protected void deleteTenant(long id) {
    TenantRlsTestSupport.deleteTenants(dsl, id);
  }

  /** 상관 id 의 행을 DB 시계 기준 "지금 due" 로 당긴다({@link TenantRlsTestSupport#makeOutboxRowDue} 참고). */
  protected void makeRowDue(UUID correlationId) {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, correlationId));
  }

  /** 상관 id 행의 tenant_id 원시 조회. */
  protected Long stampedTenantOf(UUID correlationId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(NOTIFICATION_OUTBOX.TENANT_ID)
                .from(NOTIFICATION_OUTBOX)
                .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(correlationId))
                .fetchOne(NOTIFICATION_OUTBOX.TENANT_ID));
  }

  /** 상관 id 행의 last_error 원시 조회 — 행 레코드에 last_error 가 없어 직접 읽는다(WD-728). */
  protected String lastErrorOf(UUID correlationId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(NOTIFICATION_OUTBOX.LAST_ERROR)
                .from(NOTIFICATION_OUTBOX)
                .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(correlationId))
                .fetchOne(NOTIFICATION_OUTBOX.LAST_ERROR));
  }

  /**
   * 행 수준 보안(RLS)으로 다른 테넌트의 행이 가려지는지 시험할 수 있는가. PG(정책)·Tibero(DBMS_RLS, WD-492) 모두 true — 정책 없는 DB
   * 에서 돌리는 구현이 생기면 false 로 재정의한다.
   */
  protected boolean supportsRowLevelSecurity() {
    return true;
  }

  @Test
  void insertIfAbsent_idempotent() {
    UUID corr = UUID.randomUUID();
    var row = sampleRow("key-idem-" + corr, corr);
    repository().insertIfAbsent(row);
    repository().insertIfAbsent(row); // 두 번째는 UNIQUE 충돌 → DO NOTHING

    assertThat(repository().findByCorrelation(corr)).hasSize(1);
  }

  /**
   * 배선 판별력 — 리포지토리가 연 트랜잭션이 GUC 를 심어 {@code tenant_id} DEFAULT 가 <b>현재 컨텍스트</b>에서 채워지는지 직접 본다. 정책이
   * 아직 꺼져 있어 격리로는 검증할 수 없다.
   */
  @Test
  void insertIfAbsent_stampsCurrentTenant() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-tenant-" + corr, corr));

    Long stamped = stampedTenantOf(corr);

    assertThat(stamped).as("insertIfAbsent 가 GUC 파생 DEFAULT 로 현재 테넌트를 찍어야 한다").isEqualTo(tenantId);
  }

  /**
   * 멱등 키의 유일성 범위는 테넌트별이다(V106: uk_outbox_idempotency UNIQUE (tenant_id, idempotency_key)). 두 테넌트가
   * 같은 키로 넣으면 두 행 모두 생겨야 한다 — 키만으로 중복을 판정하면 뒤 테넌트의 알림이 조용히 사라진다. 상관 id 는 테넌트마다 달리 둬 각자의 행을 찾는다.
   */
  @Test
  void insertIfAbsent_sameKeyInTwoTenants_insertsBoth() {
    String key = "key-two-tenants-" + UUID.randomUUID();
    UUID corrA = UUID.randomUUID();
    UUID corrB = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow(key, corrA));

    long otherTenant = createTenant("outbox-samekey");
    try {
      TenantContext.runScopedGet(
          otherTenant,
          () -> {
            repository().insertIfAbsent(sampleRow(key, corrB));
            return null;
          });

      assertThat(repository().findByCorrelation(corrA)).as("먼저 넣은 테넌트의 행").hasSize(1);
      assertThat(
              TenantContext.runScopedGet(otherTenant, () -> repository().findByCorrelation(corrB)))
          .as("같은 키를 쓴 다른 테넌트의 행도 생겨야 한다")
          .hasSize(1);
    } finally {
      cleanupTenant(otherTenant);
    }
  }

  /**
   * 시각 왕복이 양방향으로 맞다 — 새 행의 next_attempt_at(DB DEFAULT, timestamptz 의미)이 지금과 몇 분 안이다. 다른 시각 단언은 한쪽
   * 방향뿐이라 9시간 어긋나도 통과하므로 이 테스트를 둔다. Tibero 는 UTC 벽시계 TIMESTAMP 라 팩토리 전역 UtcInstantTypeHandler 가
   * resultMap 에 적용돼야 통과한다(기본 핸들러는 KST JVM 에서 9시간 이르게 읽는다, 실측).
   */
  @Test
  void insertIfAbsent_nextAttemptAtDefault_readsAsCurrentInstant() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-clock-" + corr, corr));

    Instant nextAttemptAt = repository().findByCorrelation(corr).get(0).nextAttemptAt();
    assertThat(java.time.Duration.between(nextAttemptAt, Instant.now()).abs())
        .as("next_attempt_at 이 시간대 해석 오류 없이 지금 시각으로 읽혀야 한다")
        .isLessThan(java.time.Duration.ofMinutes(5));
  }

  /** 상태를 하나도 주지 않으면 빈 목록이다(DB 오류가 아니다) — Tibero 문장의 IN () 구문 오류를 구현이 막는지 본다. */
  @Test
  void tenantIdsWithStatus_noStatuses_returnsEmpty() {
    assertThat(repository().tenantIdsWithStatus()).isEmpty();
  }

  /** {@code outbox_tenant_ids} definer 함수가 내 테넌트를 실제로 돌려주는지 — 배경 경로 순회의 전제. */
  @Test
  void tenantIdsWithStatus_findsTenantWithPendingRow() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-ids-" + corr, corr));

    assertThat(repository().tenantIdsWithStatus("PENDING")).contains(tenantId);
    // 상태 인자로 용도가 갈리는지도 함께 본다(SENT 행은 아직 없다).
    assertThat(repository().tenantIdsWithStatus("SENT")).doesNotContain(tenantId);
  }

  /**
   * 배경 경로(디스패치·스위퍼·보존 정리)는 {@code TenantContext} 없이 부른다 — 정책을 넘는 함수가 아니라 일반 조회면 RLS 가 0행을 줘 일감을 영영
   * 못 찾는다(Tibero 일반 SELECT 가 그랬다, WD-706). 컨텍스트를 지운 채 내 테넌트가 나오는지 본다.
   */
  @Test
  void tenantIdsWithStatus_withoutTenantContext_findsTenant() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-ids-noctx-" + corr, corr));

    TenantContext.clear();
    try {
      assertThat(repository().tenantIdsWithStatus("PENDING")).contains(tenantId);
    } finally {
      TenantContext.set(tenantId);
    }
  }

  @Test
  void claimDue_marksSendingAndReturnsRows() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-claim-" + corr, corr));
    makeRowDue(corr);

    var claimed = repository().claimDue(10_000, "test-instance", tenantId);
    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).status()).isEqualTo("SENDING");
    assertThat(claimed.get(0).correlationId()).isEqualTo(corr);
  }

  /**
   * SKIP LOCKED 동시성 — 두 스레드가 같은 행을 두 번 잡지 않는다.
   *
   * <p><b>P2-f 로 단언을 조였다.</b> 이전에는 풀 스레드에 {@code TenantContext} 가 없어(ThreadLocal 은 승계되지 않는다) 정책이
   * 켜지면 두 스레드 모두 0행을 보게 되는데도 {@code ≤1} 이 그대로 통과해 단언이 공허해지는 구조였다. 이제 각 스레드가 스스로 스코프를 열고(워커가 하는 것과
   * 같다), <b>정확히 1</b>을 요구한다 — 0이면 컨텍스트 배선이 깨진 것이고 2면 SKIP LOCKED 계약이 깨진 것이다. 스크래치 테넌트라 이 행 말고는 클레임
   * 대상이 없어 결정적이다.
   */
  @Test
  void claimDue_skipLockedConcurrent() throws Exception {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-skiplocked-" + corr, corr));
    makeRowDue(corr);

    var pool = Executors.newFixedThreadPool(2);
    try {
      var f1 =
          CompletableFuture.supplyAsync(
              () ->
                  TenantContext.runScopedGet(
                      tenantId, () -> repository().claimDue(10_000, "i1", tenantId)),
              pool);
      var f2 =
          CompletableFuture.supplyAsync(
              () ->
                  TenantContext.runScopedGet(
                      tenantId, () -> repository().claimDue(10_000, "i2", tenantId)),
              pool);
      long thisCorrClaimed =
          f1.get().stream().filter(r -> r.correlationId().equals(corr)).count()
              + f2.get().stream().filter(r -> r.correlationId().equals(corr)).count();
      assertThat(thisCorrClaimed)
          .as("정확히 한 스레드만 이 행을 잡아야 한다 (0 이면 컨텍스트 배선 결함, 2 면 SKIP LOCKED 결함)")
          .isEqualTo(1);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void markSent_setsStatus() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-sent-" + corr, corr));

    long id = repository().findByCorrelation(corr).get(0).id();
    repository().markSent(id, "ext-123");

    var rows = repository().findByCorrelation(corr);
    assertThat(rows.get(0).status()).isEqualTo("SENT");
  }

  @Test
  void reclaimZombies_resetsLongClaimedRows() {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-zombie-" + corr, corr));
    makeRowDue(corr);
    repository().claimDue(10_000, "i", tenantId);

    int reclaimed = repository().reclaimZombies(Instant.now().plusSeconds(60)); // 강제 cutoff 미래
    assertThat(reclaimed).isGreaterThanOrEqualTo(1);
    assertThat(repository().findByCorrelation(corr).get(0).status()).isEqualTo("PENDING");
  }

  /** 발송 오류는 외부 채널 응답·예외 문구라 길이를 통제할 수 없다 — Tibero 바이트 한도를 넘어도 백오프 기록은 남아야 한다(WD-728). */
  @Test
  @DisplayName("last_error 에 한도+1 바이트 한글 값을 넣어도 저장되고 한도 안으로 잘린다")
  void rescheduleTransient_overLimitError_isStoredAsPrefix() {
    long id = insertRow("long-error");
    String error = LongTextAssertions.overVarchar2Limit();

    repository().rescheduleTransient(id, 1, Instant.now().plusSeconds(3600), error);

    assertThat(rowById(id).attemptCount()).isEqualTo(1);
    LongTextAssertions.assertStoredPrefix(lastErrorOf(corrById.get(id)), error);
  }

  /**
   * WD-433: 수동 재투입은 영구 실패·대기 행만 되돌린다. 이미 보낸(SENT)·취소한(CANCELLED) 행을 되돌리면 같은 알림이 한 번 더 나가고, 보내는
   * 중(SENDING) 행을 되돌리면 두 워커가 같은 행을 보낼 수 있다 — 그런 행은 0행이고 상태가 그대로다.
   */
  @Test
  void requeueForRetry_onlyPermanentFailureOrPending() {
    long failedId = insertRow("failed");
    repository().markPermanentFailure(failedId, "TEST", "boom");

    long sentId = insertRow("sent");
    repository().markSent(sentId, "ext-1");

    UUID sendingCorr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-requeue-sending-" + sendingCorr, sendingCorr));
    makeRowDue(sendingCorr);
    long sendingId =
        repository().claimDue(10_000, "i-requeue", tenantId).stream()
            .filter(r -> sendingCorr.equals(r.correlationId()))
            .findFirst()
            .orElseThrow()
            .id();
    corrById.put(sendingId, sendingCorr);

    assertThat(repository().requeueForRetry(failedId)).isEqualTo(1);
    var failed = rowById(failedId);
    assertThat(failed.status()).isEqualTo("PENDING");
    assertThat(failed.attemptCount()).isZero();

    // 백오프 중인 대기 행은 다음 시도만 당기고 시도 횟수는 그대로 — 되돌릴 때마다 최대 시도 제한이 풀리지 않게.
    long backoffId = insertRow("backoff");
    repository().rescheduleTransient(backoffId, 3, Instant.now().plusSeconds(3600), "slow");
    assertThat(repository().requeueForRetry(backoffId)).isEqualTo(1);
    var backoff = rowById(backoffId);
    assertThat(backoff.attemptCount()).isEqualTo(3);
    assertThat(backoff.nextAttemptAt()).isBefore(Instant.now().plusSeconds(60));

    assertThat(repository().requeueForRetry(sentId)).isZero();
    assertThat(rowById(sentId).status()).isEqualTo("SENT");

    assertThat(repository().requeueForRetry(sendingId)).isZero();
    assertThat(rowById(sendingId).status()).isEqualTo("SENDING");

    assertThat(repository().requeueForRetry(Long.MAX_VALUE)).as("없는 id").isZero();
  }

  /**
   * WD-433: 관리 API 는 워크스페이스 관리자 권한(notification:admin)으로 열리므로 다른 워크스페이스의 알림을 id 로 다시 보낼 수 없어야 한다 —
   * 리포지토리 트랜잭션이 심는 테넌트 GUC(RLS)가 그 경계다. 정체 목록 쪽 경계는
   * ChannelDomainRlsTest#findStuckPendingIsScopedByPolicy 가 지킨다.
   */
  @Test
  void requeueForRetry_cannotTouchOtherTenantRow() {
    assumeTrue(supportsRowLevelSecurity(), "RLS 정책 없는 DB — 다른 테넌트 행 격리는 시험 대상이 아니다");
    long id = insertRow("tenant");
    repository().markPermanentFailure(id, "TEST", "boom");

    long otherTenant = createTenant("outbox-other");
    try {
      assertThat(TenantContext.runScopedGet(otherTenant, () -> repository().requeueForRetry(id)))
          .isZero();
    } finally {
      deleteTenant(otherTenant);
    }
    assertThat(rowById(id).status()).isEqualTo("PERMANENT_FAILURE");
  }

  /** 재투입 시험용 행 하나를 넣고 id 를 돌려준다. 행은 상관 id 로 다시 찾을 수 있다({@link #rowById}). */
  private long insertRow(String label) {
    UUID corr = UUID.randomUUID();
    repository().insertIfAbsent(sampleRow("key-requeue-" + label + "-" + corr, corr));
    long id = repository().findByCorrelation(corr).get(0).id();
    corrById.put(id, corr);
    return id;
  }

  /** {@link #insertRow}·claimDue 로 얻은 id 의 현재 행. */
  private NotificationOutboxRepository.NotificationOutboxRow rowById(long id) {
    return repository().findByCorrelation(corrById.get(id)).get(0);
  }

  private NotificationOutboxRepository.NotificationOutboxRow sampleRow(String key, UUID corr) {
    return new NotificationOutboxRepository.NotificationOutboxRow(
        null,
        key,
        corr,
        "TEST_EVENT",
        null,
        ChannelType.CHAT,
        null,
        null,
        null,
        null,
        "{\"t\":\"x\"}",
        "STANDARD",
        "PENDING",
        0,
        Instant.now());
  }
}
