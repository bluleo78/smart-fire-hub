package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.MaxJsonSize;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 차트 수정 요청.
 *
 * @param expectedUpdatedAt 편집을 시작할 때 읽은 차트의 {@code updatedAt}(WD-364 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(기준을 보내지 않는 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdateChartRequest(
    @Size(max = 200) String name,
    String description,
    String chartType,
    // 상한은 생성과 같다(WD-425 — 이유는 CreateChartRequest.CONFIG_MAX_BYTES)
    @MaxJsonSize(
            maxBytes = CreateChartRequest.CONFIG_MAX_BYTES,
            message = CreateChartRequest.CONFIG_TOO_LARGE_MESSAGE)
        Map<String, Object> config,
    Boolean isShared,
    LocalDateTime expectedUpdatedAt) {

  /** 기준 버전 없이 만드는 기존 호출부용 — 비교하지 않는다. */
  public UpdateChartRequest(
      String name,
      String description,
      String chartType,
      Map<String, Object> config,
      Boolean isShared) {
    this(name, description, chartType, config, isShared, null);
  }
}
