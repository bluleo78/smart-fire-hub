package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.channels.ChatChannel;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.proactive.service.delivery.ActiveRecipientResolver;
import com.smartfirehub.proactive.service.delivery.ChatDeliveryChannel;
import com.smartfirehub.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * WD-173: 사용자 단위 알림(리포트 도착·파이프라인 실행 실패·임포트·문서·이상 탐지)은 <b>그 알림이 생긴 워크스페이스 화면으로 연 연결로만</b> 간다.
 *
 * <p>결함: 두 워크스페이스(A·B) 멤버가 B 화면을 보고 있으면 A 의 「리포트 도착」(리포트 제목·작업 이름)·「파이프라인 실행 실패」 토스트가 B 화면에 떴고, A
 * 에서 제거된 뒤에도 떴다 — {@code SseEmitterRegistry.broadcast(userId)} 가 연결의 테넌트를 보지 않고 그 사용자의 모든 연결로 보냈기
 * 때문이다.
 *
 * <p>같은 사용자가 테넌트 T1·T2 화면으로 연 연결 두 개를 기록용 연결로 등록하고, 실제 전송 횟수로 판정한다(목 검증이 아니라 전송 기록 — 필터가 빠지면 T2 연결
 * 수가 1 이 되어 실패한다).
 */
class UserNotificationTenantScopeTest {

  private static final long T1 = 910_001L;
  private static final long T2 = 910_002L;
  private static final long USER = 77L;

  /** 전송 횟수를 세는 연결 — 실제 HTTP 응답 없이 send 를 기록한다. */
  static class RecordingEmitter extends SseEmitter {
    final AtomicInteger sent = new AtomicInteger();

    @Override
    public void send(SseEventBuilder builder) {
      sent.incrementAndGet();
    }
  }

  /** 등록 순서대로 기록용 연결을 돌려주는 레지스트리. */
  static class RecordingRegistry extends SseEmitterRegistry {
    final List<RecordingEmitter> created = new ArrayList<>();

    RecordingRegistry(ObjectMapper om) {
      super(om);
    }

    @Override
    SseEmitter createEmitter() {
      RecordingEmitter e = new RecordingEmitter();
      created.add(e);
      return e;
    }
  }

  private RecordingRegistry registry;
  private RecordingEmitter t1Conn;
  private RecordingEmitter t2Conn;

  /** 같은 사용자의 T1 화면 연결, T2 화면 연결을 하나씩 연다. */
  @BeforeEach
  void openTwoWorkspaceConnections() {
    TenantContext.clear();
    registry = new RecordingRegistry(new ObjectMapper().findAndRegisterModules());
    registry.register(USER, T1);
    registry.register(USER, T2);
    t1Conn = registry.created.get(0);
    t2Conn = registry.created.get(1);
  }

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  // ------------------------------------------------------------------ //
  // 레지스트리
  // ------------------------------------------------------------------ //

  /** 현재 테넌트 T1 에서 보낸 사용자 알림은 T1 연결만 받는다 — T2 화면 연결로는 가지 않는다. */
  @Test
  void broadcast_inTenantContext_sendsOnlyToThatTenantsConnection() {
    TenantContext.runScoped(T1, () -> registry.broadcast(USER, sampleEvent()));

    assertThat(t1Conn.sent.get()).isEqualTo(1);
    assertThat(t2Conn.sent.get()).isZero();
  }

  /** 테넌트 컨텍스트가 없으면 어느 워크스페이스 알림인지 모르므로 아무 연결로도 보내지 않는다(fail-closed). */
  @Test
  void broadcast_withoutTenantContext_sendsToNoConnection() {
    registry.broadcast(USER, sampleEvent());

    assertThat(t1Conn.sent.get()).isZero();
    assertThat(t2Conn.sent.get()).isZero();
  }

  /** 테넌트를 명시한 경로도 같은 규칙 — T2 를 지정하면 T2 연결만 받는다. */
  @Test
  void broadcastToUserInTenant_sendsOnlyToGivenTenantsConnection() {
    registry.broadcastToUserInTenant(T2, USER, sampleEvent());

    assertThat(t1Conn.sent.get()).isZero();
    assertThat(t2Conn.sent.get()).isEqualTo(1);
  }

  /** 연결을 닫은(제거된) 뒤에는 같은 테넌트 알림이어도 그 연결로 보내지 않는다 — 테넌트 기록이 없는 연결은 대상이 아니다. */
  @Test
  void broadcast_afterConnectionRemoved_notSentToRemovedConnection() {
    registry.remove(USER, t1Conn);

    TenantContext.runScoped(T1, () -> registry.broadcast(USER, sampleEvent()));

    assertThat(t1Conn.sent.get()).isZero();
    assertThat(t2Conn.sent.get()).isZero();
  }

