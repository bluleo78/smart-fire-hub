package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit tests for DataValidationService — no Spring context required. */
class DataValidationServiceTest {

  private DataValidationService service;

  // Helper to build a DatasetColumnResponse with sensible defaults
  private static DatasetColumnResponse col(String name, String dataType, boolean isNullable) {
    return new DatasetColumnResponse(
        1L, name, name, dataType, null, isNullable, false, null, 0, false);
  }

  @BeforeEach
  void setUp() {
    service = new DataValidationService();
  }

  // -----------------------------------------------------------------------
  // convertValue — TEXT / VARCHAR
  // -----------------------------------------------------------------------

  @Test
  void convertValue_text_returnsStringAsIs() throws Exception {
    assertThat(service.convertValue("hello world", "TEXT")).isEqualTo("hello world");
  }

  @Test
  void convertValue_varchar_returnsStringAsIs() throws Exception {
    assertThat(service.convertValue("simple", "VARCHAR")).isEqualTo("simple");
  }

  @Test
  void convertValue_varchar_stripsDoubleQuotes() throws Exception {
    assertThat(service.convertValue("\"quoted\"", "VARCHAR")).isEqualTo("quoted");
  }

  @Test
  void convertValue_varchar_stripsSingleQuotes() throws Exception {
    assertThat(service.convertValue("'quoted'", "VARCHAR")).isEqualTo("quoted");
  }

  // -----------------------------------------------------------------------
  // convertValue — INTEGER
  // -----------------------------------------------------------------------

  @Test
  void convertValue_integer_validValue_returnsLong() throws Exception {
    Object result = service.convertValue("123", "INTEGER");
    assertThat(result).isInstanceOf(Long.class).isEqualTo(123L);
  }

  @Test
  void convertValue_integer_negativeValue_returnsLong() throws Exception {
    Object result = service.convertValue("-42", "INTEGER");
    assertThat(result).isInstanceOf(Long.class).isEqualTo(-42L);
  }

  @Test
  void convertValue_integer_invalidValue_throwsException() {
    assertThatThrownBy(() -> service.convertValue("abc", "INTEGER"))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Invalid integer value");
  }

  // -----------------------------------------------------------------------
  // convertValue — DECIMAL
  // -----------------------------------------------------------------------

  @Test
  void convertValue_decimal_validValue_returnsBigDecimal() throws Exception {
    Object result = service.convertValue("3.14", "DECIMAL");
    assertThat(result).isInstanceOf(BigDecimal.class);
    assertThat(((BigDecimal) result).compareTo(new BigDecimal("3.14"))).isZero();
  }

  @Test
  void convertValue_decimal_invalidValue_throwsException() {
    assertThatThrownBy(() -> service.convertValue("xyz", "DECIMAL"))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Invalid decimal value");
  }

  // -----------------------------------------------------------------------
  // convertValue — BOOLEAN
  // -----------------------------------------------------------------------

  @Test
  void convertValue_boolean_trueValues_returnsTrue() throws Exception {
    assertThat(service.convertValue("true", "BOOLEAN")).isEqualTo(true);
    assertThat(service.convertValue("1", "BOOLEAN")).isEqualTo(true);
    assertThat(service.convertValue("yes", "BOOLEAN")).isEqualTo(true);
    // case-insensitive
    assertThat(service.convertValue("TRUE", "BOOLEAN")).isEqualTo(true);
    assertThat(service.convertValue("YES", "BOOLEAN")).isEqualTo(true);
  }

  @Test
  void convertValue_boolean_falseValues_returnsFalse() throws Exception {
    assertThat(service.convertValue("false", "BOOLEAN")).isEqualTo(false);
    assertThat(service.convertValue("0", "BOOLEAN")).isEqualTo(false);
    assertThat(service.convertValue("no", "BOOLEAN")).isEqualTo(false);
    // case-insensitive
    assertThat(service.convertValue("FALSE", "BOOLEAN")).isEqualTo(false);
    assertThat(service.convertValue("NO", "BOOLEAN")).isEqualTo(false);
  }

