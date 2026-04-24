package com.smartfirehub.proactive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateProactiveJobRequest(
    @NotBlank @Size(max = 200, message = "작업 이름은 200자 이내여야 합니다") String name,
    @NotBlank String prompt,
    Long templateId,
    String cronExpression,
    String timezone,
    @NotNull Map<String, Object> config) {}
