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
class DataValidationServiceTest2 {

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
        .hasMessageContaining("Unknown data type");
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
    assertThat(error.error()).contains("Required field");
  }

  @Test
  void validateWithMapping_invalidType_recordsError() {
    List<DatasetColumnResponse> columns = List.of(col("score", "INTEGER", false));

    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("점수", "score"));

    List<Map<String, String>> rows = List.of(Map.of("점수", "not_a_number"));

    ValidationResultWithDetails result = service.validateWithMapping(rows, columns, mappings);

    assertThat(result.validCount()).isEqualTo(0);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0).error()).contains("Invalid integer value");
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
    assertThat(err.error()).contains("cannot be null or empty");
  }

  @Test
  void validatePrimaryKeys_duplicateKey_recordsWarning() {
    List<Map<String, String>> rows = List.of(Map.of("id", "1"), Map.of("id", "1")); // 중복

    PkValidationResult result = service.validatePrimaryKeys(rows, List.of("id"));

    assertThat(result.warnings()).hasSize(1);
    assertThat(result.warnings().get(0)).contains("duplicate key");
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
    assertThat(result.errors().get(0).error()).contains("cannot be null or empty");
  }
}
