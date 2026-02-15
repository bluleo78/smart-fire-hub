package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExecutionDetailResponse(
    Long id,
    Long pipelineId,
    String pipelineName,
    String status,
    String executedBy,
    List<StepExecutionResponse> stepExecutions,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt
) {}
