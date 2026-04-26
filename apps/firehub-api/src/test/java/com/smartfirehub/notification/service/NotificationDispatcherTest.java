package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.NotificationOutboxRepository.NotificationOutboxRow;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/** NotificationDispatcher 라우팅/멱등성 키/forcedChat 분기 단위 검증. */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

  private final RoutingResolver routingResolver = org.mockito.Mockito.mock(RoutingResolver.class);
  private final NotificationOutboxRepository outboxRepo =
      org.mockito.Mockito.mock(NotificationOutboxRepository.class);
  private final IdempotencyKeyGenerator keyGen = new IdempotencyKeyGenerator();
  private final OutboxNotifier notifier = org.mockito.Mockito.mock(OutboxNotifier.class);
  private final ObjectMapper objectMapper = new ObjectMapper();

  private NotificationDispatcher enabled() {
    return new NotificationDispatcher(
        routingResolver, outboxRepo, keyGen, notifier, objectMapper, true);
  }

  private NotificationDispatcher disabled() {
    return new NotificationDispatcher(
        routingResolver, outboxRepo, keyGen, notifier, objectMapper, false);
  }

  @Test
  void enqueue_insertsOneRowPerResolvedChannel() {
    Recipient r = new Recipient(1L, null, EnumSet.of(ChannelType.SLACK, ChannelType.EMAIL));
    NotificationRequest req =
        new NotificationRequest(
            "TEST_EVENT", null, 99L, UUID.randomUUID(), samplePayload(), null, List.of(r));
    when(routingResolver.resolve(r))
        .thenReturn(
            new ResolvedRouting(List.of(ChannelType.SLACK, ChannelType.EMAIL), Map.of(), false));

    enabled().enqueue(req);

    ArgumentCaptor<NotificationOutboxRow> captor =
        ArgumentCaptor.forClass(NotificationOutboxRow.class);
    verify(outboxRepo, times(2)).insertIfAbsent(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(NotificationOutboxRow::channelType)
        .containsExactlyInAnyOrder(ChannelType.SLACK, ChannelType.EMAIL);
    verify(notifier).notifyOutboxNew();
  }

  @Test
  void enqueue_advisesUserWhenForcedChatFallback() {
    Recipient r = new Recipient(1L, null, EnumSet.of(ChannelType.SLACK));
    NotificationRequest req =
        new NotificationRequest(
            "TEST_EVENT", null, 99L, UUID.randomUUID(), samplePayload(), null, List.of(r));
    when(routingResolver.resolve(r))
        .thenReturn(
            new ResolvedRouting(
                List.of(ChannelType.CHAT), Map.of(ChannelType.SLACK, "BINDING_MISSING"), true));

    enabled().enqueue(req);

    // CHAT 본문 1건 + CHANNEL_ADVISORY 1건 = 2 INSERT
    verify(outboxRepo, times(2)).insertIfAbsent(any());
  }

  @Test
  void enqueue_disabledFlag_doesNothing() {
    Recipient r = new Recipient(1L, null, EnumSet.of(ChannelType.CHAT));
    NotificationRequest req =
        new NotificationRequest(
            "TEST_EVENT", null, 99L, UUID.randomUUID(), samplePayload(), null, List.of(r));

    disabled().enqueue(req);

    verify(outboxRepo, never()).insertIfAbsent(any());
    verify(notifier, never()).notifyOutboxNew();
  }

  @Test
  void enqueue_autoGeneratesCorrelationIdWhenNull() {
    Recipient r = new Recipient(1L, null, EnumSet.of(ChannelType.CHAT));
    NotificationRequest req =
        new NotificationRequest(
            "TEST_EVENT",
            null,
            99L,
            null, // null correlationId
            samplePayload(),
            null,
            List.of(r));
    when(routingResolver.resolve(r))
        .thenReturn(new ResolvedRouting(List.of(ChannelType.CHAT), Map.of(), false));

    enabled().enqueue(req);

    ArgumentCaptor<NotificationOutboxRow> captor =
        ArgumentCaptor.forClass(NotificationOutboxRow.class);
    verify(outboxRepo).insertIfAbsent(captor.capture());
    assertThat(captor.getValue().correlationId()).isNotNull();
  }

  private Payload samplePayload() {
    return new Payload(
        Payload.PayloadType.STANDARD,
        "t",
        "s",
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of());
  }
}
