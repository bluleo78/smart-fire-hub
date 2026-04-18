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

/** ChatChannel — proactive_message INSERT + SSE broadcast 경로 검증. */
@ExtendWith(MockitoExtension.class)
class ChatChannelTest {

    @Mock private ProactiveMessageRepository messageRepo;
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
                .when(sseRegistry).broadcast(anyLong(), any());

        var result = channel.deliver(ctxWithUser(99L, Map.of()));

        assertThat(result).isInstanceOf(DeliveryResult.Sent.class);
    }

    @Test
    void deliver_contentMapIncludesCorrelationIdAndMetadata() {
        when(messageRepo.create(anyLong(), any(), anyString(), any(), anyString())).thenReturn(42L);

        UUID corr = UUID.randomUUID();
        channel.deliver(new DeliveryContext(1L, corr, 99L, null, Optional.empty(),
                samplePayload(Map.of("jobId", 55L))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messageRepo).create(eq(99L), isNull(), anyString(), captor.capture(), anyString());
        assertThat(captor.getValue())
                .containsEntry("correlationId", corr.toString())
                .containsEntry("jobId", 55L);
    }

    private DeliveryContext ctxWithUser(Long userId, Map<String, Object> metadata) {
        return new DeliveryContext(1L, UUID.randomUUID(), userId, null, Optional.empty(),
                samplePayload(metadata));
    }

    private Payload samplePayload(Map<String, Object> metadata) {
        return new Payload(Payload.PayloadType.STANDARD, "제목", "요약",
                List.of(), List.of(), List.of(),
                metadata, Map.of());
    }
}
