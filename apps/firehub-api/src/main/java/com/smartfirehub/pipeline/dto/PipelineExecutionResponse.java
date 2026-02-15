package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

public record PipelineExecutionResponse(
    Long id,
    Long pipelineId,
    String status,
    String executedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt
) {}
