package com.smartfirehub.dataset.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 새 컬럼 정의(데이터셋 생성·파이프라인 TEMP 생성).
 *
 * <p>columnName·dataType 은 DDL 조립에 필수라 HTTP 요청(데이터셋 생성)은 {@code @NotBlank} 로 요청 경계에서 거부한다(WD-587) — null
 * 이면 서비스 검증에서 NPE(500)가 났다. 코드로 만드는 경로(파이프라인 TEMP 생성 등)는 Bean Validation 을 거치지 않으므로
 * {@code DataTableService.validateNewColumns} 가 같은 문구로 다시 막는다.
 */
public record DatasetColumnRequest(
    @NotBlank(message = DatasetColumnRequest.COLUMN_NAME_REQUIRED_MESSAGE) String columnName,
    String displayName,
    @NotBlank(message = DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE) String dataType,
    Integer maxLength,
    boolean isNullable,
    boolean isIndexed,
    String description,
    boolean isPrimaryKey) {

  /** 컬럼 목록에 null 요소가 들어온 경우의 안내 — 서버에 한국어 Bean Validation 기본 문구가 없어 명시한다(WD-570). */
  public static final String COLUMN_REQUIRED_MESSAGE = "컬럼 정보가 비어 있습니다.";

  /** 컬럼 이름 누락·공백 안내 — 데이터셋 생성과 필드 추가(AddColumnRequest)가 같은 문장을 쓴다. */
  public static final String COLUMN_NAME_REQUIRED_MESSAGE = "컬럼 이름을 입력하세요.";

  /** 컬럼 데이터 타입 누락·공백 안내 — 데이터셋 생성과 필드 추가(AddColumnRequest)가 같은 문장을 쓴다. */
  public static final String DATA_TYPE_REQUIRED_MESSAGE = "컬럼 데이터 타입을 입력하세요.";

  public DatasetColumnRequest(
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
