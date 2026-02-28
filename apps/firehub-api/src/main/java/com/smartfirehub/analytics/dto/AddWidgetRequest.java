package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddWidgetRequest(
    @NotNull Long chartId,
    int positionX,
    int positionY,
    @Min(1) @Max(12) int width,
    @Min(1) @Max(12) int height) {}
