package com.smartfirehub.notification.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.DeliveryContext;
import com.smartfirehub.notification.DeliveryResult;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChatChannel — proactive_message INSERT + SSE broadcast 경로 검증.
 *
 * <p>P2-f 이후 이 채널은 테넌트를 해석하지 않는다 — outbox 워커가 행의 {@code tenant_id} 로 스코프를
 * 열고 그 안에서 부른다. 그래서 멤버십 목이 필요 없다. 여기 남은 테넌트 관련 케이스는 R9 의
 * execution 교차테넌트 검사 하나뿐이고, 그 검사가 <b>실제로 테넌트 스코프되는지</b>는 목으로 증명할
 * 수 없어 {@code OutboxWorkerTenantScopeTest} 가 실제 DB·실제 RLS 위에서 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class ChatChannelTest {

  @Mock private ProactiveMessageRepository messageRepo;
  @Mock private ProactiveJobExecutionRepository executionRepo;
  @Mock private SseEmitterRegistry sseRegistry;

  @InjectMocks private ChatChannel channel;

  @Test
  void deliver_insertsProactiveMessageAndBroadcastsSse() {
    when(messageRepo.create(eq(99L), any(), eq("제목"), any(), eq("REPORT"))).thenReturn(42L);

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    assertThat(((DeliveryResult.Sent) result).externalMessageId()).isEqualTo("chat-msg-42");
    verify(sseRegistry).broadcast(eq(99L), any(NotificationEvent.class));
  }

  @Test
  void deliver_extractsExecutionIdFromMetadata() {
    // execution 이 현재 테넌트 컨텍스트에서 보여야 저장이 허용된다(R9 의 교차테넌트 검사).
    when(executionRepo.findById(7L))
        .thenReturn(
            Optional.of(
                new ProactiveJobExecutionResponse(
                    7L, 1L, "SUCCESS", null, null, null, Map.of(), List.of(), null)));
    when(messageRepo.create(eq(99L), eq(7L), anyString(), any(), anyString())).thenReturn(42L);

    var result = channel.deliver(ctxWithUser(99L, Map.of("executionId", 7L)));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    verify(messageRepo).create(eq(99L), eq(7L), anyString(), any(), eq("REPORT"));
  }

  @Test
  void deliver_missingUserId_returnsPermanentFailure() {
    var result = channel.deliver(ctxWithUser(null, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
    verify(messageRepo, never()).create(anyLong(), any(), anyString(), any(), anyString());
    verify(sseRegistry, never()).broadcast(anyLong(), any());
  }

  @Test
  void deliver_repoFailure_returnsTransient() {
    when(messageRepo.create(anyLong(), any(), anyString(), any(), anyString()))
        .thenThrow(new RuntimeException("DB down"));

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    verify(sseRegistry, never()).broadcast(anyLong(), any());
  }

  @Test
  void deliver_sseFailure_stillReportsSent() {
    when(messageRepo.create(anyLong(), any(), anyString(), any(), anyString())).thenReturn(42L);
    org.mockito.Mockito.doThrow(new RuntimeException("sse fail"))
        .when(sseRegistry)
        .broadcast(anyLong(), any());

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
  }

  @Test
  void deliver_contentMapIncludesCorrelationIdAndMetadata() {
    when(messageRepo.create(anyLong(), any(), anyString(), any(), anyString())).thenReturn(42L);

    UUID corr = UUID.randomUUID();
    channel.deliver(
        new DeliveryContext(
            1L, corr, 99L, null, Optional.empty(), samplePayload(Map.of("jobId", 55L))));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(messageRepo).create(eq(99L), isNull(), anyString(), captor.capture(), anyString());
    assertThat(captor.getValue())
        .containsEntry("correlationId", corr.toString())
        .containsEntry("jobId", 55L);
  }

  /**
   * execution 이 <b>현재 테넌트 컨텍스트</b>에서 보이지 않으면 메시지를 만들지 않는다(R9).
   *
   * <p>{@code payload.metadata.executionId} 는 JSON 페이로드에서 파싱된 신뢰할 수 없는 입력이라,
   * outbox 행의 테넌트와 그 execution 이 실제로 속한 테넌트가 어긋날 수 있다. FK 는 이것을
   * <b>막지 못한다</b> — PostgreSQL 의 참조 무결성 검사는 RLS 를 우회하므로 보이지 않는 타 테넌트
   * execution 을 참조해도 INSERT 가 통과한다. 이 명시 검사가 유일한 방어다. 그 사실 자체는 목이
   * 아니라 실제 DB 위에서만 보이므로 {@code OutboxWorkerTenantScopeTest} 가 함께 고정한다.
   */
  @Test
  void deliver_executionNotVisibleInCurrentTenant_returnsPermanentFailure() {
    when(executionRepo.findById(7L)).thenReturn(Optional.empty());

    var result = channel.deliver(ctxWithUser(99L, Map.of("executionId", 7L)));

    assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
    verify(messageRepo, never()).create(anyLong(), any(), anyString(), any(), anyString());
  }

  private DeliveryContext ctxWithUser(Long userId, Map<String, Object> metadata) {
    return new DeliveryContext(
        1L, UUID.randomUUID(), userId, null, Optional.empty(), samplePayload(metadata));
  }

  private Payload samplePayload(Map<String, Object> metadata) {
    return new Payload(
        Payload.PayloadType.STANDARD,
        "제목",
        "요약",
        List.of(),
        List.of(),
        List.of(),
        metadata,
        Map.of());
  }
}
