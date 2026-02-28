package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

public record UpdateChartRequest(
    @Size(max = 200) String name,
    String description,
    String chartType,
    Map<String, Object> config,
    Boolean isShared) {}
