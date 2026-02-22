package com.smartfirehub.pipeline.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreatePipelineRequest(
    @NotBlank String name, String description, @Valid List<PipelineStepRequest> steps) {}
