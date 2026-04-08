package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataimport.dto.ColumnMappingDto;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ColumnMappingService 순수 단위 테스트.
 *
 * <p>DB나 외부 의존성이 없는 순수 로직 서비스이므로 Spring context 없이 직접 인스턴스화하여 테스트한다. 5단계 매칭 알고리즘(EXACT →
 * CASE_INSENSITIVE → DISPLAY_NAME → NORMALIZED → NONE)의 우선순위와 중복 할당 방지 동작을 검증한다.
 */
class ColumnMappingServiceTest {

  private ColumnMappingService service;

  /** DatasetColumnResponse 생성 헬퍼 — columnName과 displayName만 지정하고 나머지는 기본값 사용 */
  private static DatasetColumnResponse col(String columnName, String displayName) {
    return new DatasetColumnResponse(
        1L, columnName, displayName, "TEXT", null, true, false, null, 0, false);
  }

  /** displayName 없는 컬럼 생성 헬퍼 */
  private static DatasetColumnResponse col(String columnName) {
    return col(columnName, null);
  }

  @BeforeEach
  void setUp() {
    service = new ColumnMappingService();
  }

  // -----------------------------------------------------------------------
  // 1단계: Exact match (columnName 완전 일치)
  // -----------------------------------------------------------------------

  /** fileHeader가 columnName과 완전히 동일하면 matchType=EXACT, confidence=1.0으로 매핑된다 */
  @Test
  void suggestMappings_exactMatch_returnsExactType() {
    List<String> headers = List.of("name");
    List<DatasetColumnResponse> columns = List.of(col("name", "이름"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).fileColumn()).isEqualTo("name");
    assertThat(result.get(0).datasetColumn()).isEqualTo("name");
    assertThat(result.get(0).matchType()).isEqualTo("EXACT");
    assertThat(result.get(0).confidence()).isEqualTo(1.0);
  }

  // -----------------------------------------------------------------------
  // 2단계: Case-insensitive match (columnName 대소문자 무시)
  // -----------------------------------------------------------------------

