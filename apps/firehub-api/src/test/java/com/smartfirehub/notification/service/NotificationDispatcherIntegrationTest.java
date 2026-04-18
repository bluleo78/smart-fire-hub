package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.NotificationRequest;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.Recipient;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Dispatcher → Outbox 통합: 멱등성 키가 중복 INSERT 차단하는지 검증. */
@TestPropertySource(properties = "notification.outbox.enabled=true")
class NotificationDispatcherIntegrationTest extends IntegrationTestBase {

    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private NotificationOutboxRepository repo;

    @Test
    void enqueue_idempotentOnSameRequest() {
        UUID corr = UUID.randomUUID();
        NotificationRequest req = chatRequest(corr);

        dispatcher.enqueue(req);
        dispatcher.enqueue(req);   // 같은 correlationId → idempotency_key 동일 → ON CONFLICT DO NOTHING

        var rows = repo.findByCorrelation(corr);
        // CHAT 1건만 존재, advisory 없음 (CHAT 요청이 resolved 이므로 forcedChatFallback=false)
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).channelType()).isEqualTo(ChannelType.CHAT);
    }

    private NotificationRequest chatRequest(UUID corr) {
        return new NotificationRequest(
                "TEST_INTEGRATION",
                null,
                null,
                corr,
                new Payload(Payload.PayloadType.STANDARD, "t", "s",
                        List.of(), List.of(), List.of(), Map.of(), Map.of()),
                null,
                List.of(new Recipient(null, null, EnumSet.of(ChannelType.CHAT)))
        );
    }
}
