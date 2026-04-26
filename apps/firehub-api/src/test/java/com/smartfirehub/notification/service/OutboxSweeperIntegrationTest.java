package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** OutboxSweeper — claim 후 오래된 행을 PENDING으로 회복하는지 검증. */
@TestPropertySource(
    properties = {
      "notification.outbox.enabled=true",
      "notification.worker.zombie_age_minutes=0" // 강제 — 모든 SENDING을 좀비로 판정
    })
class OutboxSweeperIntegrationTest extends IntegrationTestBase {

  @Autowired private OutboxSweeper sweeper;
  @Autowired private NotificationOutboxRepository repo;
  @Autowired private DSLContext dsl;

  @Test
  void sweep_reclaimsZombiesToPending() {
    UUID corr = UUID.randomUUID();
    // 사전 조건: SENDING 행 하나를 claim 완료 후 클레임 시점을 과거로 조작
    repo.insertIfAbsent(sampleRow("key-sweep-" + corr, corr));
    repo.claimDue(10_000, "sweeper-test");
    dsl.update(NOTIFICATION_OUTBOX)
        .set(NOTIFICATION_OUTBOX.CLAIMED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10))
        .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(corr))
        .execute();

    sweeper.sweep();

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
