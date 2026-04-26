package com.smartfirehub.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 도메인이 알림 발송을 트리거할 때 호출하는 단일 진입점.
 *
 * <p>수신자 × requested_channels 펼치기 → RoutingResolver로 resolved_channels 확정 → 멱등성 키로 outbox INSERT →
 * NOTIFY 발행. CHAT 강제 폴백 시 안내 메시지 1건 추가.
 *
 * <p>feature flag notification.outbox.enabled=false인 경우 no-op (회귀 안전). 도메인 코드는 flag를 별도로 확인하고 기존 직접
 * 호출 경로를 유지할 책임.
 */
@Service
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  private final RoutingResolver routingResolver;
  private final NotificationOutboxRepository outboxRepo;
  private final IdempotencyKeyGenerator keyGen;
  private final OutboxNotifier notifier;
  private final ObjectMapper objectMapper;
  private final boolean enabled;

  public NotificationDispatcher(
      RoutingResolver routingResolver,
      NotificationOutboxRepository outboxRepo,
      IdempotencyKeyGenerator keyGen,
      OutboxNotifier notifier,
      ObjectMapper objectMapper,
      @Value("${notification.outbox.enabled:false}") boolean enabled) {
    this.routingResolver = routingResolver;
    this.outboxRepo = outboxRepo;
    this.keyGen = keyGen;
    this.notifier = notifier;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
  }

  /** 도메인이 호출. 멱등성 키 기반 INSERT로 중복 발송 차단. feature flag OFF면 즉시 반환 (호출자가 기존 직접 호출 경로를 사용). */
  public void enqueue(NotificationRequest request) {
    if (!enabled) {
      log.debug("outbox disabled — skipping enqueue for {}", request.eventType());
      return;
    }
    UUID correlationId =
        request.correlationId() == null ? UUID.randomUUID() : request.correlationId();

    for (Recipient recipient : request.recipients()) {
      ResolvedRouting routing = routingResolver.resolve(recipient);
      for (ChannelType channel : routing.resolvedChannels()) {
        outboxRepo.insertIfAbsent(
            buildRow(request, recipient, channel, correlationId, request.standardPayload()));
      }
      if (routing.forcedChatFallback()) {
        outboxRepo.insertIfAbsent(
            buildAdvisoryRow(request, recipient, correlationId, routing.skippedReasons()));
      }
    }
    notifier.notifyOutboxNew();
  }

  private NotificationOutboxRow buildRow(
      NotificationRequest req, Recipient r, ChannelType ch, UUID correlationId, Payload payload) {
    try {
      String json = objectMapper.writeValueAsString(payload);
      return new NotificationOutboxRow(
          null,
          keyGen.generate(correlationId, ch, r.userId()),
          correlationId,
          req.eventType(),
          req.eventSourceId(),
          ch,
          r.userId(),
          r.externalAddressIfAny(),
          req.payloadRef() == null ? null : req.payloadRef().type(),
          req.payloadRef() == null ? null : req.payloadRef().id(),
          json,
          "STANDARD",
          "PENDING",
          0,
          Instant.now());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("payload serialize failed", e);
    }
  }

  private NotificationOutboxRow buildAdvisoryRow(
      NotificationRequest req, Recipient r, UUID correlationId, Map<ChannelType, String> reasons) {
    Payload advisory = AdvisoryPayloadFactory.build(reasons);
    try {
      String json = objectMapper.writeValueAsString(advisory);
      return new NotificationOutboxRow(
          null,
          keyGen.generateAdvisory(correlationId, r.userId()),
          correlationId,
          "CHANNEL_ADVISORY",
          null,
          ChannelType.CHAT,
          r.userId(),
          null,
          null,
          null,
          json,
          "STANDARD",
          "PENDING",
          0,
          Instant.now());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("advisory serialize failed", e);
    }
  }
}
