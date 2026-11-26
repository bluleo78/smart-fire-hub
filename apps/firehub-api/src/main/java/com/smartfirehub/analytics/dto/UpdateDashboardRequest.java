package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 대시보드 설정(이름·설명·공개·자동 새로고침) 수정 요청.
 *
 * <p>이름은 생략(null, 변경 없음)은 되지만 빈 값·공백만으로 바꿀 수 없다(WD-1079 — 생성도 같은 판정(WD-1081), 저장 쿼리는 WD-446 과 같음).
 *
 * @param expectedUpdatedAt 설정 창을 열 때 읽은 대시보드의 {@code updatedAt}(WD-364 낙관적 잠금). 지금 저장된 값과 다르면 409
 *     EDIT_CONFLICT 로 거부한다. null 이면 비교하지 않는다(기준을 보내지 않는 기존 호출부, "내 변경으로 덮어쓰기").
 */
public record UpdateDashboardRequest(
    // WD-1125: NUL(U+0000) 문자는 검증 단계에서 칸 오류로 막는다(이유는 TextRules.NO_NUL)
    // null 은 통과하고(생략), 값이 있으면 공백 아닌 글자가 하나 이상 있어야 한다(WD-1079)
    @Size(max = 200)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.NAME_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String name,
    // 상한은 생성과 같다(WD-425 — 이유는 CreateDashboardRequest.DESCRIPTION_MAX_LENGTH)
    @Size(
            max = CreateDashboardRequest.DESCRIPTION_MAX_LENGTH,
            message = CreateDashboardRequest.DESCRIPTION_TOO_LONG_MESSAGE)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
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

  /** 이름 앞뒤 유니코드 공백을 걷는다(WD-1094). null(변경 없음)은 그대로 null 로 둔다. */
  public UpdateDashboardRequest {
    name = TextRules.strip(name);
  }

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
