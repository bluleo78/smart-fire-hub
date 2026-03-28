package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateProactiveJobRequest(
    @NotBlank String name,
    @NotBlank String prompt,
    Long templateId,
    @NotBlank String cronExpression,
    String timezone,
    @NotNull Map<String, Object> config) {}
