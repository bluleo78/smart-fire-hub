package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ChartResponse(
    Long id,
    String name,
    String description,
    Long savedQueryId,
    String savedQueryName,
    String chartType,
    Map<String, Object> config,
    boolean isShared,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long dashboardCount) {}
