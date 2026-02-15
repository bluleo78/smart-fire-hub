package com.smartfirehub.pipeline.dto;

import java.util.List;

public record CreatePipelineRequest(
    String name,
    String description,
    List<PipelineStepRequest> steps
) {}
