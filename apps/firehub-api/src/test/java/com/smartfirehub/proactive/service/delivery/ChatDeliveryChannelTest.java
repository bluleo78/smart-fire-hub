package com.smartfirehub.proactive.service.delivery;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ChatDeliveryChannelTest extends IntegrationTestBase {

  @Autowired private ChatDeliveryChannel chatDeliveryChannel;

  @MockitoBean private ProactiveMessageRepository messageRepository;
  @MockitoBean private SseEmitterRegistry sseRegistry;

  private ProactiveResult makeResult() {
    return new ProactiveResult("Test Report", List.of(), null);
  }

  /** id, userId, templateId, templateName, name, prompt, cronExpression, timezone,
   *  enabled, config, lastExecutedAt, nextExecuteAt, createdAt, updatedAt, lastExecution */
  private ProactiveJobResponse makeJob(Long userId, Map<String, Object> config) {
    return new ProactiveJobResponse(
        1L, userId, null, null, "Test Job", "prompt",
        "0 9 * * *", "Asia/Seoul", true, config,
        null, null, null, null, null);
  }

  @Test
  void deliver_withRecipientUserIds_createsMessageForEach() {
    // 3 recipients in new-format config
    Map<String, Object> config = Map.of(
        "channels", List.of(
            Map.of("type", "CHAT",
                   "recipientUserIds", List.of(10, 20, 30),
                   "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L).thenReturn(101L).thenReturn(102L);

    chatDeliveryChannel.deliver(job, 1L, makeResult());

    // 3 messages created, each to the correct userId
    verify(messageRepository, times(3))
        .create(anyLong(), eq(1L), anyString(), any(), anyString());
    verify(sseRegistry).broadcast(eq(10L), any());
    verify(sseRegistry).broadcast(eq(20L), any());
    verify(sseRegistry).broadcast(eq(30L), any());
    // job owner (99) must NOT receive directly
    verify(sseRegistry, never()).broadcast(eq(99L), any());
  }

  @Test
  void deliver_withEmptyRecipients_defaultsToJobOwner() {
    // new-format config with empty recipientUserIds -> fall back to owner
    Map<String, Object> config = Map.of(
        "channels", List.of(
            Map.of("type", "CHAT",
                   "recipientUserIds", List.of(),
                   "recipientEmails", List.of())));
    ProactiveJobResponse job = makeJob(99L, config);

    when(messageRepository.create(anyLong(), anyLong(), anyString(), any(), anyString()))
        .thenReturn(100L);

    chatDeliveryChannel.deliver(job, 1L, makeResult());

    verify(messageRepository, times(1))
        .create(eq(99L), eq(1L), anyString(), any(), anyString());
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
    verify(messageRepository, times(1))
        .create(eq(99L), eq(1L), anyString(), any(), anyString());
    verify(sseRegistry).broadcast(eq(99L), any());
  }
}
