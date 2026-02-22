package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;

public record PipelineResponse(
    Long id,
    String name,
    String description,
    boolean isActive,
    String createdBy,
    int stepCount,
    LocalDateTime createdAt) {}
