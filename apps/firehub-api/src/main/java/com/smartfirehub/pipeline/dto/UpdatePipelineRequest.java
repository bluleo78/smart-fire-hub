package com.smartfirehub.pipeline.dto;

import java.util.List;

public record UpdatePipelineRequest(
    String name,
    String description,
    Boolean isActive,
    List<PipelineStepRequest> steps  // full replacement
) {}
