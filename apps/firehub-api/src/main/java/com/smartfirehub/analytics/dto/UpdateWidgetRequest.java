package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateWidgetRequest(
    Integer positionX,
    Integer positionY,
    @Min(1) @Max(12) Integer width,
    @Min(1) @Max(12) Integer height) {}
