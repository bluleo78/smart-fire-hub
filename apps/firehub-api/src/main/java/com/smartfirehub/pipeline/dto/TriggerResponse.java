package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record TriggerResponse(
    Long id,
    Long pipelineId,
    String triggerType,
    String name,
    String description,
    boolean isEnabled,
    Map<String, Object> config,
    Map<String, Object> triggerState,
    Long createdBy,
    LocalDateTime createdAt) {}
