package com.smartfirehub.analytics.dto;

import java.util.List;

public record DashboardDataResponse(
    DashboardResponse dashboard, List<DashboardDataResponse.WidgetData> widgetData) {

  public record WidgetData(Long widgetId, ChartDataResponse chartData) {}
}
