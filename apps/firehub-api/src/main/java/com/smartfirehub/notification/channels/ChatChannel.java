package com.smartfirehub.notification.channels;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 웹 인박스(CHAT) 채널 구현.
 *
 * <p>proactive_message 테이블에 행 1개 INSERT 후 SseEmitterRegistry를 통해 해당 사용자에게 실시간 SSE broadcast. 안전망
 * 채널이므로 authStrategy=NONE, binding 불필요.
 *
 * <p>executionId는 Payload.metadata에 들어있으면 그대로 사용(Proactive 발송 흐름), 없으면 null — message_type=REPORT는
 * 기본값으로 유지. htmlContent 등 대용량 필드는 저장하지 않음(인박스 UI는 title/summary만 필요).
 */
@Component
public class ChatChannel implements Channel {

  private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);

  private final ProactiveMessageRepository messageRepo;
  private final SseEmitterRegistry sseRegistry;

  public ChatChannel(ProactiveMessageRepository messageRepo, SseEmitterRegistry sseRegistry) {
    this.messageRepo = messageRepo;
    this.sseRegistry = sseRegistry;
  }

  @Override
  public ChannelType type() {
    return ChannelType.CHAT;
  }

  @Override
  public AuthStrategy authStrategy() {
    return AuthStrategy.NONE;
  }

  @Override
  public DeliveryResult deliver(DeliveryContext ctx) {
    if (ctx.recipientUserId() == null) {
      return new DeliveryResult.PermanentFailure(
          PermanentFailureReason.RECIPIENT_INVALID, "CHAT 채널은 recipientUserId 필수");
    }

    Payload payload = ctx.payload();
    String title = payload.title() == null ? "AI 알림" : payload.title();

    // contentMap 구성 — htmlContent는 일부러 제외 (UI는 summary만 사용)
    Map<String, Object> contentMap = new HashMap<>();
    contentMap.put("title", title);
    contentMap.put("summary", payload.summary() == null ? "" : payload.summary());
    contentMap.put("correlationId", ctx.correlationId().toString());
    if (payload.metadata() != null && !payload.metadata().isEmpty()) {
      contentMap.putAll(payload.metadata());
    }

    Long executionId = extractExecutionId(payload.metadata());
    String messageType = (String) payload.metadata().getOrDefault("messageType", "REPORT");

    Long messageId;
    try {
      messageId =
          messageRepo.create(ctx.recipientUserId(), executionId, title, contentMap, messageType);
    } catch (RuntimeException e) {
      log.error(
          "ChatChannel INSERT failed: outboxId={} userId={}",
          ctx.outboxId(),
          ctx.recipientUserId(),
          e);
      return new DeliveryResult.TransientFailure("proactive_message insert failed", e);
    }

    // SSE broadcast — 실패해도 발송은 성공 간주 (메시지 저장은 완료, 사용자가 재접속 시 조회 가능)
    try {
      Map<String, Object> eventMeta = new HashMap<>();
      eventMeta.put("messageId", messageId);
      eventMeta.put("correlationId", ctx.correlationId().toString());
      if (executionId != null) {
        eventMeta.put("executionId", executionId);
      }

      NotificationEvent event =
          new NotificationEvent(
              UUID.randomUUID().toString(),
              "PROACTIVE_MESSAGE",
              "INFO",
              title,
              payload.summary() == null ? "" : payload.summary(),
              "NOTIFICATION_OUTBOX",
              ctx.outboxId(),
              eventMeta,
              LocalDateTime.now());

      sseRegistry.broadcast(ctx.recipientUserId(), event);
    } catch (RuntimeException e) {
      log.warn(
          "ChatChannel SSE broadcast failed (메시지 저장은 성공): outboxId={} userId={}",
          ctx.outboxId(),
          ctx.recipientUserId(),
          e);
    }

    return new DeliveryResult.Sent("chat-msg-" + messageId);
  }

  /** metadata의 executionId를 Long으로 추출. 없거나 형식 오류면 null. */
  private Long extractExecutionId(Map<String, Object> metadata) {
    if (metadata == null) return null;
    Object raw = metadata.get("executionId");
    if (raw == null) return null;
    if (raw instanceof Long l) return l;
    if (raw instanceof Integer i) return i.longValue();
    if (raw instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(raw.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
