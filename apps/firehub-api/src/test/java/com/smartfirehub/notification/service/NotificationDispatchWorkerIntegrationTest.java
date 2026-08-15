package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
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

  /** 이 테스트가 만든 사용자의 멤버십. ChatChannel 이 이 행에서 저장 테넌트를 해석한다. */
  private Long membershipUserId;

  @AfterEach
  void cleanupMembership() {
    // 내가 심은 행만 지운다(공유 테스트 DB). membership 은 전역 테이블이라 컨텍스트가 필요 없다.
    TenantRlsTestSupport.deleteMembership(dsl, membershipUserId);
  }

  @Test
  void enqueueChatRequest_workerSendsAndMarksSent() {
    long userId = createTestUser();
    UUID corr = UUID.randomUUID();
    dispatcher.enqueue(chatRequest(userId, corr));

    // 방금 넣은 행을 큐의 맨 앞으로 당긴다 — 내가 만든 행만 만진다.
    // 왜 필요한가: claimDue 는 next_attempt_at ASC 로 batch_size(20)건만 클레임하는데, 공유 테스트 DB
    // 에는 오래전 실패로 남은 due PENDING 행이 수천 건 쌓여 있다(재시도 소진 시 status 를
    // 'PERMANENT_FAILURE'(17자)로 쓰려다 varchar(16) 을 넘겨 예외가 나 배치가 통째로 중단됐기 때문).
    // 그 원인은 V105 에서 닫혔지만 이미 쌓인 행은 남의 행이라 지우지 않는다 — 그대로 두면 방금 넣은
    // 행은 영원히 클레임되지 않아 이 테스트가 무엇을 검증하든 PENDING 으로 관측된다.
    pullToFrontOfQueue(corr);

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

  /**
   * V105 회귀 가드 — {@code 'PERMANENT_FAILURE'}(17자)가 status 컬럼에 실제로 저장되는지 본다.
   *
   * <p>V50 이 컬럼을 varchar(16) 으로 선언한 탓에 이 쓰기는 언제나 22001 로 실패했고, 재시도가
   * 소진된 행은 상태 기록 자체가 불가능해 SENDING 에 갇힌 뒤 stale-claim 리퍼에 의해 PENDING 으로
   * 되돌려져 영구 루프에 빠졌다. 컬럼 폭을 되돌리면 이 단언이 즉시 깨진다.
   */
  @Test
  void markPermanentFailure_persistsFullStatusString() {
    long userId = createTestUser();
    UUID corr = UUID.randomUUID();
    dispatcher.enqueue(chatRequest(userId, corr));

    long outboxId = repo.findByCorrelation(corr).get(0).id();
    repo.markPermanentFailure(outboxId, "UNRECOVERABLE", "V105 회귀 가드");

    assertThat(repo.findByCorrelation(corr).get(0).status())
        .as("status 컬럼이 다시 좁아지면 markPermanentFailure 가 22001 로 죽어 outbox 가 영구 정지한다")
        .isEqualTo("PERMANENT_FAILURE");
  }

  /**
   * 테스트용 사용자 생성 — 충돌 회피를 위해 nanoTime으로 unique 이메일.
   *
   * <p>기본 테넌트의 ACTIVE 멤버십을 함께 만든다(P2-e). {@code proactive_message} 가 V103 으로
   * {@code tenant_id} NOT NULL 이 됐고, 워커 스레드에는 테넌트 컨텍스트가 없어 {@code ChatChannel}
   * 이 <b>수신자의 멤버십</b>에서 테넌트를 해석하기 때문이다. 멤버십이 없는 수신자는 어느 워크스페이스의
   * 인박스인지 확정할 수 없어 영구 실패로 처리된다 — 그것이 의도된 fail-closed 동작이다.
   */
  private long createTestUser() {
    long ts = System.nanoTime();
    long userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "workeruser_" + ts)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Worker Test User")
            .set(USER.EMAIL, "worker_" + ts + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    membershipUserId = userId;
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    return userId;
  }

  /** 이 테스트가 방금 enqueue 한 행의 next_attempt_at 을 과거로 당겨 배치의 맨 앞에 오게 한다. */
  private void pullToFrontOfQueue(UUID corr) {
    dsl.update(table(name("notification_outbox")))
        .set(
            field(name("next_attempt_at"), OffsetDateTime.class),
            OffsetDateTime.now().minusYears(10))
        .where(field(name("correlation_id"), UUID.class).eq(corr))
        .execute();
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