  @Test
  void convertValue_boolean_invalidValue_throwsException() {
    assertThatThrownBy(() -> service.convertValue("maybe", "BOOLEAN"))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Invalid boolean value");
  }

  // -----------------------------------------------------------------------
  // convertValue — DATE
  // -----------------------------------------------------------------------

  @Test
  void convertValue_date_isoFormat_returnsLocalDate() throws Exception {
    Object result = service.convertValue("2024-01-15", "DATE");
    assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2024, 1, 15));
  }

  @Test
  void convertValue_date_ddMMyyyyFormat_returnsLocalDate() throws Exception {
    Object result = service.convertValue("15/01/2024", "DATE");
    assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2024, 1, 15));
  }

  @Test
  void convertValue_date_invalidValue_throwsException() {
    assertThatThrownBy(() -> service.convertValue("not-a-date", "DATE"))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Invalid date value");
  }

  // -----------------------------------------------------------------------
  // convertValue — TIMESTAMP
  // -----------------------------------------------------------------------

  @Test
  void convertValue_timestamp_spaceFormat_returnsLocalDateTime() throws Exception {
    Object result = service.convertValue("2024-01-15 10:30:00", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
  }

  @Test
  void convertValue_timestamp_isoFormat_returnsLocalDateTime() throws Exception {
    Object result = service.convertValue("2024-01-15T10:30:00", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
  }

  @Test
  void convertValue_timestamp_invalidValue_throwsException() {
    assertThatThrownBy(() -> service.convertValue("not-a-timestamp", "TIMESTAMP"))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Invalid timestamp value");
  }

  // -----------------------------------------------------------------------
  // convertValue — null / empty
  // -----------------------------------------------------------------------

  @Test
  void convertValue_nullValue_returnsNull() throws Exception {
    assertThat(service.convertValue(null, "TEXT")).isNull();
  }

  @Test
  void convertValue_emptyString_returnsNull() throws Exception {
    assertThat(service.convertValue("", "INTEGER")).isNull();
  }

  // -----------------------------------------------------------------------
  // validate — mixed rows
  // -----------------------------------------------------------------------

  @Test
  void validate_mixedValidAndInvalidRows_correctCounts() {
    List<DatasetColumnResponse> columns = List.of(col("age", "INTEGER", false));

    List<Map<String, String>> rows =
        List.of(
            Map.of("age", "25"), // valid
            Map.of("age", "abc"), // invalid — not an integer
            Map.of("age", "30") // valid
            );

    DataValidationService.ValidationResult result = service.validate(rows, columns);

    assertThat(result.totalRows()).isEqualTo(3);
    assertThat(result.validCount()).isEqualTo(2);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.validRows()).hasSize(2);
    assertThat(result.errors()).hasSize(1);
    assertThat(result.errors().get(0)).contains("age").contains("Invalid integer value");
  }

  @Test
  void validate_requiredColumnWithEmptyValue_reportsError() {
    List<DatasetColumnResponse> columns =
        List.of(
            col("name", "TEXT", false) // isNullable = false → required
            );

    List<Map<String, String>> rows =
        List.of(
            Map.of("name", "") // empty value for required field
            );

    DataValidationService.ValidationResult result = service.validate(rows, columns);

    assertThat(result.validCount()).isEqualTo(0);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0)).contains("name").contains("required but empty");
  }

  @Test
  void validate_nullableColumnWithEmptyValue_addsNullNoError() {
    List<DatasetColumnResponse> columns =
        List.of(
            col("nickname", "TEXT", true) // isNullable = true → optional
            );

    List<Map<String, String>> rows =
        List.of(
            Map.of("nickname", "") // empty value for nullable field
            );

    DataValidationService.ValidationResult result = service.validate(rows, columns);

    assertThat(result.validCount()).isEqualTo(1);
    assertThat(result.errorCount()).isEqualTo(0);
    assertThat(result.errors()).isEmpty();
    // The converted row should contain null for the nullable column
    assertThat(result.validRows().get(0)).containsExactly((Object) null);
  }
}
