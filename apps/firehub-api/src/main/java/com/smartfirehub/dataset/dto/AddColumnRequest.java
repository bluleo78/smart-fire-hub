package com.smartfirehub.dataset.dto;

import com.smartfirehub.dataset.service.ColumnPhysicalType;
import jakarta.validation.constraints.NotBlank;

/**
 * 데이터셋에 컬럼 추가 요청 DTO.
 *
 * <p>columnName/dataType은 컬럼 식별·DDL 생성에 필수이므로 @NotBlank로 검증한다. null/공백이 들어오면 Bean Validation이 400으로
 * 거절 — 이전엔 service에서 NPE가 발생해 의미 없는 500으로 응답하던 문제(#222)를 해결. 문구는 데이터셋 생성과 같은 한국어 문장을 명시한다(WD-587) —
 * 당시엔 서버에 한국어 기본 문구가 없어 영어 "must not be blank" 가 보였다(WD-570 이후 기본 문구는 「필수 입력값입니다.」지만, 무엇이 빠졌는지 말하는
 * 구체 문구를 유지한다).
 */
public record AddColumnRequest(
    @NotBlank(message = DatasetColumnRequest.COLUMN_NAME_REQUIRED_MESSAGE) String columnName,
    String displayName,
    @NotBlank(message = DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE) String dataType,
    Integer maxLength,
    Boolean isNullable,
    Boolean isIndexed,
    String description,
    Boolean isPrimaryKey) {
  /**
   * 타입을 입력 경계에서 정규화한다(WD-636) — 소문자·앞뒤 공백 타입도 받아 대문자로 저장한다({@code
   * ColumnPhysicalType#normalizeDataType}).
   */
  public AddColumnRequest {
    dataType = ColumnPhysicalType.normalizeDataType(dataType);
    // WD-698: 누락·null 옵션을 SQL 기본으로 채운다 — 규칙은 {@link DatasetColumnRequest#defaultNullable}.
    isPrimaryKey = Boolean.TRUE.equals(isPrimaryKey);
    isIndexed = Boolean.TRUE.equals(isIndexed);
    isNullable = DatasetColumnRequest.defaultNullable(isNullable, isPrimaryKey);
  }

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
