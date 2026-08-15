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
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.repository.MembershipRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p><b>테넌트 배선(P2-e)</b>: 이 채널은 {@code NotificationDispatchWorker} 의 {@code @Scheduled}
 * 스레드에서 불린다 — 원 HTTP 요청이 없어 테넌트 컨텍스트도 트랜잭션도 없다. {@code proactive_message}
 * 는 V103 으로 {@code tenant_id} NOT NULL(GUC 파생 DEFAULT)이 됐으므로, 컨텍스트 없이 INSERT 하면
 * 실패한다. 그래서 여기서 테넌트를 <b>해석</b>해 {@code TenantContext.runScoped} 안에서 저장한다.
 * ({@code notification_outbox} 는 P2-f 소관이라 아직 {@code tenant_id} 를 싣지 못한다.)
 */
@Component
public class ChatChannel implements Channel {

  private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);

  private final ProactiveMessageRepository messageRepo;
  private final ProactiveJobExecutionRepository executionRepo;
  private final MembershipRepository membershipRepository;
  private final SseEmitterRegistry sseRegistry;

  public ChatChannel(
      ProactiveMessageRepository messageRepo,
      ProactiveJobExecutionRepository executionRepo,
      MembershipRepository membershipRepository,
      SseEmitterRegistry sseRegistry) {
    this.messageRepo = messageRepo;
    this.executionRepo = executionRepo;
    this.membershipRepository = membershipRepository;
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

    // 수신자의 ACTIVE 멤버십에서 테넌트를 해석한다. 확정할 수 없으면 저장하지 않는다(fail-closed).
    Optional<Long> resolvedTenant = resolveTenantId(ctx.recipientUserId());
    if (resolvedTenant.isEmpty()) {
      return new DeliveryResult.PermanentFailure(
          PermanentFailureReason.RECIPIENT_INVALID,
          "수신자 " + ctx.recipientUserId() + " 의 ACTIVE 멤버십이 정확히 1개가 아니라 테넌트를 확정할 수 없다");
    }
    long tenantId = resolvedTenant.get();

    // 사전 판정 R1 — execution_id 가 있으면 그쪽이 권위다. 다만 execution 행 자체가 RLS 대상이라
    // "컨텍스트 없이 먼저 읽어 테넌트를 알아내는" 조회는 불가능하다(0행). 그래서 조회가 아니라
    // <b>거부권</b>으로 구현한다: 해석된 테넌트 안에서 execution 이 보이지 않으면 두 출처가 어긋난
    // 것이므로 메시지를 만들지 않는다. A 테넌트 사용자에게 B 테넌트 잡의 메시지가 배달되는 행을
    // 불가능하게 만드는 것이 이 밴드의 목적이다.
    if (executionId != null) {
      boolean visible =
          TenantContext.runScopedGet(
              tenantId, () -> executionRepo.findById(executionId).isPresent());
      if (!visible) {
        log.error(
            "ChatChannel: execution {} 이 수신자 {} 의 테넌트 {} 에서 보이지 않는다 —"
                + " 잡 소유 테넌트와 수신자 테넌트가 어긋나므로 메시지를 생성하지 않는다 (outboxId={})",
            executionId,
            ctx.recipientUserId(),
            tenantId,
            ctx.outboxId());
        return new DeliveryResult.PermanentFailure(
            PermanentFailureReason.RECIPIENT_INVALID,
            "execution " + executionId + " 이 수신자 테넌트 " + tenantId + " 에 속하지 않는다");
      }
    }

    Long messageId;
    try {
      // 저장은 반드시 테넌트 컨텍스트 안에서 — GUC 는 리포지토리의 클래스 레벨 @Transactional 이
      // 여는 트랜잭션에서 주입되고, 그 값이 tenant_id DEFAULT 로 채워진다.
      messageId =
          TenantContext.runScopedGet(
              tenantId,
              () ->
                  messageRepo.create(
                      ctx.recipientUserId(), executionId, title, contentMap, messageType));
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

  /**
   * 수신자의 ACTIVE 멤버십에서 저장 테넌트를 해석한다. 정확히 1개일 때만 값이 나온다.
   *
   * <p>0개(멤버십 없음)나 2개 이상(어느 워크스페이스의 인박스인지 판별 불가)이면 빈 값이다. 임의의
   * 기본 테넌트로 떨어뜨리면 수신자가 속하지도 않은 워크스페이스에 메시지가 쌓이고, 정작 수신자에게는
   * RLS 로 0행이라 보이지 않는다 — 조용한 열화보다 눈에 띄는 영구 실패가 낫다(같은 판단:
   * {@code ProactiveJobSchedulerService} 의 P2-e 이전 테넌트 해석 주석).
   *
   * <p>{@code membership}/{@code tenant} 는 전역(RLS 미적용) 테이블이라 컨텍스트·트랜잭션 없이 조회된다.
   */
  private Optional<Long> resolveTenantId(Long recipientUserId) {
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(recipientUserId);
    Optional<Long> tenantId = MembershipResponse.soleActiveTenant(memberships);
    if (tenantId.isEmpty()) {
      log.error(
          "ChatChannel: 수신자 {} 의 ACTIVE 멤버십이 {}개라 저장 테넌트를 확정할 수 없다",
          recipientUserId,
          memberships.size());
    }
    return tenantId;
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
