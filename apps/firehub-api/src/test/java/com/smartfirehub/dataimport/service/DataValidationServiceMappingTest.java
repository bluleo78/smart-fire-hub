package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ValidationErrorDetail;
import com.smartfirehub.dataimport.service.DataValidationService.PkValidationResult;
import com.smartfirehub.dataimport.service.DataValidationService.ValidationResultWithDetails;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DataValidationService 추가 단위 테스트 — 기존 테스트에서 누락된 분기 커버. - validateWithMapping (컬럼 매핑 포함 검증) -
 * validatePrimaryKeys (PK 중복/빈값 검사) - convertValue GEOMETRY 타입 pass-through - convertValue unknown
 * type 예외 - 따옴표 한 쪽만 있는 strip 분기 - DATE 다양한 포맷 (yyyy/MM/dd, dd-MM-yyyy, MM/dd/yyyy) - TIMESTAMP 다양한
 * 포맷 (yyyy/MM/dd HH:mm:ss, dd-MM-yyyy HH:mm:ss, yyyyMMddHHmmss)
 */
class DataValidationServiceMappingTest {

  private DataValidationService service;

  /** DatasetColumnResponse 생성 헬퍼 */
  private static DatasetColumnResponse col(String name, String dataType, boolean isNullable) {
    return new DatasetColumnResponse(
        1L, name, name, dataType, null, isNullable, false, null, 0, false);
  }

  @BeforeEach
  void setUp() {
    service = new DataValidationService();
  }

  // -----------------------------------------------------------------------
  // convertValue — GEOMETRY (pass-through)
  // -----------------------------------------------------------------------

  @Test
  void convertValue_geometry_passesThrough() throws Exception {
    String geojson = "{\"type\":\"Point\",\"coordinates\":[127.0,37.5]}";
    Object result = service.convertValue(geojson, "GEOMETRY");
    assertThat(result).isEqualTo(geojson);
  }

  // -----------------------------------------------------------------------
  // convertValue — unknown data type
  // -----------------------------------------------------------------------

  @Test
  void convertValue_unknownType_throwsException() {
    assertThatThrownBy(() -> service.convertValue("value", "JSONB"))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("알 수 없는 데이터 타입입니다");
  }

  // -----------------------------------------------------------------------
  // convertValue — 한쪽 따옴표만 있는 경우 (leading quote strip)
  // -----------------------------------------------------------------------

  @Test
  void convertValue_leadingSingleQuoteOnly_stripsLeadingQuote() throws Exception {
    // 앞에만 ' 있고 뒤에는 없는 경우 → leading quote만 제거
    Object result = service.convertValue("'hello", "TEXT");
    assertThat(result).isEqualTo("hello");
  }

  @Test
  void convertValue_leadingDoubleQuoteOnly_stripsLeadingQuote() throws Exception {
    Object result = service.convertValue("\"hello", "TEXT");
    assertThat(result).isEqualTo("hello");
  }

  // -----------------------------------------------------------------------
  // convertValue — DATE 포맷 변형들
  // -----------------------------------------------------------------------