  // ------------------------------------------------------------------ //
  // 호출부 통합 — 실제 호출부가 다른 테넌트 연결로 보내지 않는다
  // ------------------------------------------------------------------ //

  /** 파이프라인 실행 실패 알림(T1 의 실행)은 T1 연결로만 간다 — 같은 사람의 T2 화면에 「파이프라인 실행 실패」 토스트가 뜨지 않는다. */
  @Test
  void pipelineFailedNotification_notSentToOtherTenantConnection() {
    // 이 알림은 보안 등급 판정을 쓰지 않는다
    NotificationService service = new NotificationService(registry, null, null, null);

    // @Async 리스너는 pipelineExecutor 스레드의 테넌트를 TenantContextTaskDecorator 로 승계받는다 — 여기서는 그 상태를 직접 세운다
    TenantContext.runScoped(
        T1, () -> service.onPipelineCompleted(new PipelineCompletedEvent(3L, 5L, "FAILED", USER)));

    assertThat(t1Conn.sent.get()).isEqualTo(1);
    assertThat(t2Conn.sent.get()).isZero();
  }

  /** 스마트 작업 리포트 CHAT 전달(T1 작업)의 「리포트 도착」 알림은 T1 연결로만 간다 — 리포트 제목·작업 이름이 T2 화면에 가지 않는다. */
  @Test
  void proactiveReportChatDelivery_notSentToOtherTenantConnection() {
    ProactiveMessageRepository messageRepository = mock(ProactiveMessageRepository.class);
    ActiveRecipientResolver recipientResolver = mock(ActiveRecipientResolver.class);
    when(recipientResolver.activeUsers(List.of(USER)))
        .thenReturn(List.of(new UserResponse(USER, "u77", null, "u77", true, null)));
    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L);
    ChatDeliveryChannel channel =
        new ChatDeliveryChannel(messageRepository, registry, recipientResolver);

    // 실행기(ProactiveJobAsyncRunner)는 T1 스코프 안에서 전달한다 — 여기서는 그 상태를 직접 세운다
    TenantContext.runScoped(
        T1,
        () ->
            channel.deliver(
                reportJob(), 1L, new ProactiveResult("T1 주간 인사 리포트", List.of(), null, null, null)));

    assertThat(t1Conn.sent.get()).isEqualTo(1);
    assertThat(t2Conn.sent.get()).isZero();
  }

  /** 발송함(outbox) CHAT 채널도 같은 규칙 — 워커가 연 T1 스코프의 알림은 T1 연결로만 간다. */
  @Test
  void outboxChatChannel_notSentToOtherTenantConnection() {
    ProactiveMessageRepository messageRepo = mock(ProactiveMessageRepository.class);
    ProactiveJobExecutionRepository executionRepo = mock(ProactiveJobExecutionRepository.class);
    when(messageRepo.create(anyLong(), isNull(), anyString(), any(), anyString())).thenReturn(5L);
    ChatChannel channel = new ChatChannel(messageRepo, executionRepo, registry);
    DeliveryContext ctx =
        new DeliveryContext(
            1L,
            UUID.randomUUID(),
            USER,
            null,
            Optional.empty(),
            new Payload(
                Payload.PayloadType.STANDARD,
                "제목",
                "요약",
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of()));

    DeliveryResult result = TenantContext.runScopedGet(T1, () -> channel.deliver(ctx));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    assertThat(t1Conn.sent.get()).isEqualTo(1);
    assertThat(t2Conn.sent.get()).isZero();
  }

  // ------------------------------------------------------------------ //
  // helpers
  // ------------------------------------------------------------------ //

  private NotificationEvent sampleEvent() {
    return new NotificationEvent(
        UUID.randomUUID().toString(),
        "PROACTIVE_MESSAGE",
        "INFO",
        "리포트",
        "새로운 Proactive AI 리포트가 도착했습니다",
        "PROACTIVE_JOB",
        1L,
        Map.of(),
        LocalDateTime.now());
  }

  /** CHAT 수신자로 USER 만 지정한 T1 스마트 작업. */
  private ProactiveJobResponse reportJob() {
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "CHAT",
                    "recipientUserIds", List.of((int) USER),
                    "recipientEmails", List.of())));
    return new ProactiveJobResponse(
        1L,
        99L,
        null,
        null,
        "T1_주간_인사리포트",
        "prompt",
        "0 9 * * *",
        "Asia/Seoul",
        true,
        null,
        config,
        null,
        null,
        null,
        null,
        null);
  }
}
