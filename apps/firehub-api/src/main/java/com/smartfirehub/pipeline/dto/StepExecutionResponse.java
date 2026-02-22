package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

public record StepExecutionResponse(
    Long id,
    Long stepId,
    String stepName,
    String status,
    Integer outputRows,
    String log,
    String errorMessage,
    LocalDateTime startedAt,
    LocalDateTime completedAt) {}