  @Test
  void convertValue_date_slashFormat_yyyy_MM_dd() throws Exception {
    Object result = service.convertValue("2024/03/20", "DATE");
    assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2024, 3, 20));
  }

  @Test
  void convertValue_date_ddDashMMDashyyyy() throws Exception {
    Object result = service.convertValue("20-03-2024", "DATE");
    assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2024, 3, 20));
  }

  @Test
  void convertValue_date_MMSlashddSlashyyyy() throws Exception {
    // MM/dd/yyyy 포맷
    Object result = service.convertValue("03/20/2024", "DATE");
    assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2024, 3, 20));
  }

  @Test
  void convertValue_date_yyyyMMdd_compact() throws Exception {
    // 구분자 없는 8자리 yyyyMMdd 포맷(예: 20200316) — 공공/레거시 CSV 대응
    Object result = service.convertValue("20200316", "DATE");
    assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2020, 3, 16));
  }

  // -----------------------------------------------------------------------
  // convertValue — TIMESTAMP 포맷 변형들
  // -----------------------------------------------------------------------

  @Test
  void convertValue_timestamp_yyyySlashMMSlashdd_HHmmss() throws Exception {
    Object result = service.convertValue("2024/03/20 10:30:00", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2024, 3, 20, 10, 30, 0));
  }

  @Test
  void convertValue_timestamp_ddDashMMDashyyyy_HHmmss() throws Exception {
    Object result = service.convertValue("20-03-2024 10:30:00", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2024, 3, 20, 10, 30, 0));
  }

  @Test
  void convertValue_timestamp_ddSlashMMSlashyyyy_HHmmss() throws Exception {
    Object result = service.convertValue("20/03/2024 10:30:00", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2024, 3, 20, 10, 30, 0));
  }

  @Test
  void convertValue_timestamp_yyyyMMddHHmmss_compact() throws Exception {
    Object result = service.convertValue("20240320103000", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2024, 3, 20, 10, 30, 0));
  }

  // -----------------------------------------------------------------------
  // validateWithMapping — 정상 매핑
  // -----------------------------------------------------------------------

  @Test
  void validateWithMapping_validRows_mapsCorrectly() {
    List<DatasetColumnResponse> columns =
        List.of(col("age", "INTEGER", false), col("name", "TEXT", true));

    // 파일 컬럼명이 다를 때 매핑
    List<ColumnMappingEntry> mappings =
        List.of(new ColumnMappingEntry("나이", "age"), new ColumnMappingEntry("이름", "name"));

    List<Map<String, String>> rows =
        List.of(Map.of("나이", "30", "이름", "홍길동"), Map.of("나이", "25", "이름", "김철수"));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings);

    assertThat(result.totalRows()).isEqualTo(2);
    assertThat(result.validCount()).isEqualTo(2);
    assertThat(result.errorCount()).isEqualTo(0);
    assertThat(result.validRows()).hasSize(2);
  }

  @Test
  void validateWithMapping_requiredFieldEmpty_recordsError() {
    List<DatasetColumnResponse> columns = List.of(col("age", "INTEGER", false));

    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("나이", "age"));

    List<Map<String, String>> rows = List.of(Map.of("나이", ""));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings);

    assertThat(result.validCount()).isEqualTo(0);
    assertThat(result.errorCount()).isEqualTo(1);

    ValidationErrorDetail error = result.errors().get(0);
    assertThat(error.columnName()).isEqualTo("age");
    assertThat(error.error()).contains("필수 값이 비어 있습니다");
  }

  @Test
  void validateWithMapping_invalidType_recordsError() {
    List<DatasetColumnResponse> columns = List.of(col("score", "INTEGER", false));

    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("점수", "score"));

    List<Map<String, String>> rows = List.of(Map.of("점수", "not_a_number"));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings);

    assertThat(result.validCount()).isEqualTo(0);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0).error()).contains("정수 형식이 아닙니다");
  }

  @Test
  void validateWithMapping_nullableEmptyField_addsNull() {
    List<DatasetColumnResponse> columns = List.of(col("remark", "TEXT", true));

    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("비고", "remark"));

    // nullable 필드가 비어있으면 null 추가 후 에러 없이 통과
    List<Map<String, String>> rows = List.of(Map.of("비고", ""));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings);

    assertThat(result.validCount()).isEqualTo(1);
    assertThat(result.errorCount()).isEqualTo(0);
  }

  @Test
  void validateWithMapping_nullMapping_skipsUnmappedColumn() {
    // datasetColumn이 null인 매핑 항목은 무시되어야 한다
    List<DatasetColumnResponse> columns = List.of(col("name", "TEXT", true));

    List<ColumnMappingEntry> mappings =
        List.of(
            new ColumnMappingEntry("이름", "name"),
            new ColumnMappingEntry("무시컬럼", null)); // datasetColumn = null → 무시

    List<Map<String, String>> rows = List.of(Map.of("이름", "홍길동", "무시컬럼", "값"));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings);

    assertThat(result.validCount()).isEqualTo(1);
  }

  // -----------------------------------------------------------------------
  // validateWithMapping — rowIndexBase 오버로드 (배치 검증 전역 행 번호 오프셋)
  // -----------------------------------------------------------------------

  @Test
  void validateWithMapping_withRowIndexBase_offsetsErrorRowIndex() {
    List<DatasetColumnResponse> columns = List.of(col("age", "INTEGER", false));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("나이", "age"));

    // 내부 인덱스 1(1-based)인 첫 행이 invalid → base=500이면 전역 rowIndex는 501
    List<Map<String, String>> rows = List.of(Map.of("나이", "not_a_number"));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings, 500);

    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0).rowNumber()).isEqualTo(501);
    assertThat(result.totalRows()).isEqualTo(1);
  }

  @Test
  void validateWithMapping_noRowIndexBaseArg_delegatesToZeroBaseAndPreservesBehavior() {
    List<DatasetColumnResponse> columns = List.of(col("age", "INTEGER", false));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("나이", "age"));

    List<Map<String, String>> rows = List.of(Map.of("나이", "not_a_number"));

    ValidationResultWithDetails withoutBase = service.validateWithMapping(rows, columns, mappings);
    ValidationResultWithDetails withZeroBase =
        service.validateWithMapping(rows, columns, mappings, 0);

    assertThat(withoutBase.errors().get(0).rowNumber()).isEqualTo(1);
    assertThat(withoutBase.errors()).isEqualTo(withZeroBase.errors());
  }

  // -----------------------------------------------------------------------
  // toRows — Pass1(검증)과 Pass2(삽입)가 동일한 값 변환을 사용하는지 확인하는 정합성 테스트
  // (Task2 핵심 위험 3: 두 벌로 갈라지면 "검증 통과 == 값 변환 성공"이 어긋나는 버그가 생긴다).
  // -----------------------------------------------------------------------

  @Test
  void toRows_withMapping_matchesValidateWithMappingValidRows() {
    List<DatasetColumnResponse> columns =
        List.of(col("name", "TEXT", false), col("age", "INTEGER", true));
    List<ColumnMappingEntry> mappings =
        List.of(new ColumnMappingEntry("이름", "name"), new ColumnMappingEntry("나이", "age"));
    List<Map<String, String>> rows =
        List.of(
            Map.of("이름", "Alice", "나이", "30"),
            Map.of("이름", "Bob", "나이", "25"),
            Map.of("이름", "Carol", "나이", ""));

    ValidationResultWithDetails vr = service.validateWithMapping(rows, columns, mappings);
    List<List<Object>> converted = service.toRows(rows, columns, mappings);

    // 전량 유효한 배치이므로 toRows()의 원소별 결과가 검증 결과의 validRows()와 정확히 같아야 한다.
    assertThat(vr.errorCount()).isZero();
    assertThat(converted).isEqualTo(vr.validRows());
  }

  @Test
  void toRows_withoutMapping_matchesValidateValidRows() {
    List<DatasetColumnResponse> columns =
        List.of(
            col("name", "TEXT", false),
            col("age", "INTEGER", true),
            col("score", "DECIMAL", true));
    List<Map<String, String>> rows =
        List.of(
            Map.of("name", "Alice", "age", "30", "score", "88.5"),
            Map.of("name", "Bob", "age", "25", "score", "91.2"));

    DataValidationService.ValidationResult vr = service.validate(rows, columns);
    List<List<Object>> converted = service.toRows(rows, columns, null);

    assertThat(vr.errorCount()).isZero();
    assertThat(vr.validRows()).hasSize(2);
    assertThat(converted).isEqualTo(vr.validRows());
  }

  /**
   * 매핑 없는 경로에서 필수값 누락/타입 변환 실패가 있는 잘못된 행은 validate()가 에러로 기록하고 유효 행에서 제외해야 한다.
   * validate()와 toRows/validateWithMapping이 동일한 convertRowOrNull을 공유하는지 회귀 확인하는 테스트.
   */
  @Test
  void validate_withoutMapping_invalidRow_recordsErrorAndExcludesRow() {
    List<DatasetColumnResponse> columns =
        List.of(col("name", "TEXT", false), col("age", "INTEGER", true));
    List<Map<String, String>> rows =
        List.of(
            Map.of("name", "Alice", "age", "30"), // valid
            Map.of("name", "", "age", "25"), // 필수값(name) 누락
            Map.of("name", "Carl", "age", "not-a-number")); // 타입 변환 실패

    DataValidationService.ValidationResult vr = service.validate(rows, columns);

    assertThat(vr.validRows()).hasSize(1);
    assertThat(vr.errorCount()).isEqualTo(2);
    assertThat(vr.errors().get(0)).contains("2행").contains("name").contains("필수 값이 비어 있습니다");
    assertThat(vr.errors().get(1))
        .contains("3행")
        .contains("age")
        .contains("정수 형식이 아닙니다");
  }

  // -----------------------------------------------------------------------
  // validatePrimaryKeys — 정상 / PK 빈값 / 중복
  // -----------------------------------------------------------------------

  @Test
  void validatePrimaryKeys_validUniqueKeys_noErrorsOrWarnings() {
    List<Map<String, String>> rows =
        List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"));

    PkValidationResult result = service.validatePrimaryKeys(rows, List.of("id"));

    assertThat(result.errors()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void validatePrimaryKeys_emptyPkValue_recordsError() {
    List<Map<String, String>> rows = List.of(Map.of("id", ""), Map.of("id", "2"));

    PkValidationResult result = service.validatePrimaryKeys(rows, List.of("id"));

    assertThat(result.errors()).hasSize(1);
    ValidationErrorDetail err = result.errors().get(0);
    assertThat(err.rowNumber()).isEqualTo(1);
    assertThat(err.columnName()).isEqualTo("id");
    assertThat(err.error()).contains("기본키 컬럼은 비어 있을 수 없습니다");
  }

  @Test
  void validatePrimaryKeys_duplicateKey_recordsWarning() {
    List<Map<String, String>> rows = List.of(Map.of("id", "1"), Map.of("id", "1")); // 중복

    PkValidationResult result = service.validatePrimaryKeys(rows, List.of("id"));

    assertThat(result.warnings()).hasSize(1);
    assertThat(result.warnings().get(0)).contains("기본키 값 중복");
  }

  @Test
  void validatePrimaryKeys_compositePk_duplicateDetected() {
    // 복합 PK 중복 감지
    List<Map<String, String>> rows =
        List.of(
            Map.of("col_a", "1", "col_b", "X"),
            Map.of("col_a", "1", "col_b", "Y"),
            Map.of("col_a", "1", "col_b", "X")); // 중복

    PkValidationResult result = service.validatePrimaryKeys(rows, List.of("col_a", "col_b"));

    assertThat(result.warnings()).hasSize(1);
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void validatePrimaryKeys_nullPkColumn_recordsError() {
    // row에 PK 컬럼 키 자체가 없는 경우 → null로 처리되어 에러
    List<Map<String, String>> rows = List.of(Map.of("other_col", "value")); // "id" 키 없음

    PkValidationResult result = service.validatePrimaryKeys(rows, List.of("id"));

    assertThat(result.errors()).hasSize(1);
    assertThat(result.errors().get(0).error()).contains("기본키 컬럼은 비어 있을 수 없습니다");
  }
}