  /** fileHeader와 columnName이 대소문자만 다른 경우 matchType=CASE_INSENSITIVE, confidence=0.9 */
  @Test
  void suggestMappings_caseInsensitiveMatch_returnsCaseInsensitiveType() {
    List<String> headers = List.of("Name");
    // columnName은 "name" — 정확히 일치하지 않지만 대소문자 무시 시 일치
    List<DatasetColumnResponse> columns = List.of(col("name", null));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).datasetColumn()).isEqualTo("name");
    assertThat(result.get(0).matchType()).isEqualTo("CASE_INSENSITIVE");
    assertThat(result.get(0).confidence()).isEqualTo(0.9);
  }

  // -----------------------------------------------------------------------
  // 3단계: DisplayName match (displayName 대소문자 무시)
  // -----------------------------------------------------------------------

  /** fileHeader가 displayName과 대소문자 무시 시 일치하면 matchType=DISPLAY_NAME, confidence=0.8 */
  @Test
  void suggestMappings_displayNameMatch_returnsDisplayNameType() {
    List<String> headers = List.of("이름");
    // columnName은 "col_name", displayName은 "이름"
    List<DatasetColumnResponse> columns = List.of(col("col_name", "이름"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).datasetColumn()).isEqualTo("col_name");
    assertThat(result.get(0).matchType()).isEqualTo("DISPLAY_NAME");
    assertThat(result.get(0).confidence()).isEqualTo(0.8);
  }

  /** displayName 대소문자 무시 매칭도 동작해야 한다 */
  @Test
  void suggestMappings_displayNameCaseInsensitive_matches() {
    List<String> headers = List.of("User Name");
    List<DatasetColumnResponse> columns = List.of(col("user_name", "user name"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).matchType()).isEqualTo("DISPLAY_NAME");
  }

  // -----------------------------------------------------------------------
  // 4단계: Normalized match (공백·언더스코어·하이픈 제거 후 소문자 비교)
  // -----------------------------------------------------------------------

  /** 공백/언더스코어/하이픈을 제거하면 일치하는 경우 matchType=NORMALIZED, confidence=0.7 */
  @Test
  void suggestMappings_normalizedColumnNameMatch_returnsNormalizedType() {
    List<String> headers = List.of("user name");
    // columnName "user_name" → normalize → "username"
    // fileHeader "user name" → normalize → "username" → 일치
    List<DatasetColumnResponse> columns = List.of(col("user_name", null));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).datasetColumn()).isEqualTo("user_name");
    assertThat(result.get(0).matchType()).isEqualTo("NORMALIZED");
    assertThat(result.get(0).confidence()).isEqualTo(0.7);
  }

  /** displayName normalize 매칭도 동작해야 한다 */
  @Test
  void suggestMappings_normalizedDisplayNameMatch_returnsNormalizedType() {
    List<String> headers = List.of("birth-date");
    // columnName "col_bd" → normalize → "colbd" (불일치)
    // displayName "birth date" → normalize → "birthdate"
    // fileHeader "birth-date" → normalize → "birthdate" → 일치
    List<DatasetColumnResponse> columns = List.of(col("col_bd", "birth date"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).datasetColumn()).isEqualTo("col_bd");
    assertThat(result.get(0).matchType()).isEqualTo("NORMALIZED");
    assertThat(result.get(0).confidence()).isEqualTo(0.7);
  }

  // -----------------------------------------------------------------------
  // 5단계: No match
  // -----------------------------------------------------------------------

  /** 어떤 단계에서도 매칭되지 않으면 datasetColumn=null, matchType=NONE, confidence=0.0 */
  @Test
  void suggestMappings_noMatch_returnsNoneType() {
    List<String> headers = List.of("unknown_header");
    List<DatasetColumnResponse> columns = List.of(col("completely_different", "다른 이름"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).fileColumn()).isEqualTo("unknown_header");
    assertThat(result.get(0).datasetColumn()).isNull();
    assertThat(result.get(0).matchType()).isEqualTo("NONE");
    assertThat(result.get(0).confidence()).isEqualTo(0.0);
  }

  // -----------------------------------------------------------------------
  // 우선순위 검증: EXACT가 CASE_INSENSITIVE보다 우선
  // -----------------------------------------------------------------------

  /** 두 컬럼이 있을 때 하나는 EXACT 매칭, 다른 하나는 CASE_INSENSITIVE 매칭 가능한 경우 EXACT 매칭이 우선 적용되어야 한다. */
  @Test
  void suggestMappings_exactBeforeCaseInsensitive_exactWins() {
    // fileHeader "Name" → col("Name") EXACT, col("name") CASE_INSENSITIVE 가능
    // EXACT가 먼저 선택되어야 한다
    List<String> headers = List.of("Name");
    List<DatasetColumnResponse> columns = List.of(col("name"), col("Name"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).matchType()).isEqualTo("EXACT");
    assertThat(result.get(0).datasetColumn()).isEqualTo("Name");
  }

  // -----------------------------------------------------------------------
  // 중복 할당 방지: 동일 datasetColumn이 여러 fileHeader에 할당되지 않아야 함
  // -----------------------------------------------------------------------

  /**
   * 두 fileHeader가 모두 같은 datasetColumn에 EXACT 매칭 가능할 때, 첫 번째 헤더가 컬럼을 선점하면 두 번째 헤더는 NONE으로 처리되어야 한다.
   */
  @Test
  void suggestMappings_duplicateColumnNotAssignedTwice() {
    // fileHeader "name"과 "name" 모두 col("name")에 매칭되지만
    // 첫 번째가 선점하므로 두 번째는 NONE
    List<String> headers = List.of("name", "name");
    List<DatasetColumnResponse> columns = List.of(col("name"));

    List<ColumnMappingDto> result = service.suggestMappings(headers, columns);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).matchType()).isEqualTo("EXACT");
    assertThat(result.get(0).datasetColumn()).isEqualTo("name");
    // 두 번째 헤더는 이미 사용된 컬럼이므로 매핑 불가
    assertThat(result.get(1).matchType()).isEqualTo("NONE");
    assertThat(result.get(1).datasetColumn()).isNull();
  }

  // -----------------------------------------------------------------------
  // 빈 입력 처리
  // -----------------------------------------------------------------------

  /** fileHeaders가 비어있으면 결과도 빈 리스트를 반환한다 */
  @Test
  void suggestMappings_emptyHeaders_returnsEmptyList() {
    List<DatasetColumnResponse> columns = List.of(col("name"));

    List<ColumnMappingDto> result = service.suggestMappings(List.of(), columns);

    assertThat(result).isEmpty();
  }

  /** datasetColumns가 비어있으면 모든 헤더가 NONE으로 반환된다 */
  @Test
  void suggestMappings_emptyDatasetColumns_allNone() {
    List<String> headers = List.of("col1", "col2");

    List<ColumnMappingDto> result = service.suggestMappings(headers, List.of());

    assertThat(result).hasSize(2);
    assertThat(result).allMatch(m -> "NONE".equals(m.matchType()));
    assertThat(result).allMatch(m -> m.datasetColumn() == null);
  }
}
