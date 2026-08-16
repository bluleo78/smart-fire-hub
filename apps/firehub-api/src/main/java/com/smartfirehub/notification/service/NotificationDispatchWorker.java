package com.smartfirehub.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import com.smartfirehub.notification.repository.UserChannelBindingRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 워커 — 30초 폴링 + LISTEN/NOTIFY 즉시 깨움 + claim loop + deliver.
 *
 * <p>SKIP LOCKED + lease 컬럼으로 멀티 인스턴스 안전. Sent/TransientFailure/PermanentFailure sealed result로 완전
 * 분기. feature flag OFF 상태에서는 no-op.
 *
 * <p>Micrometer 메트릭은 Task 13에서 추가.
 *
 * <p><b>테넌트 배선(P2-f)</b>: 이 워커는 컨텍스트가 없는 배경 스레드에서 돈다. {@code runOneBatch}
 * 가 {@code outbox_tenant_ids('{PENDING}')} 로 일감 있는 테넌트를 얻어 테넌트마다
 * {@code TenantContext.runScoped} 를 열고, 그 안에서만 클레임·배달·상태기록을 한다. 따라서
 * {@link #deliverOne} 이하 모든 리포지토리·채널 호출은 <b>항상 테넌트 컨텍스트 안</b>에 있다.
 */
@Component
public class NotificationDispatchWorker {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatchWorker.class);

  private final NotificationOutboxRepository outboxRepo;
  private final TenantScopedRunner tenantRunner;
  private final UserChannelBindingRepository bindingRepo;
  private final ChannelRegistry channelRegistry;
  private final BackoffPolicy backoff;
  private final ObjectMapper objectMapper;
  private final String instanceId = "instance-" + UUID.randomUUID().toString().substring(0, 8);
  private final int batchSize;
  private final boolean enabled;

  public NotificationDispatchWorker(
      NotificationOutboxRepository outboxRepo,
      TenantScopedRunner tenantRunner,
      UserChannelBindingRepository bindingRepo,
      ChannelRegistry channelRegistry,
      BackoffPolicy backoff,
      ObjectMapper objectMapper,
      @Value("${notification.worker.batch_size:20}") int batchSize,
      @Value("${notification.outbox.enabled:false}") boolean enabled) {
    this.outboxRepo = outboxRepo;
    this.tenantRunner = tenantRunner;
    this.bindingRepo = bindingRepo;
    this.channelRegistry = channelRegistry;
    this.backoff = backoff;
    this.objectMapper = objectMapper;
    this.batchSize = batchSize;
    this.enabled = enabled;
  }

  /**
   * 30초 폴백 폴링. LISTEN/NOTIFY가 정상이면 거의 즉시 깨어나고 이건 보조.
   *
   * <p>{@code initialDelay} 기본값은 0 이라 운영 동작은 그대로다(기동 직후 1회 폴링). 노브를 둔
   * 이유는 P2-f 이후 클레임이 테넌트별이 됐기 때문이다 — 예전에는 전역 {@code next_attempt_at ASC}
   * 라 오래된 적체가 앞을 막아 갓 만들어진 행이 폴러에 닿지 않았지만, 이제는 어떤 테넌트든 자기
   * 배치를 받는다. 통합 테스트는 스프링 컨텍스트를 캐시해 스위트 도중에도 새 컨텍스트가 뜨는데,
   * 그때의 기동 폴링이 <b>다른 테스트 클래스의 스크래치 테넌트 행</b>을 가로채 무관한 실패를 만든다.
   */
  @Scheduled(
      initialDelayString = "${notification.scheduler.initial_delay_ms:0}",
      fixedDelayString = "${notification.worker.poll_interval_ms:30000}")
  public void pollOnce() {
    if (!enabled) return;
    runOneBatch();
  }

  /** {@link OutboxListenerLoop}가 NOTIFY 수신 시 호출. */
  public void onNotify() {
    if (!enabled) return;
    runOneBatch();
  }

  /**
   * 일감이 있는 테넌트를 순회하며 테넌트마다 한 배치씩 처리한다.
   *
   * <p><b>왜 순회인가.</b> {@code @Scheduled}/LISTEN 스레드에는 원 HTTP 요청이 없어 테넌트 컨텍스트가
   * 없다. 컨텍스트가 없으면 {@code TenantAwareTransactionManager.doBegin} 이 GUC 를 <b>아예 심지
   * 않으므로</b>, 정책(V107) 이후 클레임은 조용히 0행이 되고 채널이 하는 쓰기는 {@code tenant_id}
   * NOT NULL 위반으로 죽는다. 전역 노출은 {@code outbox_tenant_ids} 가 돌려주는 정수 목록뿐이고
   * 실제 클레임·배달·상태기록은 전부 테넌트 컨텍스트 안의 평범한 RLS 쿼리로 한다(R3).
   *
   * <p>순회 자체는 {@code TenantScopedRunner.forEachTenant} 가 맡되 <b>목록은 여기서 정한다</b>.
   * {@code forEachActiveTenant} 를 쓰지 않는 이유는 R4 — ACTIVE 테넌트만 돌면 비활성 테넌트의
   * outbox 행이 영원히 처리되지 않는다. R4 가 배제한 것은 <b>출처</b>({@code findActiveTenantIds})
   * 이지 루프가 아니므로, 출처를 {@code outbox_tenant_ids} 로 바꿔 러너를 그대로 쓴다.
   *
   * <p>{@code pollOnce} 와 {@code onNotify} 가 모두 여기로 모이므로 <b>배선 한 곳이 두 진입점을
   * 동시에 덮는다</b>.
   *
   * <p><b>비용 — R3 가 판정한 설계상 대가다(이 자리에서 줄일 수 없다).</b> {@code batchSize} 는 이제
   * <b>테넌트당</b> 적용되므로 한 폴링의 최대 처리량이 {@code batchSize × 일감있는테넌트수} 이고,
   * 그 전부가 이 스케줄러 스레드 하나에서 <b>동기</b>로 돈다. {@code onNotify} 도 여기로 모이므로
   * enqueue 한 건마다 {@code outbox_tenant_ids} 1회 + 테넌트당 클레임 1회가 발생한다. 테넌트가
   * 많아져 한 주기가 폴링 간격을 넘기 시작하면 그때 병렬화·샤딩을 검토할 자리다.
   */
  void runOneBatch() {
    // 순회·격리(Throwable 까지)·로깅은 러너가 소유한다 — 출처만 여기서 정한다(R4 가 배제한 것은
    // findActiveTenantIds 라는 출처이지 루프가 아니다). 한 테넌트의 실패가 다른 테넌트의 배치를
    // 멈추지 않는다 — 스케줄러는 다음 주기까지 기다려야 하므로 장애가 그대로 전파된다.
    tenantRunner.forEachTenant(
        outboxRepo.tenantIdsWithStatus("PENDING"), this::runOneBatchForTenant);
  }

  /**
   * 한 테넌트의 컨텍스트를 열고 그 안에서 클레임 → 배달 → 상태기록을 끝낸다.
   *
   * <p><b>이 배선 하나가 채널 3종을 동시에 고친다.</b> {@code deliverOne} 의
   * {@code bindingRepo.findActive}, {@code SlackChannel} 의 binding·workspace 조회,
   * {@code KakaoChannel:152} 의 토큰 만료 {@code upsert}(쓰기), {@code ChatChannel} 의
   * {@code proactive_message} INSERT 가 전부 이 스코프 안에서 돈다. 채널마다 테넌트를 따로
   * 해석하지 않는 이유가 이것이다 — 해석 지점이 늘수록 각자 다른 방식으로 틀릴 수 있다.
   *
   * <p>{@code runScoped} 는 진입 전 컨텍스트를 <b>복원</b>한다(clear 아님). 테스트처럼 이미
   * 컨텍스트가 있는 스레드에서 불려도 호출자의 컨텍스트를 빼앗지 않는다.
   */
  void runOneBatchForTenant(long tenantId) {
    TenantContext.runScoped(tenantId, () -> claimAndDeliver(tenantId));
  }

  private void claimAndDeliver(long tenantId) {
    var rows = outboxRepo.claimDue(batchSize, instanceId, tenantId);
    for (var row : rows) {
      try {
        deliverOne(row);
      } catch (Throwable t) {
        // deliver 내부에서 미처 catch되지 않은 예외 — transient로 처리 후 재시도 스케줄
        int next = row.attemptCount() + 1;
        try {
          if (backoff.exhausted(next)) {
            outboxRepo.markPermanentFailure(
                row.id(), "UNRECOVERABLE", t.getClass().getSimpleName() + ": " + t.getMessage());
          } else {
            outboxRepo.rescheduleTransient(
                row.id(),
                next,
                Instant.now().plus(backoff.delayFor(next)),
                t.getClass().getSimpleName() + ": " + t.getMessage());
          }
        } catch (Throwable bookkeepingFailure) {
          // 한 행의 상태 기록 실패가 배치 전체를 멈추지 않게 한다.
          // 왜 필요한가: 여기서 예외가 새면 남은 행이 그대로 SENDING 에 갇힌 채 폴링마다 같은 지점에서
          // 죽어, outbox 가 조용히 영구 정지한다(실측: 공유 테스트 DB 에 due PENDING 2500여 건 적체).
          // 그 적체를 만든 원인(status varchar(16) < 'PERMANENT_FAILURE' 17자)은 V105 에서 닫혔다.
          // 그래도 이 방어는 남긴다 — 상태 기록이 실패할 이유는 폭 말고도 있다(연결 끊김, 데드락).
          log.error(
              "outbox {} 상태 기록 실패 — 이 행은 건너뛰고 배치를 계속한다 (원래 실패: {})",
              row.id(),
              t.toString(),
              bookkeepingFailure);
        }
      }
    }
  }

  /**
   * 한 행을 배달한다. <b>호출자가 연 테넌트 컨텍스트 안에서만 불린다</b>({@link
   * #runOneBatchForTenant}) — 아래 {@code bindingRepo.findActive} 와 채널 구현체의 리포지토리
   * 접근이 전부 그 스코프에 의존하므로, 이 메서드를 스코프 밖에서 부르는 호출자를 새로 만들지 말 것.
   */
  private void deliverOne(NotificationOutboxRow row) {
    Channel ch;
    try {
      ch = channelRegistry.get(row.channelType());
    } catch (IllegalStateException e) {
      // 채널 구현체가 아직 등록되지 않음 — 영구 실패로 기록
      outboxRepo.markPermanentFailure(
          row.id(), "UNRECOVERABLE", "no channel: " + row.channelType());
      return;
    }

    Optional<com.smartfirehub.notification.repository.UserChannelBinding> binding =
        row.recipientUserId() == null
            ? Optional.empty()
            : bindingRepo.findActive(row.recipientUserId(), row.channelType());

    Payload payload;
    try {
      payload =
          row.payloadJson() == null
              ? null
              : objectMapper.readValue(row.payloadJson(), Payload.class);
    } catch (Exception e) {
      outboxRepo.markPermanentFailure(
          row.id(), "RECIPIENT_INVALID", "payload parse: " + e.getMessage());
      return;
    }
    if (payload == null) {
      outboxRepo.markPermanentFailure(
          row.id(),
          "RECIPIENT_INVALID",
          "payload missing (ref-based payload not yet supported in Stage 1)");
      return;
    }

    DeliveryContext ctx =
        new DeliveryContext(
            row.id(),
            row.correlationId(),
            row.recipientUserId(),
            row.recipientAddress(),
            binding,
            payload);

    DeliveryResult result;
    try {
      result = ch.deliver(ctx);
    } catch (Throwable t) {
      // 채널 코드가 RuntimeException 던지면 transient 처리
      result =
          new DeliveryResult.TransientFailure(
              "uncaught: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }

    switch (result) {
      case DeliveryResult.Sent sent -> outboxRepo.markSent(row.id(), sent.externalMessageId());
      case DeliveryResult.TransientFailure tf -> {
        int next = row.attemptCount() + 1;
        if (backoff.exhausted(next)) {
          outboxRepo.markPermanentFailure(row.id(), "UNRECOVERABLE", tf.reason());
        } else {
          outboxRepo.rescheduleTransient(
              row.id(), next, Instant.now().plus(backoff.delayFor(next)), tf.reason());
        }
      }
      case DeliveryResult.PermanentFailure pf ->
          outboxRepo.markPermanentFailure(row.id(), pf.reason().name(), pf.details());
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "deliver outboxId={} channel={} result={}",
          row.id(),
          row.channelType(),
          result.getClass().getSimpleName());
    }
  }
}
