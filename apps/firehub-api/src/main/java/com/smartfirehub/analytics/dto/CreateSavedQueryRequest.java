package com.smartfirehub.analytics.dto;

import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.TextRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 저장 쿼리 생성 요청. */
public record CreateSavedQueryRequest(
    // WD-1081: @NotBlank 가 못 거르는 NBSP 류를 HAS_TEXT 로 막아 수정 DTO 와 같은 판정(이유는 TextRules)
    @NotBlank(message = TextRules.NAME_BLANK_MESSAGE)
        @Size(max = 200)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.NAME_BLANK_MESSAGE)
        String name,
    @Size(max = DescriptionLimits.MAX_LENGTH, message = DescriptionLimits.QUERY_TOO_LONG)
        String description,
    // WD-1081: SQL 도 같다 — @NotBlank 는 NBSP 류를 못 거르므로 @Pattern(HAS_TEXT) 가 메운다
    @NotBlank(message = TextRules.SQL_BLANK_MESSAGE)
        @Pattern(regexp = TextRules.HAS_TEXT, message = TextRules.SQL_BLANK_MESSAGE)
        @Size(
            max = CreateSavedQueryRequest.SQL_MAX_LENGTH,
            message = CreateSavedQueryRequest.SQL_TOO_LONG_MESSAGE)
        String sqlText,
    Long datasetId,
    // WD-1086: 선택 필드라 null·"" 은 그대로("" 는 「폴더 없음」 신호, WD-197) — 공백만·NBSP·Cf 만인 값만 거절한다
    @Size(max = 100)
        @Pattern(regexp = TextRules.HAS_TEXT_OR_EMPTY, message = TextRules.FOLDER_BLANK_MESSAGE)
        String folder,
    boolean isShared) {

  /**
   * 이름 앞뒤의 유니코드 공백(전각 U+3000·NBSP 포함)을 걷어 저장·중복 검사가 같은 값을 보게 한다(WD-1094). 공백만 다른 이름이 중복 검사를 비켜 가던
   * 것을 막는다.
   */
  public CreateSavedQueryRequest {
    name = TextRules.strip(name);
  }

  /**
   * SQL 길이 상한 — 100,000자(WD-425). 상한이 없어 500만 자 SQL 도 저장됐다. 손으로 쓰거나 AI 가 만드는 분석 쿼리는 수천 자를 넘지 않는다.
   */
  public static final int SQL_MAX_LENGTH = 100_000;

  /** 상한을 넘었을 때의 안내 — 수정 요청과 같은 문구를 쓴다. */
  public static final String SQL_TOO_LONG_MESSAGE = "SQL은 최대 100,000자까지 입력할 수 있습니다.";
}
