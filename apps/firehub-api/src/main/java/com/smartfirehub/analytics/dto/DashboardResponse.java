package com.smartfirehub.analytics.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DashboardResponse(
    Long id,
    String name,
    String description,
    boolean isShared,
    Integer autoRefreshSeconds,
    List<DashboardWidgetResponse> widgets,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  public record DashboardWidgetResponse(
      Long id,
      Long chartId,
      String chartName,
      String chartType,
      int positionX,
      int positionY,
      int width,
      int height) {}
}
