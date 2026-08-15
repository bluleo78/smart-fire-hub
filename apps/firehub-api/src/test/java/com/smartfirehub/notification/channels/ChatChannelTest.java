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
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.repository.MembershipRepository;
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
 * <p>P2-e 이후에는 저장 테넌트 해석도 이 채널의 책임이다(워커 스레드에는 컨텍스트가 없다). 그래서
 * 대부분의 케이스가 "수신자에게 ACTIVE 멤버십이 정확히 1개" 라는 전제를 세우고 시작한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatChannelTest {

  @Mock private ProactiveMessageRepository messageRepo;
  @Mock private ProactiveJobExecutionRepository executionRepo;
  @Mock private MembershipRepository membershipRepository;
  @Mock private SseEmitterRegistry sseRegistry;

  @InjectMocks private ChatChannel channel;

  private static final long TENANT_ID = 7001L;

  /** 수신자에게 ACTIVE 멤버십 1개를 준다 — 저장 테넌트가 모호하지 않은 정상 상태. */
  private void givenSoleMembership(long userId) {
    when(membershipRepository.findActiveByUser(userId))
        .thenReturn(List.of(new MembershipResponse(TENANT_ID, "t", "T", "MEMBER")));
  }

  @Test
  void deliver_insertsProactiveMessageAndBroadcastsSse() {
    givenSoleMembership(99L);
    when(messageRepo.create(eq(99L), any(), eq("제목"), any(), eq("REPORT"))).thenReturn(42L);

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    assertThat(((DeliveryResult.Sent) result).externalMessageId()).isEqualTo("chat-msg-42");
    verify(sseRegistry).broadcast(eq(99L), any(NotificationEvent.class));
  }

  @Test
  void deliver_extractsExecutionIdFromMetadata() {
    givenSoleMembership(99L);
    // execution 이 수신자 테넌트에서 보여야 저장이 허용된다(R1 의 거부권 형태).
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
    givenSoleMembership(99L);
    when(messageRepo.create(anyLong(), any(), anyString(), any(), anyString()))
        .thenThrow(new RuntimeException("DB down"));

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.TransientFailure.class);
    verify(sseRegistry, never()).broadcast(anyLong(), any());
  }

  @Test
  void deliver_sseFailure_stillReportsSent() {
    givenSoleMembership(99L);
    when(messageRepo.create(anyLong(), any(), anyString(), any(), anyString())).thenReturn(42L);
    org.mockito.Mockito.doThrow(new RuntimeException("sse fail"))
        .when(sseRegistry)
        .broadcast(anyLong(), any());

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
  }

  @Test
  void deliver_contentMapIncludesCorrelationIdAndMetadata() {
    givenSoleMembership(99L);
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
   * 수신자의 테넌트를 확정할 수 없으면 저장하지 않는다(P2-e, fail-closed).
   *
   * <p>임의의 기본 테넌트로 떨어뜨리면 수신자가 속하지도 않은 워크스페이스에 메시지가 쌓이고, 정작
   * 수신자에게는 RLS 로 0행이라 보이지 않는다 — 조용한 열화보다 눈에 띄는 영구 실패가 낫다.
   */
  @Test
  void deliver_ambiguousTenant_returnsPermanentFailureWithoutInsert() {
    when(membershipRepository.findActiveByUser(99L))
        .thenReturn(
            List.of(
                new MembershipResponse(1L, "a", "A", "MEMBER"),
                new MembershipResponse(2L, "b", "B", "MEMBER")));

    var result = channel.deliver(ctxWithUser(99L, Map.of()));

    assertThat(result).isInstanceOf(DeliveryResult.PermanentFailure.class);
    verify(messageRepo, never()).create(anyLong(), any(), anyString(), any(), anyString());
  }

  /**
   * execution 이 수신자 테넌트에서 보이지 않으면 메시지를 만들지 않는다(사전 판정 R1).
   *
   * <p>메시지의 테넌트 출처는 둘(수신자 / 그 메시지를 만들어낸 실행)이고 서로 어긋날 수 있다.
   * execution 행 자체가 RLS 대상이라 "먼저 읽어 테넌트를 알아내는" 조회는 불가능하므로, 조회가 아니라
   * <b>거부권</b>으로 우선순위를 구현한다 — 어긋나면 아예 저장하지 않는다.
   */
  @Test
  void deliver_executionNotVisibleInRecipientTenant_returnsPermanentFailure() {
    givenSoleMembership(99L);
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
