package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ProactiveMessageResponse(
    Long id,
    Long userId,
    Long executionId,
    String title,
    Map<String, Object> content,
    String messageType,
    Boolean read,
    LocalDateTime readAt,
    String jobName,
    LocalDateTime createdAt) {}
