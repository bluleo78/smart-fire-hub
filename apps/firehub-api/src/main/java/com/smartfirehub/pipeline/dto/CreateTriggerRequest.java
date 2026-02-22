package com.smartfirehub.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateTriggerRequest(
    @NotBlank String name,
    @NotNull TriggerType triggerType,
    String description,
    Map<String, Object> config) {}
