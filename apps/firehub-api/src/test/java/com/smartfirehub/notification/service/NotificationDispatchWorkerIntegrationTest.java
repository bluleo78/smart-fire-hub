package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Dispatcher → Worker → ChatChannel 전체 흐름 통합 검증. LISTEN/NOTIFY는 테스트에서 비활성화(다른 프로세스의 listener 간섭
 * 회피), 워커를 직접 호출한다.
 */
@TestPropertySource(
    properties = {
      "notification.outbox.enabled=true",
      "notification.worker.listen_notify=false",
      "notification.worker.poll_interval_ms=500"
    })
class NotificationDispatchWorkerIntegrationTest extends IntegrationTestBase {

  @Autowired private NotificationDispatcher dispatcher;
  @Autowired private NotificationDispatchWorker worker;
  @Autowired private NotificationOutboxRepository repo;
  @Autowired private DSLContext dsl;

  @Test
  void enqueueChatRequest_workerSendsAndMarksSent() {
    long userId = createTestUser();
    UUID corr = UUID.randomUUID();
    dispatcher.enqueue(chatRequest(userId, corr));

    // 워커를 직접 호출해 즉시 deliver
    worker.runOneBatch();

    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              var rows = repo.findByCorrelation(corr);
              assertThat(rows).hasSize(1);
              assertThat(rows.get(0).status()).isEqualTo("SENT");
            });
  }

  /** 테스트용 사용자 생성 — 충돌 회피를 위해 nanoTime으로 unique 이메일. */
  private long createTestUser() {
    long ts = System.nanoTime();
    return dsl.insertInto(USER)
        .set(USER.USERNAME, "workeruser_" + ts)
        .set(USER.PASSWORD, "password")
        .set(USER.NAME, "Worker Test User")
        .set(USER.EMAIL, "worker_" + ts + "@example.com")
        .returning(USER.ID)
        .fetchOne()
        .getId();
  }

  private NotificationRequest chatRequest(long userId, UUID corr) {
    return new NotificationRequest(
        "TEST_WORKER",
        null,
        userId,
        corr,
        new Payload(
            Payload.PayloadType.STANDARD,
            "워커 테스트",
            "요약",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of()),
        null,
        List.of(new Recipient(userId, null, EnumSet.of(ChannelType.CHAT))));
  }
}
