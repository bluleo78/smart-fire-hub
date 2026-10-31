package com.smartfirehub.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 저장 쿼리 생성 요청. */
public record CreateSavedQueryRequest(
    @NotBlank @Size(max = 200) String name,
    String description,
    @NotBlank
        @Size(
            max = CreateSavedQueryRequest.SQL_MAX_LENGTH,
            message = CreateSavedQueryRequest.SQL_TOO_LONG_MESSAGE)
        String sqlText,
    Long datasetId,
    @Size(max = 100) String folder,
    boolean isShared) {

  /**
   * SQL 길이 상한 — 100,000자(WD-425). 상한이 없어 500만 자 SQL 도 저장됐다. 손으로 쓰거나 AI 가 만드는 분석 쿼리는 수천 자를 넘지 않는다.
   */
  public static final int SQL_MAX_LENGTH = 100_000;

  /** 상한을 넘었을 때의 안내 — 수정 요청과 같은 문구를 쓴다. */
  public static final String SQL_TOO_LONG_MESSAGE = "SQL은 최대 100,000자까지 입력할 수 있습니다.";
}
