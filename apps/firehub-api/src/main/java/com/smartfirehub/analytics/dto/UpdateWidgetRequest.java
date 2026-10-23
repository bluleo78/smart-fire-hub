package com.smartfirehub.analytics.dto;

import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_POSITION_X;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_POSITION_Y;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MAX_SIZE;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MIN_POSITION;
import static com.smartfirehub.analytics.dto.DashboardGridLimits.MIN_SIZE;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 위젯 단건 수정 요청(부분 수정 — null 인 필드는 바꾸지 않는다).
 *
 * <p>WD-193: 배치 일괄 저장과 같은 범위 규칙(DashboardGridLimits)을 쓴다. 좌표에는 원래 제약이 없어 음수·그리드 밖 좌표가 저장될 수 있었다.
 */
public record UpdateWidgetRequest(
    @Min(value = MIN_POSITION, message = "열 위치(positionX)는 {value} 이상이어야 합니다.")
        @Max(value = MAX_POSITION_X, message = "열 위치(positionX)는 {value} 이하여야 합니다.")
        Integer positionX,
    @Min(value = MIN_POSITION, message = "행 위치(positionY)는 {value} 이상이어야 합니다.")
        @Max(value = MAX_POSITION_Y, message = "행 위치(positionY)는 {value} 이하여야 합니다.")
        Integer positionY,
    @Min(value = MIN_SIZE, message = "너비(width)는 {value} 이상이어야 합니다.")
        @Max(value = MAX_SIZE, message = "너비(width)는 {value} 이하여야 합니다.")
        Integer width,
    @Min(value = MIN_SIZE, message = "높이(height)는 {value} 이상이어야 합니다.")
        @Max(value = MAX_SIZE, message = "높이(height)는 {value} 이하여야 합니다.")
        Integer height) {}
