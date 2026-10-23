package com.smartfirehub.analytics.dto;

import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_POSITION_X;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_POSITION_Y;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_SIZE;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_WIDGETS_PER_DASHBOARD;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MIN_POSITION;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MIN_SIZE;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 위젯 배치 일괄 저장 요청.
 *
 * <p>WD-193: 목록 자체가 null 이면 서비스에서 NPE(500)가 나고, 원소에 {@code @Valid} 가 없으면 원소의 범위 제약이 아예 검사되지 않았다. 목록
 * {@code @NotNull} + 원소 {@code @NotNull @Valid} 로 null 목록·null 원소·범위 밖 값을 모두 400 으로 거른다
 * ({@code @Valid} 만으로는 null 원소를 건너뛰므로 원소 {@code @NotNull} 도 필요하다). 목록 길이는 대시보드 위젯 상한으로 막아, 아주 큰 목록이
 * 소속 확인 조회의 IN 조건을 DB 한계 너머로 키워 500 이 나지 않게 한다.
 */
public record UpdateWidgetLayoutRequest(
    @NotNull(message = "위젯 배치 목록(widgets)은 필수입니다.")
        @Size(max = MAX_WIDGETS_PER_DASHBOARD, message = "위젯 배치는 한 번에 {max}개까지 저장할 수 있습니다.")
        List<@NotNull(message = "위젯 배치 항목은 비어 있을 수 없습니다.") @Valid WidgetPosition> widgets) {

  /** 위젯 한 개의 배치 — 범위는 DashboardGridLimits 를 단건 수정·추가와 함께 쓴다. */
  public record WidgetPosition(
      @NotNull(message = "위젯 ID(widgetId)는 필수입니다.") Long widgetId,
      @Min(value = MIN_POSITION, message = "열 위치(positionX)는 {value} 이상이어야 합니다.")
          @Max(value = MAX_POSITION_X, message = "열 위치(positionX)는 {value} 이하여야 합니다.")
          int positionX,
      @Min(value = MIN_POSITION, message = "행 위치(positionY)는 {value} 이상이어야 합니다.")
          @Max(value = MAX_POSITION_Y, message = "행 위치(positionY)는 {value} 이하여야 합니다.")
          int positionY,
      @Min(value = MIN_SIZE, message = "너비(width)는 {value} 이상이어야 합니다.")
          @Max(value = MAX_SIZE, message = "너비(width)는 {value} 이하여야 합니다.")
          int width,
      @Min(value = MIN_SIZE, message = "높이(height)는 {value} 이상이어야 합니다.")
          @Max(value = MAX_SIZE, message = "높이(height)는 {value} 이하여야 합니다.")
          int height) {}
}
