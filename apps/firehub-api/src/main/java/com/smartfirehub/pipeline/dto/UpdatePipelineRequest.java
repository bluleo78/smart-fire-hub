package com.smartfirehub.pipeline.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record UpdatePipelineRequest(
    @NotBlank String name,
    String description,
    Boolean isActive,
    @Valid List<PipelineStepRequest> steps // full replacement
    ) {}
