package com.smartfirehub.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record UpdateTriggerRequest(
    @NotBlank String name, Boolean isEnabled, String description, Map<String, Object> config) {}
