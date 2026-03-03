package com.smartfirehub.notification.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record NotificationEvent(
    String id,
    String eventType,
    String severity,
    String title,
    String description,
    String entityType,
    Long entityId,
    Map<String, Object> metadata,
    LocalDateTime occurredAt) {}
