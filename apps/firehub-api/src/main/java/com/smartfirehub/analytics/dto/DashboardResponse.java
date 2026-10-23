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
    int widgetCount,
    String createdByName,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  /**
   * 대시보드 위젯.
   *
   * <p>WD-195: {@code accessible} 은 조회자가 이 위젯의 차트를 볼 수 있는지(본인 소유 또는 공유)다. false 면 차트 이름·종류를 싣지 않고
   * null 로 둔다 — 비공개 차트 이름이 공유 대시보드로 새지 않게. 위치·크기는 그대로 실어 조회자 화면의 배치가 무너지지 않게 한다. 보안 등급으로 원본 데이터를 못
   * 보는 경우(차트 데이터의 denied)와는 다른 축이다 — 그 차트는 보이는 차트라 accessible=true 이고 이름도 그대로다.
   */
  public record DashboardWidgetResponse(
      Long id,
      Long chartId,
      String chartName,
      String chartType,
      int positionX,
      int positionY,
      int width,
      int height,
      boolean accessible) {

    /** 조회자가 볼 수 없는 위젯으로 바꾼다 — 차트 이름·종류를 지우고 위치·크기만 남긴다(WD-195). */
    public DashboardWidgetResponse withheld() {
      return new DashboardWidgetResponse(
          id, chartId, null, null, positionX, positionY, width, height, false);
    }
  }
}
