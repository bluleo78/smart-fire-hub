package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

public record PipelineExecutionResponse(
    Long id,
    Long pipelineId,
    String status,
    String executedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime createdAt,
    String triggeredBy,
    String triggerName
) {
    /**
     * Backward-compatible constructor without trigger fields.
     */
    public PipelineExecutionResponse(
            Long id, Long pipelineId, String status, String executedBy,
            LocalDateTime startedAt, LocalDateTime completedAt, LocalDateTime createdAt) {
        this(id, pipelineId, status, executedBy, startedAt, completedAt, createdAt, "MANUAL", null);
    }
}
