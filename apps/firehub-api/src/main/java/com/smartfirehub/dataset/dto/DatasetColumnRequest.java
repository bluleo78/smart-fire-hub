package com.smartfirehub.dataset.dto;

import com.smartfirehub.dataset.service.ColumnPhysicalType;
import jakarta.validation.constraints.NotBlank;

/**
 * 새 컬럼 정의(데이터셋 생성·파이프라인 TEMP 생성).
 *
 * <p>columnName·dataType 은 DDL 조립에 필수라 HTTP 요청(데이터셋 생성)은 {@code @NotBlank} 로 요청 경계에서 거부한다(WD-587) —
 * null 이면 서비스 검증에서 NPE(500)가 났다. 코드로 만드는 경로(파이프라인 TEMP 생성 등)는 Bean Validation 을 거치지 않으므로 {@code
 * DataTableService.validateNewColumns} 가 같은 문구로 다시 막는다.
 */
public record DatasetColumnRequest(
    @NotBlank(message = DatasetColumnRequest.COLUMN_NAME_REQUIRED_MESSAGE) String columnName,
    String displayName,
    @NotBlank(message = DatasetColumnRequest.DATA_TYPE_REQUIRED_MESSAGE) String dataType,
    Integer maxLength,
    Boolean isNullable,
    Boolean isIndexed,
    String description,
    Boolean isPrimaryKey) {

  /** 컬럼 목록에 null 요소가 들어온 경우의 안내 — 서버에 한국어 Bean Validation 기본 문구가 없어 명시한다(WD-570). */
  public static final String COLUMN_REQUIRED_MESSAGE = "컬럼 정보가 비어 있습니다.";

  /** 컬럼 이름 누락·공백 안내 — 데이터셋 생성과 필드 추가(AddColumnRequest)가 같은 문장을 쓴다. */
  public static final String COLUMN_NAME_REQUIRED_MESSAGE = "컬럼 이름을 입력하세요.";

  /**
   * 컬럼 데이터 타입 누락·공백 안내 — 데이터셋 생성과 필드 추가(AddColumnRequest)가 같은 문장을 쓴다. 문장의 단일 출처는 {@link
   * ColumnPhysicalType#DATA_TYPE_REQUIRED_MESSAGE}(WD-636 — 서비스 계층이 DTO 를 참조하지 않게); 기존 호출부를 위해 이름은
   * 유지한다.
   */
  public static final String DATA_TYPE_REQUIRED_MESSAGE =
      ColumnPhysicalType.DATA_TYPE_REQUIRED_MESSAGE;

  /**
   * isNullable 누락·null 의 기본값(WD-698) — 데이터셋 생성과 필드 추가(AddColumnRequest)가 같은 규칙을 쓴다.
   *
   * <p>세 옵션이 원시 boolean 이던 때는 JSON 에서 빠지면 false 가 돼, isNullable 을 빼먹은 API·AI 도구 호출이 NOT NULL 컬럼을
   * 만들고 행이 있는 데이터셋에는 필드 추가가 거부됐다. 이제 누락은 SQL 기본처럼 NULL 허용이다. 단 기본 키는 NOT NULL 이어야 하므로(NULL 허용 기본 키는
   * 거부된다) 기본 키에서 빠뜨리면 예전처럼 NOT NULL 로 둔다. isIndexed·isPrimaryKey 누락은 false. 명시한 값은 그대로 따르며, 정규화 뒤 세
   * 값은 null 이 아니라 호출부는 언박싱해도 된다.
   */
  static boolean defaultNullable(Boolean isNullable, boolean isPrimaryKey) {
    return isNullable != null ? isNullable : !isPrimaryKey;
  }

  /**
   * 타입을 입력 경계에서 정규화한다(WD-636) — 소문자·앞뒤 공백 타입도 받아 대문자로 저장한다({@code
   * ColumnPhysicalType#normalizeDataType}).
   */
  public DatasetColumnRequest {
    dataType = ColumnPhysicalType.normalizeDataType(dataType);
    // WD-698: 누락·null 옵션을 SQL 기본으로 채운다 — 규칙은 {@link #defaultNullable}.
    isPrimaryKey = Boolean.TRUE.equals(isPrimaryKey);
    isIndexed = Boolean.TRUE.equals(isIndexed);
    isNullable = defaultNullable(isNullable, isPrimaryKey);
  }

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
