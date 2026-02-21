package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record TriggerEventResponse(
    Long id,
    Long triggerId,
    String triggerName,
    String eventType,
    Long executionId,
    Map<String, Object> detail,
    LocalDateTime createdAt
) {}
