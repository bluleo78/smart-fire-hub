package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDashboardRequest(
    @NotBlank @Size(max = 200) String name,
    String description,
    boolean isShared,
    @Min(5) Integer autoRefreshSeconds) {}
