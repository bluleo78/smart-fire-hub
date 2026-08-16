package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * OutboxSweeper — claim 후 오래된 행을 PENDING으로 회복하는지 검증.
 *
 * <p><b>테넌트(P2-f)</b>: 스크래치 테넌트에서 픽스처를 만들고, 검증 대상인 {@code sweep()} 은
 * <b>컨텍스트를 비운 채</b> 부른다 — 프로덕션의 {@code @Scheduled} 스레드가 정확히 그 상태이기
 * 때문이다. 컨텍스트를 남겨 두고 부르면 "스위퍼가 스스로 테넌트를 순회하는가"라는 검증이 무의미해진다.
 */
@TestPropertySource(
    // 5개 알림 통합 테스트가 완전히 동일한 프로퍼티 집합을 공유한다 — 스프링 컨텍스트 캐시
    // 키가 프로퍼티 배열이라, 한 글자만 달라도 컨텍스트가 하나 더 뜬다. 컨텍스트마다 스케줄러
    // 스레드가 따로 도는데 그중 일부는 @MockitoBean 을 건드려, 컨텍스트 수가 늘면 무관한 테스트의
    // 스터빙과 경합해 전체 스위트에서만 재현되는 플레이크가 난다(실측: DataExportServiceExtTest).
    // 값 자체는 각 테스트가 필요로 하는 것의 합집합이며 서로 무해하다.
    properties = {
      "notification.outbox.enabled=true",
      // 세 스케줄러의 기동 1회 실행 지연(notification.scheduler.initial_delay_ms)은
      // application-test.yml 로 옮겼다 — 스위트 전체에 같은 값이 필요하고, 이 노브의 실소비자가
      // 테스트뿐이라 프로덕션 프로퍼티로 남길 이유가 없었다.
      "notification.worker.poll_interval_ms=3600000",
      "notification.worker.listen_notify=false",
      "notification.worker.zombie_age_minutes=0",
      "notification.retention.sent_days=3650",
      "notification.retention.permanent_failure_days=3650",
      // 주기 자체도 스위트 길이보다 길게 잡아 두 번째 실행이 아예 오지 않게 한다.
      "notification.metrics.refresh_interval_ms=3600000"
    })
class OutboxSweeperIntegrationTest extends IntegrationTestBase {

  @Autowired private OutboxSweeper sweeper;
  @Autowired private NotificationOutboxRepository repo;
  @Autowired private DSLContext dsl;

  private long tenantId;

  @BeforeEach
  void createScratchTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-sweep");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupScratchTenant() {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  @Test
  void sweep_reclaimsZombiesToPending() {
    UUID corr = UUID.randomUUID();
    // 사전 조건: SENDING 행 하나를 claim 완료 후 클레임 시점을 과거로 조작
    repo.insertIfAbsent(sampleRow("key-sweep-" + corr, corr));
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));
    repo.claimDue(10_000, "sweeper-test", tenantId);
    inTenantFixture(
        tenantId,
        () ->
            dsl.update(NOTIFICATION_OUTBOX)
                .set(
                    NOTIFICATION_OUTBOX.CLAIMED_AT,
                    OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10))
                .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(corr))
                .execute());

    // 배경 스레드 재현 — 컨텍스트 없이 부른다. 스위퍼가 스스로 테넌트를 찾아 스코프를 열어야 한다.
    TenantContext.clear();
    sweeper.sweep();
    TenantContext.set(tenantId); // 검증 조회를 위해 복구

    var rows = repo.findByCorrelation(corr);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).status()).isEqualTo("PENDING");
  }

  private NotificationOutboxRow sampleRow(String key, UUID corr) {
    return new NotificationOutboxRow(
        null,
        key,
        corr,
        "TEST_SWEEP",
        null,
        ChannelType.CHAT,
        1L,
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
