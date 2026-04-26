package com.smartfirehub.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Component
public class NotificationDispatchWorker {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatchWorker.class);

  private final NotificationOutboxRepository outboxRepo;
  private final UserChannelBindingRepository bindingRepo;
  private final ChannelRegistry channelRegistry;
  private final BackoffPolicy backoff;
  private final ObjectMapper objectMapper;
  private final String instanceId = "instance-" + UUID.randomUUID().toString().substring(0, 8);
  private final int batchSize;
  private final boolean enabled;

  public NotificationDispatchWorker(
      NotificationOutboxRepository outboxRepo,
      UserChannelBindingRepository bindingRepo,
      ChannelRegistry channelRegistry,
      BackoffPolicy backoff,
      ObjectMapper objectMapper,
      @Value("${notification.worker.batch_size:20}") int batchSize,
      @Value("${notification.outbox.enabled:false}") boolean enabled) {
    this.outboxRepo = outboxRepo;
    this.bindingRepo = bindingRepo;
    this.channelRegistry = channelRegistry;
    this.backoff = backoff;
    this.objectMapper = objectMapper;
    this.batchSize = batchSize;
    this.enabled = enabled;
  }

  /** 30초 폴백 폴링. LISTEN/NOTIFY가 정상이면 거의 즉시 깨어나고 이건 보조. */
  @Scheduled(fixedDelayString = "${notification.worker.poll_interval_ms:30000}")
  public void pollOnce() {
    if (!enabled) return;
    runOneBatch();
  }

  /** {@link OutboxListenerLoop}가 NOTIFY 수신 시 호출. */
  public void onNotify() {
    if (!enabled) return;
    runOneBatch();
  }

  void runOneBatch() {
    var rows = outboxRepo.claimDue(batchSize, instanceId);
    for (var row : rows) {
      try {
        deliverOne(row);
      } catch (Throwable t) {
        // deliver 내부에서 미처 catch되지 않은 예외 — transient로 처리 후 재시도 스케줄
        int next = row.attemptCount() + 1;
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
      }
    }
  }

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
