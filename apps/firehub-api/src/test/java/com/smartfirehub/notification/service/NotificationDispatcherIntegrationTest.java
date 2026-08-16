package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.NOTIFICATION_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Dispatcher → Outbox 통합: 멱등성 키가 중복 INSERT 차단하는지 검증.
 *
 * <p><b>테넌트(P2-f)</b>: 기본 테넌트가 아니라 이 테스트가 만든 테넌트에서 돈다 — 정리가 공유 테스트
 * DB 의 남의 행을 건드리지 않게 하고, enqueue 가 찍는 {@code tenant_id} 를 직접 단언하기 위해서다.
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
class NotificationDispatcherIntegrationTest extends IntegrationTestBase {

  @Autowired private NotificationDispatcher dispatcher;
  @Autowired private NotificationOutboxRepository repo;
  @Autowired private DSLContext dsl;

  private long tenantId;
  private Long userId;

  @BeforeEach
  void createScratchTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-dispatch");
    // 바인딩 픽스처가 user FK 를 요구한다. "user" 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라
    // 컨텍스트 없이 만든다.
    userId = TenantRlsTestSupport.insertUser(dsl, "outboxdispatch");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupScratchTenant() {
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId));
    TenantRlsTestSupport.deleteUser(dsl, userId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  @Test
  void enqueue_idempotentOnSameRequest() {
    UUID corr = UUID.randomUUID();
    NotificationRequest req = chatRequest(corr);

    dispatcher.enqueue(req);
    dispatcher.enqueue(req); // 같은 correlationId → idempotency_key 동일 → ON CONFLICT DO NOTHING

    var rows = repo.findByCorrelation(corr);
    // CHAT 1건만 존재, advisory 없음 (CHAT 요청이 resolved 이므로 forcedChatFallback=false)
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).channelType()).isEqualTo(ChannelType.CHAT);

    // enqueue 경로(RoutingResolver → insertIfAbsent)가 호출자의 테넌트 안에서 돌았는지 직접 본다.
    // 정책이 꺼져 있어 격리로는 검증할 수 없으므로 값 자체를 단언한다.
    Long stamped =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(NOTIFICATION_OUTBOX.TENANT_ID)
                    .from(NOTIFICATION_OUTBOX)
                    .where(NOTIFICATION_OUTBOX.CORRELATION_ID.eq(corr))
                    .fetchOne(NOTIFICATION_OUTBOX.TENANT_ID));
    assertThat(stamped).isEqualTo(tenantId);
  }

  /**
   * <b>조용한 CHAT 열화 회귀 가드 (P2-f Task 6, 리뷰 M1).</b>
   *
   * <p><b>무엇을 막는가.</b> V107 이후 {@code enqueue} 가 테넌트 컨텍스트 없는 스레드에서 돌면
   * {@code RoutingResolver} 의 두 조회가 모두 "행 없음"을 보고 — {@code preferenceRepo.isEnabled}
   * 는 기본 {@code true}, {@code bindingRepo.findActive} 는 0행 — <b>바인딩이 있는 채널까지 전부
   * {@code BINDING_MISSING} 으로 스킵되어 CHAT 폴백만 남는다.</b> 예외도 로그도 없다. 프로액티브
   * 경로는 예외마저 {@code ProactiveJobAsyncRunner:163-171} 이 {@code log.warn} 으로 삼키므로,
   * SLACK/EMAIL 알림이 통째로 사라져도 아무도 모른다. 조사가 "이 밴드에서 가장 발견하기 어려운
   * 회귀" 로 지목한 형태다.
   *
   * <p><b>왜 리졸버를 직접 부르면 안 되는가.</b> 열화가 발생하는 지점은 {@code enqueue} 와
   * {@code resolve} <b>사이의 배관</b>이다. 테스트가 스스로 스코프를 열고 {@code resolve} 를 직접
   * 부르면 그 배관을 건너뛰므로, {@code NotificationDispatcher:65} 직전에서 컨텍스트를 지우는
   * 사보타주를 넣어도 초록을 유지한다(리뷰어 실측). <b>진입점을 통과해야 한다.</b>
   *
   * <p>그래서 이 테스트는 실제 ACTIVE SLACK 바인딩을 심고 {@code dispatcher.enqueue} 를 통과시켜
   * <b>outbox 에 비-CHAT 행이 실제로 들어갔는지</b>를 본다. 컨텍스트가 유실되면 그 행이 CHAT
   * advisory 로 바뀌므로 즉시 빨개진다.
   */
  @Test
  void enqueue_withBoundChannel_producesNonChatRow_notSilentChatFallback() {
    // ACTIVE SLACK 바인딩을 소유 테넌트에 심는다 — 이것이 있으면 SLACK 이 resolved 여야 한다.
    inTenantFixture(
        tenantId,
        () ->
            dsl.execute(
                "insert into user_channel_binding (user_id, channel_type, external_user_id)"
                    + " values (?, 'SLACK', 'U-TEST')",
                userId));

    UUID corr = UUID.randomUUID();
    dispatcher.enqueue(slackRequest(corr));

    var rows = repo.findByCorrelation(corr);
    assertThat(rows)
        .as("바인딩이 있는데 SLACK 행이 없으면 enqueue 경로에서 컨텍스트가 유실된 것이다")
        .extracting(NotificationOutboxRepository.NotificationOutboxRow::channelType)
        .containsExactly(ChannelType.SLACK);
    // advisory 행이 붙었다는 것은 forcedChatFallback 이 발동했다는 뜻 — 열화의 직접 증거다.
    assertThat(rows)
        .as("CHAT advisory 가 섞이면 조용한 폴백이 일어난 것이다")
        .noneMatch(r -> r.channelType() == ChannelType.CHAT);
  }

  /** 위 테스트용 — 수신자가 SLACK 만 요청한다(바인딩이 없으면 CHAT 폴백으로 떨어지는 형태). */
  private NotificationRequest slackRequest(UUID corr) {
    return new NotificationRequest(
        "TEST_ROUTING",
        null,
        null,
        corr,
        new Payload(
            Payload.PayloadType.STANDARD,
            "t",
            "s",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of()),
        null,
        List.of(new Recipient(userId, null, EnumSet.of(ChannelType.SLACK))));
  }

  private NotificationRequest chatRequest(UUID corr) {
    return new NotificationRequest(
        "TEST_INTEGRATION",
        null,
        null,
        corr,
        new Payload(
            Payload.PayloadType.STANDARD,
            "t",
            "s",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of()),
        null,
        List.of(new Recipient(null, null, EnumSet.of(ChannelType.CHAT))));
  }
}
