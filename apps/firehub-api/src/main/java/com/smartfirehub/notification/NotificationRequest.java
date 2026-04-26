package com.smartfirehub.notification;

import java.util.List;
import java.util.UUID;

/** 도메인이 NotificationDispatcher.enqueue로 전달하는 요청. */
public record NotificationRequest(
    String eventType,
    Long eventSourceId,
    Long createdByUserId,
    UUID correlationId, // null이면 enqueue가 자동 생성
    Payload standardPayload,
    PayloadRef payloadRef, // 참조 발송 시 (entity join 렌더), null이면 payload 직접 사용
    List<Recipient> recipients) {
  public record PayloadRef(String type, long id) {}
}
