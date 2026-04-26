package com.smartfirehub.notification;

/** Channel.deliver() 결과. sealed로 강제하여 워커가 모든 경우 처리. */
public sealed interface DeliveryResult {
  record Sent(String externalMessageId) implements DeliveryResult {}

  record TransientFailure(String reason, Throwable cause) implements DeliveryResult {}

  record PermanentFailure(PermanentFailureReason reason, String details)
      implements DeliveryResult {}
}
