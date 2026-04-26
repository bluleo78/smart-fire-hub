package com.smartfirehub.notification;

import com.smartfirehub.notification.repository.UserChannelBinding;
import java.util.Optional;
import java.util.UUID;

/** Channel.deliver()에 전달되는 발송 컨텍스트. 워커가 outbox 행 + binding 조회 후 구성. */
public record DeliveryContext(
    long outboxId,
    UUID correlationId,
    Long recipientUserId,
    String recipientAddress,
    Optional<UserChannelBinding> binding,
    Payload payload) {}
