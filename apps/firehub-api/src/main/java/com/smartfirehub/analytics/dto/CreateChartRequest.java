package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.MaxJsonSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 차트 생성 요청. */
public record CreateChartRequest(
    @NotBlank @Size(max = 200) String name,
    String description,
    @NotNull Long savedQueryId,
    @NotBlank String chartType,
    @NotNull
        @MaxJsonSize(
            maxBytes = CreateChartRequest.CONFIG_MAX_BYTES,
            message = CreateChartRequest.CONFIG_TOO_LARGE_MESSAGE)
        Map<String, Object> config,
    boolean isShared) {

  /**
   * 차트 설정(config) 직렬화 크기 상한 — 64KB(UTF-8 바이트, WD-425). 차트 목록 응답이 config 를 통째로 싣기 때문에, 상한이 없으면 공유 차트
   * 하나가 모든 사용자의 목록을 수 MB 로 부풀린다. 화면·AI 도구가 만드는 config 는 축·컬럼 이름 몇 개라 1KB 안팎이다.
   */
  public static final long CONFIG_MAX_BYTES = 64 * 1024;

  /** 상한을 넘었을 때의 안내 — 수정 요청과 같은 문구를 쓴다. */
  public static final String CONFIG_TOO_LARGE_MESSAGE = "차트 설정(config)이 너무 큽니다(최대 64KB).";
}
