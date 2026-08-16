package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 스펙 4장·5장 OutboxRepository 라이프사이클 통합 검증.
 *
 * <p><b>테넌트(P2-f)</b>: 기본 테넌트(1)가 아니라 <b>이 테스트가 직접 만든 테넌트</b>에서 돈다. 공유
 * 테스트 DB 의 {@code notification_outbox} 3883행이 전부 tenant_id=1 이라, 기본 테넌트로 정리하면
 * 다른 세션이 만든 행까지 지운다({@code deleteChannelCascade} javadoc 참조). 스크래치 테넌트를 쓰면
 * (a) 정리가 안전해지고 (b) {@code tenant_id} 값 자체를 단언할 수 있어 정책이 켜지기 전에도 배선
 * 판별력이 생긴다.
 */
class NotificationOutboxRepositoryIntegrationTest extends IntegrationTestBase {

  @Autowired private NotificationOutboxRepository repo;
  @Autowired private DSLContext dsl;

  private long tenantId;

  @BeforeEach
  void createScratchTenant() {
    // IntegrationTestBase 의 @BeforeEach 가 먼저 기본 테넌트를 세우므로 여기서 덮어쓴다.
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-repo");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupScratchTenant() {
    // RLS 대상 테이블이므로 테넌트 컨텍스트 + 트랜잭션 안에서 지운다. 지우는 대상은 내가 만든 테넌트뿐.
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  @Test
  void insertIfAbsent_idempotent() {
    UUID corr = UUID.randomUUID();
    var row = sampleRow("key-idem-" + corr, corr);
    repo.insertIfAbsent(row);
    repo.insertIfAbsent(row); // 두 번째는 UNIQUE 충돌 → DO NOTHING

    assertThat(repo.findByCorrelation(corr)).hasSize(1);
  }

  /**
   * 배선 판별력 — 리포지토리가 연 트랜잭션이 GUC 를 심어 {@code tenant_id} DEFAULT 가 <b>현재
   * 컨텍스트</b>에서 채워지는지 직접 본다. 정책이 아직 꺼져 있어 격리로는 검증할 수 없다.
   */
  @Test
  void insertIfAbsent_stampsCurrentTenant() {
    UUID corr = UUID.randomUUID();
    repo.insertIfAbsent(sampleRow("key-tenant-" + corr, corr));

    Long stamped =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(NOTIFICATION_OUTBOX.TENANT_ID)
                    .from(NOTIFICATION_OUTBOX)
                    .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(corr))
                    .fetchOne(NOTIFICATION_OUTBOX.TENANT_ID));

    assertThat(stamped)
        .as("insertIfAbsent 가 GUC 파생 DEFAULT 로 현재 테넌트를 찍어야 한다")
        .isEqualTo(tenantId);
  }

  /** {@code outbox_tenant_ids} definer 함수가 내 테넌트를 실제로 돌려주는지 — 배경 경로 순회의 전제. */
  @Test
  void tenantIdsWithStatus_findsTenantWithPendingRow() {
    UUID corr = UUID.randomUUID();
    repo.insertIfAbsent(sampleRow("key-ids-" + corr, corr));

    assertThat(repo.tenantIdsWithStatus("PENDING")).contains(tenantId);
    // 상태 인자로 용도가 갈리는지도 함께 본다(SENT 행은 아직 없다).
    assertThat(repo.tenantIdsWithStatus("SENT")).doesNotContain(tenantId);
  }

  @Test
  void claimDue_marksSendingAndReturnsRows() {
    UUID corr = UUID.randomUUID();
    repo.insertIfAbsent(sampleRow("key-claim-" + corr, corr));
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));

    var claimed = repo.claimDue(10_000, "test-instance", tenantId);
    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).status()).isEqualTo("SENDING");
    assertThat(claimed.get(0).correlationId()).isEqualTo(corr);
  }

  /**
   * SKIP LOCKED 동시성 — 두 스레드가 같은 행을 두 번 잡지 않는다.
   *
   * <p><b>P2-f 로 단언을 조였다.</b> 이전에는 풀 스레드에 {@code TenantContext} 가 없어(ThreadLocal 은
   * 승계되지 않는다) 정책이 켜지면 두 스레드 모두 0행을 보게 되는데도 {@code ≤1} 이 그대로 통과해
   * 단언이 공허해지는 구조였다. 이제 각 스레드가 스스로 스코프를 열고(워커가 하는 것과 같다),
   * <b>정확히 1</b>을 요구한다 — 0이면 컨텍스트 배선이 깨진 것이고 2면 SKIP LOCKED 계약이 깨진 것이다.
   * 스크래치 테넌트라 이 행 말고는 클레임 대상이 없어 결정적이다.
   */
  @Test
  void claimDue_skipLockedConcurrent() throws Exception {
    UUID corr = UUID.randomUUID();
    repo.insertIfAbsent(sampleRow("key-skiplocked-" + corr, corr));
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));

    var pool = Executors.newFixedThreadPool(2);
    try {
      var f1 =
          CompletableFuture.supplyAsync(
              () -> TenantContext.runScopedGet(tenantId, () -> repo.claimDue(10_000, "i1", tenantId)),
              pool);
      var f2 =
          CompletableFuture.supplyAsync(
              () -> TenantContext.runScopedGet(tenantId, () -> repo.claimDue(10_000, "i2", tenantId)),
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
    repo.insertIfAbsent(sampleRow("key-sent-" + corr, corr));

    long id = repo.findByCorrelation(corr).get(0).id();
    repo.markSent(id, "ext-123");

    var rows = repo.findByCorrelation(corr);
    assertThat(rows.get(0).status()).isEqualTo("SENT");
  }

  @Test
  void reclaimZombies_resetsLongClaimedRows() {
    UUID corr = UUID.randomUUID();
    repo.insertIfAbsent(sampleRow("key-zombie-" + corr, corr));
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));
    repo.claimDue(10_000, "i", tenantId);

    int reclaimed = repo.reclaimZombies(Instant.now().plusSeconds(60)); // 강제 cutoff 미래
    assertThat(reclaimed).isGreaterThanOrEqualTo(1);
    assertThat(repo.findByCorrelation(corr).get(0).status()).isEqualTo("PENDING");
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
