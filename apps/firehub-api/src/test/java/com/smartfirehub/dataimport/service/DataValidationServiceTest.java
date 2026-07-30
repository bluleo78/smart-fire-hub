package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
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
        .hasMessageContaining("정수 형식이 아닙니다");
  }

  @Test
  void convertValue_integer_thousandsComma_stripsAndParses() throws Exception {
    // 천단위 콤마가 든 값("38,344")도 정수로 파싱돼야 한다 (임포트 흔한 형식).
    Object result = service.convertValue("38,344", "INTEGER");
    assertThat(result).isInstanceOf(Long.class).isEqualTo(38344L);
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
        .hasMessageContaining("소수 형식이 아닙니다");
  }

  @Test
  void convertValue_decimal_thousandsComma_stripsAndParses() throws Exception {
    // 천단위 콤마가 든 소수("1,234.56")도 파싱돼야 한다.
    Object result = service.convertValue("1,234.56", "DECIMAL");
    assertThat(result).isInstanceOf(BigDecimal.class);
    assertThat(((BigDecimal) result).compareTo(new BigDecimal("1234.56"))).isZero();
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
        .hasMessageContaining("참/거짓 형식이 아닙니다");
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
        .hasMessageContaining("날짜 형식이 아닙니다");
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
        .hasMessageContaining("날짜시간 형식이 아닙니다");
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
    assertThat(result.errors().get(0)).contains("age").contains("정수 형식이 아닙니다");
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
    assertThat(result.errors().get(0)).contains("name").contains("필수 값이 비어 있습니다");
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

  // -----------------------------------------------------------------------
  // validate — DATE/TIMESTAMP "0" 등 무의미한 날짜없음 표식 → null 취급
  // -----------------------------------------------------------------------

  @Test
  void validate_nullableDateColumn_zeroValue_treatedAsNull() {
    // (a) DATE nullable 컬럼 "0" → 빈 값과 동일하게 null 저장(에러 없음)
    List<DatasetColumnResponse> columns = List.of(col("event_date", "DATE", true));

    DataValidationService.ValidationResult result =
        service.validate(List.of(Map.of("event_date", "0")), columns);

    assertThat(result.errorCount()).isEqualTo(0);
    assertThat(result.validCount()).isEqualTo(1);
    assertThat(result.validRows().get(0)).containsExactly((Object) null);
  }

  @Test
  void validate_nonNullDateColumn_zeroValue_reportsRequiredErrorNotInvalidDate() {
    // (b) DATE non-null 컬럼 "0" → "필수 값이 비어 있습니다" 계열 에러 ("날짜 형식이 아닙니다" 아님)
    List<DatasetColumnResponse> columns = List.of(col("event_date", "DATE", false));

    DataValidationService.ValidationResult result =
        service.validate(List.of(Map.of("event_date", "0")), columns);

    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0)).contains("필수 값이 비어 있습니다");
    assertThat(result.errors().get(0)).doesNotContain("날짜 형식이 아닙니다");
  }

  @Test
  void validate_dateColumn_allZeroVariants_treatedAsNull() {
    // (c) all-zero 변형("0000-00-00", "00000000") → null 저장
    List<DatasetColumnResponse> columns = List.of(col("event_date", "DATE", true));

    DataValidationService.ValidationResult result1 =
        service.validate(List.of(Map.of("event_date", "0000-00-00")), columns);
    DataValidationService.ValidationResult result2 =
        service.validate(List.of(Map.of("event_date", "00000000")), columns);

    assertThat(result1.errorCount()).isEqualTo(0);
    assertThat(result1.validRows().get(0)).containsExactly((Object) null);
    assertThat(result2.errorCount()).isEqualTo(0);
    assertThat(result2.validRows().get(0)).containsExactly((Object) null);
  }

  @Test
  void validate_timestampColumn_allZeroVariants_treatedAsNull() {
    // 타임스탬프 전용 all-zero 변형("00000000000000")도 동일하게 null 처리
    List<DatasetColumnResponse> columns = List.of(col("event_ts", "TIMESTAMP", true));

    DataValidationService.ValidationResult result =
        service.validate(List.of(Map.of("event_ts", "00000000000000")), columns);

    assertThat(result.errorCount()).isEqualTo(0);
    assertThat(result.validRows().get(0)).containsExactly((Object) null);
  }

  @Test
  void validate_dateColumn_validDates_stillParsedCorrectly() {
    // (d) 정상 날짜("2020-03-16") 및 yyyyMMdd("20200316") 회귀 — 절대 무의미값으로 오검출되면 안 됨
    List<DatasetColumnResponse> columns = List.of(col("event_date", "DATE", false));

    DataValidationService.ValidationResult isoResult =
        service.validate(List.of(Map.of("event_date", "2020-03-16")), columns);
    DataValidationService.ValidationResult compactResult =
        service.validate(List.of(Map.of("event_date", "20200316")), columns);

    assertThat(isoResult.errorCount()).isEqualTo(0);
    assertThat(isoResult.validRows().get(0)).containsExactly(LocalDate.of(2020, 3, 16));
    assertThat(compactResult.errorCount()).isEqualTo(0);
    assertThat(compactResult.validRows().get(0)).containsExactly(LocalDate.of(2020, 3, 16));
  }

  @Test
  void validate_numericColumn_zeroValue_notAffectedByDateNormalization() {
    // (e) NUMERIC("DECIMAL") "0"은 정규화 대상이 아니며 숫자 0으로 정상 변환된다
    List<DatasetColumnResponse> columns = List.of(col("amount", "DECIMAL", false));

    DataValidationService.ValidationResult result =
        service.validate(List.of(Map.of("amount", "0")), columns);

    assertThat(result.errorCount()).isEqualTo(0);
    assertThat(result.validRows().get(0)).hasSize(1);
    Object value = result.validRows().get(0).get(0);
    assertThat(value).isInstanceOf(BigDecimal.class);
    assertThat(((BigDecimal) value).compareTo(BigDecimal.ZERO)).isZero();
  }

  // -----------------------------------------------------------------------
  // validate — rowIndexBase 오버로드 (배치 검증 시 전역 행 번호 오프셋)
  // -----------------------------------------------------------------------

  @Test
  void validate_withRowIndexBase_offsetsErrorRowIndex() {
    List<DatasetColumnResponse> columns = List.of(col("age", "INTEGER", false));

    // 배치의 두 번째 행(내부 인덱스 2)이 invalid → base=1000이면 전역 rowIndex는 1002여야 함
    List<Map<String, String>> rows =
        List.of(
            Map.of("age", "25"), // valid
            Map.of("age", "abc") // invalid
            );

    DataValidationService.ValidationResult result = service.validate(rows, columns, 1000);

    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0)).contains("1002행");
    // totalRows는 이번 호출(배치)의 행수만 반영 — 누적은 컨트롤러 책임
    assertThat(result.totalRows()).isEqualTo(2);
  }

  @Test
  void validate_noRowIndexBaseArg_delegatesToZeroBaseAndPreservesBehavior() {
    List<DatasetColumnResponse> columns = List.of(col("age", "INTEGER", false));

    List<Map<String, String>> rows =
        List.of(
            Map.of("age", "25"), // valid
            Map.of("age", "abc") // invalid — 내부 인덱스 2 (1-based)
            );

    DataValidationService.ValidationResult withoutBase = service.validate(rows, columns);
    DataValidationService.ValidationResult withZeroBase = service.validate(rows, columns, 0);

    assertThat(withoutBase.errors().get(0)).contains("2행");
    assertThat(withoutBase.errors()).isEqualTo(withZeroBase.errors());
  }

  // -----------------------------------------------------------------------
  // dedupeByPrimaryKeysLastWins — UPSERT 파일 내 중복 PK 접기 (last-write-wins)
  // -----------------------------------------------------------------------

  // PK 행 맵을 간단히 만드는 헬퍼 (가변 HashMap — 운영 코드의 rowMaps와 동일 형태)
  private static Map<String, Object> row(
      String pk, String pkValue, String otherCol, Object otherVal) {
    Map<String, Object> m = new HashMap<>();
    m.put(pk, pkValue);
    m.put(otherCol, otherVal);
    return m;
  }

  @Test
  void dedupeByPrimaryKeysLastWins_collapsesDuplicates_keepingLastOccurrence() {
    // 같은 disaster_no(UR42...)가 두 번 등장 — UPSERT 의미상 마지막 행이 이겨야 한다
    List<Map<String, Object>> rows =
        List.of(
            row("disaster_no", "UR4206974320", "scale", "A"),
            row("disaster_no", "UR0000000001", "scale", "B"),
            row("disaster_no", "UR4206974320", "scale", "C")); // 1행과 중복 → last-wins

    DataValidationService.DedupResult result =
        service.dedupeByPrimaryKeysLastWins(rows, List.of("disaster_no"));

    // 중복 1건이 제거되어 2행만 남고, 제거 건수가 보고된다
    assertThat(result.rows()).hasSize(2);
    assertThat(result.removedCount()).isEqualTo(1);

    // last-wins: 남은 UR4206974320 행의 값은 마지막 occurrence(C)여야 한다
    Map<String, Object> kept =
        result.rows().stream()
            .filter(r -> "UR4206974320".equals(r.get("disaster_no")))
            .findFirst()
            .orElseThrow();
    assertThat(kept.get("scale")).isEqualTo("C");
  }

  @Test
  void dedupeByPrimaryKeysLastWins_compositeKey_treatsFullTupleAsKey() {
    // 복합 PK: (region, disaster_no) — 한 컬럼만 같으면 중복 아님
    Map<String, Object> r1 = new HashMap<>();
    r1.put("region", "donghae");
    r1.put("disaster_no", "UR1");
    Map<String, Object> r2 = new HashMap<>();
    r2.put("region", "gangneung");
    r2.put("disaster_no", "UR1"); // disaster_no는 같지만 region이 달라 중복 아님
    Map<String, Object> r3 = new HashMap<>();
    r3.put("region", "donghae");
    r3.put("disaster_no", "UR1"); // r1과 완전히 동일한 복합키 → 중복

    DataValidationService.DedupResult result =
        service.dedupeByPrimaryKeysLastWins(List.of(r1, r2, r3), List.of("region", "disaster_no"));

    assertThat(result.rows()).hasSize(2);
    assertThat(result.removedCount()).isEqualTo(1);
  }
}
