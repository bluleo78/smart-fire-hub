package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AnalyticsQueryRequest(
    @NotBlank String sql, @Min(1) @Max(10000) Integer maxRows, Boolean readOnly) {}
