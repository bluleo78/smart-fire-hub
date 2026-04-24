package com.smartfirehub.pipeline.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePipelineRequest(
    @NotBlank @Size(max = 100, message = "파이프라인 이름은 100자 이내여야 합니다") String name,
    String description,
    @Valid List<PipelineStepRequest> steps) {}
