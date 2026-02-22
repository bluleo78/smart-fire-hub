package com.smartfirehub.pipeline.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PipelineDetailResponse(
    Long id,
    String name,
    String description,
    boolean isActive,
    String createdBy,
    List<PipelineStepResponse> steps,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String updatedBy) {}
