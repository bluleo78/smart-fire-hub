package com.smartfirehub.dataset.dto;

import com.smartfirehub.dataset.service.ColumnPhysicalType;

/**
 * 필드 수정 요청 DTO — 모든 항목이 선택(null 이면 바꾸지 않음).
 *
 * <p>WD-636: 타입은 생성·추가와 같은 규칙으로 정규화한다(소문자·앞뒤 공백 허용, 저장은 대문자). 빈 타입은 서비스가 「데이터 타입을 입력하세요」로 막는다.
 */
public record UpdateColumnRequest(
    String columnName,
    String displayName,
    String dataType,
    Integer maxLength,
    Boolean isNullable,
    Boolean isIndexed,
    String description,
    Boolean isPrimaryKey) {
  /**
   * 타입을 입력 경계에서 정규화한다(WD-636) — 소문자·앞뒤 공백 타입도 받아 대문자로 저장한다({@code
   * ColumnPhysicalType#normalizeDataType}).
   */
  public UpdateColumnRequest {
    dataType = ColumnPhysicalType.normalizeDataType(dataType);
  }

  public UpdateColumnRequest(
      String columnName,
      String displayName,
      String dataType,
      Integer maxLength,
      Boolean isNullable,
      Boolean isIndexed,
      String description) {
    this(columnName, displayName, dataType, maxLength, isNullable, isIndexed, description, null);
  }
}
