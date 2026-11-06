package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDashboardRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(
            max = CreateDashboardRequest.DESCRIPTION_MAX_LENGTH,
            message = CreateDashboardRequest.DESCRIPTION_TOO_LONG_MESSAGE)
        String description,
    boolean isShared,
    @Min(5)
        @Max(
            value = CreateDashboardRequest.MAX_AUTO_REFRESH_SECONDS,
            message = CreateDashboardRequest.AUTO_REFRESH_MAX_MESSAGE)
        Integer autoRefreshSeconds) {

  /**
   * 자동 새로고침 주기 상한(초) — 24시간(WD-327). 상한이 없으면 약 24.8일(2^31 ms)을 넘는 값에서 브라우저 타이머 지연이 32비트로 감겨 0 이 되고,
   * 대시보드를 연 화면이 위젯 데이터(저장 쿼리 실행)를 쉬지 않고 다시 요청한다. 하루보다 긴 자동 새로고침은 쓸모도 적다.
   */
  public static final int MAX_AUTO_REFRESH_SECONDS = 86_400;

  /** 상한을 넘었을 때의 안내 — 화면 검증 문구와 같은 뜻으로 맞춘다. */
  public static final String AUTO_REFRESH_MAX_MESSAGE = "자동 새로고침은 86400초(24시간) 이하로 입력해 주세요.";

  /**
   * 설명 길이 상한 — 2,000자(WD-425). 대시보드 목록 응답이 설명을 싣기 때문에, 상한이 없으면 공유 대시보드 하나(한글 200만 자 등)가 모든 사용자의 목록을
   * 부풀린다. 값은 다른 자산 설명과 같은 공용 상한을 쓴다(WD-520 — {@link DescriptionLimits}).
   */
  public static final int DESCRIPTION_MAX_LENGTH = DescriptionLimits.MAX_LENGTH;

  /** 설명 상한을 넘었을 때의 안내 — 화면 입력 상한(maxLength)과 같은 값이다. */
  public static final String DESCRIPTION_TOO_LONG_MESSAGE = DescriptionLimits.DASHBOARD_TOO_LONG;
}
