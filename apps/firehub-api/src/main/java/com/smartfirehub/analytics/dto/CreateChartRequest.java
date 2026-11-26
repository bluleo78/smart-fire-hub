package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.MaxJsonSize;
import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 차트 생성 요청. */
public record CreateChartRequest(
    // WD-1125: NUL(U+0000) 문자는 검증 단계에서 칸 오류로 막는다(이유는 TextRules.NO_NUL)
    // WD-1081: @NotBlank 가 못 거르는 NBSP 류를 HAS_TEXT 로 막아 수정 DTO 와 같은 판정(이유는 TextRules)
    @NotBlank(message = TextRules.NAME_BLANK_MESSAGE)
        @Size(max = 200)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.NAME_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
        String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.CHART_TOO_LONG)
        @Pattern(regexp = TextRules.NO_NUL, message = TextRules.NUL_MESSAGE)
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
   * 이름 앞뒤의 유니코드 공백(전각 U+3000·NBSP 포함)을 걷어 저장·중복 검사가 같은 값을 보게 한다(WD-1094). 공백만 다른 이름이 중복 검사를 비켜 가던
   * 것을 막는다.
   */
  public CreateChartRequest {
    name = TextRules.strip(name);
  }

  /**
   * 차트 설정(config) 직렬화 크기 상한 — 64KB(UTF-8 바이트, WD-425). 차트 목록 응답이 config 를 통째로 싣기 때문에, 상한이 없으면 공유 차트
   * 하나가 모든 사용자의 목록을 수 MB 로 부풀린다. 화면·AI 도구가 만드는 config 는 축·컬럼 이름 몇 개라 1KB 안팎이다.
   */
  public static final long CONFIG_MAX_BYTES = 64 * 1024;

  /** 상한을 넘었을 때의 안내 — 수정 요청과 같은 문구를 쓴다. */
  public static final String CONFIG_TOO_LARGE_MESSAGE = "차트 설정(config)이 너무 큽니다(최대 64KB).";
}
