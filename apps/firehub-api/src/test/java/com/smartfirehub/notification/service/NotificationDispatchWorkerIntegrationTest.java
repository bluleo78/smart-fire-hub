package com.smartfirehub.notification.service;

import static com.smartfirehub.jooq.Tables.USER;
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
 * Dispatcher → Worker → ChatChannel 전체 흐름 통합 검증. LISTEN/NOTIFY는 테스트에서 비활성화(다른 프로세스의 listener 간섭
 * 회피), 워커를 직접 호출한다.
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
class NotificationDispatchWorkerIntegrationTest extends IntegrationTestBase {

  @Autowired private NotificationDispatcher dispatcher;
  @Autowired private NotificationDispatchWorker worker;
  @Autowired private NotificationOutboxRepository repo;
  @Autowired private DSLContext dsl;

  /** 이 테스트가 만든 테넌트. 기본 테넌트(1)를 쓰면 정리가 공유 테스트 DB 의 남의 행까지 지운다. */
  private long tenantId;

  @BeforeEach
  void createScratchTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "outbox-worker");
    TenantContext.set(tenantId);
  }

  @AfterEach
  void cleanupFixtures() {
    // 내가 심은 행만 지운다(공유 테스트 DB).
    inTenantFixture(
        tenantId,
        () -> {
          TenantRlsTestSupport.deleteChannelCascade(dsl, tenantId);
          // ChatChannel 이 이 테넌트에 proactive_message 를 남긴다. 채널 cascade 에는 없으므로
          // 함께 지우지 않으면 아래 deleteTenants 가 FK 위반(23503)으로 터진다.
          TenantRlsTestSupport.deleteProactiveAiCascade(dsl, tenantId);
        });
    TenantRlsTestSupport.deleteTenants(dsl, tenantId);
  }

  @Test
  void enqueueChatRequest_workerSendsAndMarksSent() {
    long userId = createTestUser();
    UUID corr = UUID.randomUUID();
    dispatcher.enqueue(chatRequest(userId, corr));
    // 컨테이너 DB 시계가 호스트보다 앞설 때 방금 넣은 행이 "아직 미래"가 되는 것을 막는다.
    inTenantFixture(tenantId, () -> TenantRlsTestSupport.makeOutboxRowDue(dsl, corr));

    // 배경 스레드 재현 — 컨텍스트를 비우고 워커를 부른다. 워커가 스스로 이 테넌트의 스코프를
    // 열지 못하면 배달도 상태 기록도 일어나지 않는다.
    //
    // 큐를 앞으로 당기던 보정(pullToFrontOfQueue)은 더 이상 필요 없다: 클레임이 테넌트별로 좁혀져
    // 공유 테스트 DB 의 tenant_id=1 적체(2026-08-16 실측 3883행)가 이 배치에 섞이지 않는다.
    TenantContext.clear();
    worker.runOneBatchForTenant(tenantId);
    TenantContext.set(tenantId); // 검증 조회를 위해 복구

    // 배달은 runOneBatchForTenant 안에서 동기로 끝난다 — 폴러를 기다리던 await 는 더 이상 필요 없다.
    var rows = repo.findByCorrelation(corr);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).status()).isEqualTo("SENT");
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
   * <p><b>멤버십은 만들지 않는다(P2-f Task 4).</b> P2-e 에는 여기서 스크래치 테넌트의 ACTIVE 멤버십을
   * 함께 만들었다 — {@code ChatChannel} 이 수신자의 멤버십에서 저장 테넌트를 <b>추측</b>했고 멤버십이
   * 없으면 영구 실패였기 때문이다. 그 임시방편은 제거됐다: 이제 워커가 outbox 행의
   * {@code tenant_id} 로 {@code TenantContext.runScoped} 를 열고 채널은 그 컨텍스트를 그대로 쓴다.
   * 따라서 <b>멤버십은 배달에 무관하며</b>, 여기서 멤버십을 만들면 사라진 계약을 되살리는 죽은
   * 픽스처가 된다. 멤버십 없는 수신자도 정상 배달된다는 것 자체의 커버리지는
   * {@code OutboxWorkerTenantScopeTest} 가 진다.
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
    return userId;
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
