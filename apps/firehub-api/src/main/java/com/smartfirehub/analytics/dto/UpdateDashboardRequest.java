package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateDashboardRequest(
    @Size(max = 200) String name,
    String description,
    Boolean isShared,
    @Min(5) Integer autoRefreshSeconds) {}
