package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 데이터셋에 컬럼 추가 요청 DTO.
 *
 * <p>columnName/dataType은 컬럼 식별·DDL 생성에 필수이므로 @NotBlank로 검증한다. null/공백이 들어오면 Bean Validation이 400으로
 * 거절 — 이전엔 service에서 NPE가 발생해 의미 없는 500으로 응답하던 문제(#222)를 해결. 문구는 데이터셋 생성과 같은 한국어 문장을 명시한다(WD-587) —
 * 서버에 한국어 기본 문구가 없어 지정하지 않으면 영어 "must not be blank" 가 화면에 그대로 보였다.
 */
public record AddColumnRequest(
    @NotBlank(message = DatasetColumnRequest.COLUMN_NAME_REQUIRED_MESSAGE) String columnName,
    String displayName,
    @NotBlank(message = DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE) String dataType,
    Integer maxLength,
    boolean isNullable,
    boolean isIndexed,
    String description,
    boolean isPrimaryKey) {
  public AddColumnRequest(
      String columnName,
      String displayName,
      String dataType,
      Integer maxLength,
      boolean isNullable,
      boolean isIndexed,
      String description) {
    this(columnName, displayName, dataType, maxLength, isNullable, isIndexed, description, false);
  }
}
