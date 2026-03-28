package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ProactiveJobResponse(
    Long id,
    Long userId,
    Long templateId,
    String templateName,
    String name,
    String prompt,
    String cronExpression,
    String timezone,
    Boolean enabled,
    Map<String, Object> config,
    LocalDateTime lastExecutedAt,
    LocalDateTime nextExecuteAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    ProactiveJobExecutionResponse lastExecution) {}
