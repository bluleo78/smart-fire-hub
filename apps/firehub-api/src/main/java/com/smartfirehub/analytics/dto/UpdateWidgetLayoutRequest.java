package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpdateWidgetLayoutRequest(List<UpdateWidgetLayoutRequest.WidgetPosition> widgets) {
  public record WidgetPosition(
      @NotNull Long widgetId,
      int positionX,
      int positionY,
      @Min(1) @Max(12) int width,
      @Min(1) @Max(12) int height) {}
}
