package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 대시보드 설정(이름·설명·공개·자동 새로고침) 수정 요청.
 *
 * @param expectedUpdatedAt 설정 창을 열 때 읽은 대시보드의 {@code updatedAt}(WD-364 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(기준을 보내지 않는 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdateDashboardRequest(
    @Size(max = 200) String name,
    String description,
    Boolean isShared,
    // 상한은 생성과 같다(WD-327 — 이유는 CreateDashboardRequest.MAX_AUTO_REFRESH_SECONDS)
    @Min(5)
        @Max(
            value = CreateDashboardRequest.MAX_AUTO_REFRESH_SECONDS,
            message = CreateDashboardRequest.AUTO_REFRESH_MAX_MESSAGE)
        Integer autoRefreshSeconds,
    // autoRefreshSeconds가 null인 이유가 "미제공(변경 없음)"인지 "명시적으로 지움"인지
    // Integer 하나로는 구분할 수 없어 별도 플래그로 분리한다(#568).
    // true면 autoRefreshSeconds가 null이어도 DB 값을 실제로 null로 초기화한다.
    Boolean clearAutoRefresh,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdateDashboardRequest(
      String name,
      String description,
      Boolean isShared,
      Integer autoRefreshSeconds,
      Boolean clearAutoRefresh) {
    this(name, description, isShared, autoRefreshSeconds, clearAutoRefresh, null);
  }
}
