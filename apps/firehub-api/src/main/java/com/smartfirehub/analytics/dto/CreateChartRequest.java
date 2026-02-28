package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateChartRequest(
    @NotBlank @Size(max = 200) String name,
    String description,
    @NotNull Long savedQueryId,
    @NotBlank String chartType,
    @NotNull Map<String, Object> config,
    boolean isShared) {}
