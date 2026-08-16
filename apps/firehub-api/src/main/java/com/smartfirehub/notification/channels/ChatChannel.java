package com.smartfirehub.notification.channels;

import com.smartfirehub.notification.AuthStrategy;
import com.smartfirehub.notification.Channel;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.PermanentFailureReason;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
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
 *
 * <p><b>테넌트 배선(P2-f)</b>: 이 채널은 항상 <b>이미 열린 테넌트 컨텍스트 안</b>에서 불린다.
 * 프로덕션 호출자는 outbox 워커 하나뿐이고({@code ChannelSettingsService} 는 CHAT 을 진입 즉시
 * 거부한다), {@code NotificationDispatchWorker.runOneBatchForTenant} 가 outbox 행의
 * {@code tenant_id} 로 {@code TenantContext.runScoped} 를 열어 그 안에서 배달한다. 따라서 여기서
 * 테넌트를 따로 해석하지 않는다 — 해석 지점이 늘수록 각자 다른 방식으로 틀릴 수 있다.
 *
 * <p>P2-e 에는 여기서 수신자의 ACTIVE 멤버십으로 테넌트를 <b>추측</b>했다. 그것은
 * {@code notification_outbox} 에 {@code tenant_id} 가 없던 시절의 임시방편이었고, V106 이 그 컬럼을
 * 붙이면서 전제가 사라져 P2-f 에서 제거했다(추측이 아니라 enqueue 시점의 사실을 쓴다).
 */
@Component
public class ChatChannel implements Channel {

  private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);

  private final ProactiveMessageRepository messageRepo;
  private final ProactiveJobExecutionRepository executionRepo;
  private final SseEmitterRegistry sseRegistry;

  public ChatChannel(
      ProactiveMessageRepository messageRepo,
      ProactiveJobExecutionRepository executionRepo,
      SseEmitterRegistry sseRegistry) {
    this.messageRepo = messageRepo;
    this.executionRepo = executionRepo;
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

    // execution 교차테넌트 검사(R9). 현재 컨텍스트에서 execution 이 보이지 않으면 payload 가 가리키는
    // 잡과 이 행의 테넌트가 어긋난 것이므로 메시지를 만들지 않는다.
    //
    // 왜 FK 에 맡길 수 없는가: proactive_message.execution_id → proactive_job_execution FK 가
    // 있지만 PostgreSQL 의 참조 무결성 검사는 RLS 를 우회한다. 즉 현재 컨텍스트에서 보이지도
    // 않는 타 테넌트 execution 을 참조해도 FK 는 통과하고 행이 그대로 만들어진다. app_tenant 롤로
    // 실측 확인했다(2026-08-16, 트랜잭션 롤백): 테넌트 A 컨텍스트에서 B 의 execution 은 SELECT 로
    // 0행이었으나 그 id 를 execution_id 에 넣은 INSERT 는 성공했다(tenant_id=A, execution_id=B의 것).
    // 따라서 이 명시 검사는 "실패 형태를 다듬는 장치"가 아니라 이 경로의 유일한 방어다.
    // 게다가 payload.metadata.executionId 는 JSON 페이로드에서 파싱된 신뢰할 수 없는 입력이다.
    if (executionId != null && executionRepo.findById(executionId).isEmpty()) {
      log.error(
          "ChatChannel: execution {} 이 현재 테넌트 {} 에서 보이지 않는다 —"
              + " 잡 소유 테넌트와 outbox 행의 테넌트가 어긋나므로 메시지를 생성하지 않는다 (outboxId={})",
          executionId,
          TenantContext.get(),
          ctx.outboxId());
      return new DeliveryResult.PermanentFailure(
          PermanentFailureReason.RECIPIENT_INVALID,
          "execution " + executionId + " 이 현재 테넌트 " + TenantContext.get() + " 에 속하지 않는다");
    }

    Long messageId;
    try {
      // 저장은 호출자(워커)가 연 테넌트 컨텍스트 안에서 일어난다 — GUC 는 리포지토리의 클래스 레벨
      // @Transactional 이 여는 트랜잭션에서 주입되고, 그 값이 tenant_id DEFAULT 로 채워진다.
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
