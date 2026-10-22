package com.smartfirehub.proactive.service.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ChatDeliveryChannelTest extends IntegrationTestBase {

  @Autowired private ChatDeliveryChannel chatDeliveryChannel;

  @MockitoBean private ProactiveMessageRepository messageRepository;
  @MockitoBean private SseEmitterRegistry sseRegistry;
  @MockitoBean private ActiveRecipientResolver recipientResolver;

  /** 기본: 지정한 수신자가 모두 받을 수 있다(해석기를 통과시킨다). 거르는 경우는 해당 테스트가 따로 스텁한다. */
  @BeforeEach
  void allRecipientsReceivable() {
    when(recipientResolver.activeUsers(any()))
        .thenAnswer(
            inv ->
                inv.<List<Long>>getArgument(0).stream()
                    .map(id -> new UserResponse(id, "u" + id, null, "u" + id, true, null))
                    .toList());
  }

  /** WD-172: 정지·제거돼 해석기가 거른 수신자에게는 메시지도 알림도 만들지 않는다 — 남은 수신자는 그대로 받는다. */
  @Test
  void deliver_skipsRecipientsTheResolverDrops() {
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "CHAT",
                    "recipientUserIds", List.of(10, 20),
                    "recipientEmails", List.of())));
    when(recipientResolver.activeUsers(List.of(10L, 20L)))
        .thenReturn(List.of(new UserResponse(10L, "u10", null, "u10", true, null)));
    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L);

    chatDeliveryChannel.deliver(makeJob(99L, config), 1L, makeResult());

    verify(messageRepository).create(eq(10L), eq(1L), anyString(), any(), anyString());
    verify(messageRepository, never()).create(eq(20L), anyLong(), anyString(), any(), anyString());
    verify(sseRegistry, never()).broadcast(eq(20L), any());
  }

  /** WD-172: 받을 사람이 아무도 없으면 던진다 — 정상 반환이면 실행 기록에 「채팅으로 전달됨」이 거짓으로 남는다. */
  @Test
  void deliver_noReceivableRecipient_throws() {
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "CHAT",
                    "recipientUserIds", List.of(20),
                    "recipientEmails", List.of())));
    when(recipientResolver.activeUsers(List.of(20L))).thenReturn(List.of());

    assertThatThrownBy(() -> chatDeliveryChannel.deliver(makeJob(99L, config), 1L, makeResult()))
        .hasMessageContaining("받을 수 있는 수신자가 없습니다");
    verify(messageRepository, never())
        .create(anyLong(), anyLong(), anyString(), any(), anyString());
  }

  private ProactiveResult makeResult() {
    return new ProactiveResult("Test Report", List.of(), null, null, null);
  }

  /**
   * id, userId, templateId, templateName, name, prompt, cronExpression, timezone, enabled, config,
   * lastExecutedAt, nextExecuteAt, createdAt, updatedAt, lastExecution
   */
  private ProactiveJobResponse makeJob(Long userId, Map<String, Object> config) {
    return new ProactiveJobResponse(
        1L,
        userId,
        null,
        null,
        "Test Job",
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

  @Test
  void deliver_withRecipientUserIds_createsMessageForEach() {
    // 3 recipients in new-format config
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "CHAT",
                    "recipientUserIds", List.of(10, 20, 30),
                    "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L)
        .thenReturn(101L)
        .thenReturn(102L);

    chatDeliveryChannel.deliver(job, 1L, makeResult());

    // 3 messages created, each to the correct userId
    verify(messageRepository, times(3)).create(anyLong(), eq(1L), anyString(), any(), anyString());
    verify(sseRegistry).broadcast(eq(10L), any());
    verify(sseRegistry).broadcast(eq(20L), any());
    verify(sseRegistry).broadcast(eq(30L), any());
    // job owner (99) must NOT receive directly
    verify(sseRegistry, never()).broadcast(eq(99L), any());
  }

  @Test
  void deliver_withEmptyRecipients_defaultsToJobOwner() {
    // new-format config with empty recipientUserIds -> fall back to owner
    Map<String, Object> config =
        Map.of(
            "channels",
            List.of(
                Map.of(
                    "type", "CHAT",
                    "recipientUserIds", List.of(),
                    "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L);

    chatDeliveryChannel.deliver(job, 1L, makeResult());

    verify(messageRepository, times(1)).create(eq(99L), eq(1L), anyString(), any(), anyString());
    verify(sseRegistry).broadcast(eq(99L), any());
  }

  @Test
  void deliver_withOldConfig_defaultsToJobOwner() {
    // old-format config: channels as string list
    Map<String, Object> config = Map.of("channels", List.of("CHAT"), "targets", "ALL");
    ProactiveJobResponse job = makeJob(99L, config);

    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L);

    chatDeliveryChannel.deliver(job, 1L, makeResult());

    // old format -> recipientUserIds empty -> falls back to job owner
    verify(messageRepository, times(1)).create(eq(99L), eq(1L), anyString(), any(), anyString());
    verify(sseRegistry).broadcast(eq(99L), any());
  }
}
