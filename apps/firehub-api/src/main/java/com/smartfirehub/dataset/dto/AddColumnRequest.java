package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 데이터셋에 컬럼 추가 요청 DTO.
 *
 * <p>columnName/dataType은 컬럼 식별·DDL 생성에 필수이므로 @NotBlank로 검증한다. null/공백이 들어오면 Bean
 * Validation이 400으로 거절 — 이전엔 service에서 NPE가 발생해 의미 없는 500으로 응답하던 문제(#222)를 해결.
 */
public record AddColumnRequest(
    @NotBlank String columnName,
    String displayName,
    @NotBlank String dataType,
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
