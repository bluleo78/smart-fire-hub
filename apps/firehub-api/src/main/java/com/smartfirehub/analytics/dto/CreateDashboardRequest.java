package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDashboardRequest(
    @NotBlank @Size(max = 200) String name,
    String description,
    boolean isShared,
    @Min(5)
        @Max(
            value = CreateDashboardRequest.MAX_AUTO_REFRESH_SECONDS,
            message = CreateDashboardRequest.AUTO_REFRESH_MAX_MESSAGE)
        Integer autoRefreshSeconds) {

  /**
   * 자동 새로고침 주기 상한(초) — 24시간(WD-327). 상한이 없으면 약 24.8일(2^31 ms)을 넘는 값에서 브라우저 타이머 지연이 32비트로 감겨 0 이
   * 되고, 대시보드를 연 화면이 위젯 데이터(저장 쿼리 실행)를 쉬지 않고 다시 요청한다. 하루보다 긴 자동 새로고침은 쓸모도 적다.
   */
  public static final int MAX_AUTO_REFRESH_SECONDS = 86_400;

  /** 상한을 넘었을 때의 안내 — 화면 검증 문구와 같은 뜻으로 맞춘다. */
  public static final String AUTO_REFRESH_MAX_MESSAGE = "자동 새로고침은 86400초(24시간) 이하로 입력해 주세요.";
}
