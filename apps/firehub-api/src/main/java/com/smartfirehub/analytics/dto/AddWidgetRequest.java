package com.smartfirehub.analytics.dto;

import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_POSITION_X;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_POSITION_Y;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_SIZE;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MIN_POSITION;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MIN_SIZE;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 대시보드 위젯 추가 요청.
 *
 * <p>WD-193: 단건 수정·배치 저장과 같은 범위 규칙(DashboardGridLimits)을 쓴다 — 추가 때만 그리드 밖 좌표가 들어가면 이후 수정 경로에서 그 위젯을
 * 다시 저장할 수 없게 되기 때문이다.
 */
public record AddWidgetRequest(
    @NotNull Long chartId,
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
