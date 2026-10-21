package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ValidationErrorDetail;
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

  // #516 — TIMESTAMP 컬럼에 시간 없이 날짜만(yyyy-MM-dd) 있는 값도 자정 시각으로 관대하게 허용해야 한다.
  @Test
  void convertValue_timestamp_dateOnlyValue_fallsBackToStartOfDay() throws Exception {
    Object result = service.convertValue("2026-01-01", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
  }

  @Test
  void convertValue_timestamp_dateOnlySlashFormat_fallsBackToStartOfDay() throws Exception {
    Object result = service.convertValue("2026/01/01", "TIMESTAMP");
    assertThat(result)
        .isInstanceOf(LocalDateTime.class)
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
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

  // -----------------------------------------------------------------------
  // 물리 타입 제약 — VARCHAR(n) 길이 / NUMERIC(18,6) 정수부 / BIGINT 범위 (WD-96)
  //
  // 형식 변환만 통과시키면 사전검증은 "성공"인데 INSERT 가 DB 오류로 잡 전체를 실패시키고 사용자는 행·칸을 모른다.
  // 세 경로(validate·validateWithMapping·toRows)가 공유하는 convertRowOrNull 에서 거부되는지 행 단위로 확인한다.
  // -----------------------------------------------------------------------

  /** 한 칸 검증 결과 — 통과하면 value(정규화된 저장값), 거부되면 error. */
  record Cell(Object value, ValidationErrorDetail error) {}

  /** 한 칸짜리 행을 매핑 경로(웹이 쓰는 경로)로 검증한다. 통합 테스트도 같은 도우미를 쓴다. */
  static Cell validateCell(
      DataValidationService service, DatasetColumnResponse column, String value) {
    Map<String, String> row = new HashMap<>();
    row.put(column.columnName(), value);
    DataValidationService.ValidationResultWithDetails vr =
        service.validateWithMapping(
            List.of(row),
            List.of(column),
            List.of(new ColumnMappingEntry(column.columnName(), column.columnName())));
    return vr.errors().isEmpty()
        ? new Cell(vr.validRows().get(0).get(0), null)
        : new Cell(null, vr.errors().get(0));
  }

  private static DatasetColumnResponse varcharCol(String name, Integer maxLength) {
    return new DatasetColumnResponse(
        1L, name, name, "VARCHAR", maxLength, true, false, null, 0, false);
  }

  @Test
  void varchar_exactlyMaxLength_isAccepted_andOneMore_isRejectedWithCounts() {
    DatasetColumnResponse code = varcharCol("code", 10);

    assertThat(validateCell(service, code, "a".repeat(10)).error()).isNull();

    var err = validateCell(service, code, "a".repeat(13)).error();
    assertThat(err).isNotNull();
    assertThat(err.rowNumber()).isEqualTo(1);
    assertThat(err.columnName()).isEqualTo("code");
    assertThat(err.error()).isEqualTo("최대 10자까지 입력할 수 있습니다(입력 13자)");
  }

  @Test
  void varchar_countsKoreanAndEmojiAsOneCharacterEach_likePostgres() {
    DatasetColumnResponse code = varcharCol("code", 10);
    // 이모지(😀)는 UTF-16 2단위지만 PostgreSQL 기준 1자 — length() 로 세면 5개만으로도 10을 채워 오검출한다.
    String tenEmoji = "😀".repeat(10);
    assertThat(tenEmoji.length()).isEqualTo(20);
    assertThat(validateCell(service, code, tenEmoji).error()).isNull();
    assertThat(validateCell(service, code, "가".repeat(10)).error()).isNull();

    assertThat(validateCell(service, code, "😀".repeat(11)).error().error())
        .isEqualTo("최대 10자까지 입력할 수 있습니다(입력 11자)");
    assertThat(validateCell(service, code, "가".repeat(11)).error().error())
        .isEqualTo("최대 10자까지 입력할 수 있습니다(입력 11자)");
  }

  @Test
  void varchar_countsValueAfterTrimAndQuoteStrip_whichIsWhatGetsStored() {
    DatasetColumnResponse code = varcharCol("code", 5);
    // 저장되는 값은 trim·따옴표 제거 후 "abcde"(5자) — 원문 길이(9자)로 세면 안 된다.
    assertThat(validateCell(service, code, "  \"abcde\"").error()).isNull();
  }

  @Test
  void varchar_withoutMaxLength_usesPhysicalDefault255() {
    // 길이 미지정 VARCHAR 는 물리 타입이 VARCHAR(255) — 검사 생략이 아니라 255 로 막아야 한다.
    DatasetColumnResponse code = varcharCol("code", null);
    assertThat(validateCell(service, code, "a".repeat(255)).error()).isNull();
    assertThat(validateCell(service, code, "a".repeat(256)).error().error())
        .isEqualTo("최대 255자까지 입력할 수 있습니다(입력 256자)");
  }

  @Test
  void text_hasNoLengthLimit() {
    assertThat(validateCell(service, col("memo", "TEXT", true), "a".repeat(20_000)).error())
        .isNull();
  }

  @Test
  void decimal_integerPart12Digits_isAccepted_13Digits_isRejected() {
    DatasetColumnResponse amount = col("amount", "DECIMAL", true);

    assertThat(validateCell(service, amount, "123456789012").error()).isNull();
    assertThat(validateCell(service, amount, "123456789012.123456").error()).isNull();
    assertThat(validateCell(service, amount, "-999999999999.999999").error()).isNull();
    // 소수부 6자리 초과는 PG 가 반올림 저장하므로 오류가 아니다(WD-98 범위).
    assertThat(validateCell(service, amount, "1.1234567").error()).isNull();

    var err = validateCell(service, amount, "12345678901234").error();
    assertThat(err).isNotNull();
    assertThat(err.columnName()).isEqualTo("amount");
    assertThat(err.error()).isEqualTo("정수부는 최대 12자리까지 입력할 수 있습니다");
    assertThat(validateCell(service, amount, "1234567890123").error().error())
        .contains("정수부는 최대 12자리");
    assertThat(validateCell(service, amount, "-1234567890123").error().error())
        .contains("정수부는 최대 12자리");
    assertThat(validateCell(service, amount, "1,234,567,890,123").error().error())
        .contains("정수부는 최대 12자리");
  }

  @Test
  void decimal_exponentNotation_isJudgedByValue() {
    DatasetColumnResponse amount = col("amount", "DECIMAL", true);
    assertThat(validateCell(service, amount, "1E11").error()).isNull();
    assertThat(validateCell(service, amount, "1.5E-3").error()).isNull();
    assertThat(validateCell(service, amount, "1E12").error().error()).contains("정수부는 최대 12자리");
    assertThat(validateCell(service, amount, "-1E13").error().error()).contains("정수부는 최대 12자리");
  }

  @Test
  void decimal_hugeExponent_isRejectedQuickly_withoutExpandingTheNumber() {
    // "1E999999999" 를 펼치면(setScale/toPlainString) 거대한 수를 할당한다 — compareTo 만으로 즉시 판정해야 한다.
    DatasetColumnResponse amount = col("amount", "DECIMAL", true);
    long start = System.nanoTime();
    assertThat(validateCell(service, amount, "1E999999999").error().error())
        .contains("정수부는 최대 12자리");
    assertThat(validateCell(service, amount, "1E-999999999").error()).isNull();
    assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
  }

  @Test
  void decimal_valueThatRoundsUpTo13IntegerDigits_isRejected() {
    // PG 는 소수 6자리로 반올림한 뒤 정밀도를 검사한다 — 정수부 12자리라도 반올림으로 10^12 가 되면 overflow.
    DatasetColumnResponse amount = col("amount", "DECIMAL", true);
    assertThat(validateCell(service, amount, "999999999999.9999994").error()).isNull();
    assertThat(validateCell(service, amount, "999999999999.9999995").error().error())
        .contains("반올림하면 정수부가 13자리")
        .contains("정수부는 최대 12자리");
    assertThat(validateCell(service, amount, "-999999999999.9999995").error().error())
        .contains("반올림하면");
  }

  @Test
  void integer_bigintBoundary_andOverflowHasRangeMessage() throws Exception {
    assertThat(service.convertValue(String.valueOf(Long.MAX_VALUE), "INTEGER"))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(service.convertValue(String.valueOf(Long.MIN_VALUE), "INTEGER"))
        .isEqualTo(Long.MIN_VALUE);

    // Long.MAX_VALUE + 1 — 형식은 정수인데 BIGINT 범위 밖: "형식 아님"이 아니라 범위 초과로 안내한다.
    assertThatThrownBy(() -> service.convertValue("9223372036854775808", "INTEGER"))
        .hasMessageContaining("정수 범위를 벗어났습니다")
        .hasMessageContaining("9223372036854775807");
    assertThatThrownBy(() -> service.convertValue("-99,999,999,999,999,999,999", "INTEGER"))
        .hasMessageContaining("정수 범위를 벗어났습니다");
    // 숫자가 아닌 값은 기존 형식 오류 그대로
    assertThatThrownBy(() -> service.convertValue("12a", "INTEGER"))
        .hasMessageContaining("정수 형식이 아닙니다");
  }

  @Test
  void physicalLimit_violation_isReportedByAllThreePaths() {
    DatasetColumnResponse code = varcharCol("code", 3);
    Map<String, String> row = new HashMap<>();
    row.put("code", "abcd");

    // 매핑 없는 경로 — 요약 문자열에 행·칸·사유
    DataValidationService.ValidationResult plain =
        service.validate(List.of(row), List.of(code), 249);
    assertThat(plain.errors()).containsExactly("250행 'code' 컬럼 - 최대 3자까지 입력할 수 있습니다(입력 4자)");

    // 삽입 변환(toRows)은 검증을 통과한 행만 받으므로, 제약 위반 행이 오면 조용히 넣지 않고 불변식 위반으로 실패한다.
    assertThatThrownBy(() -> service.toRows(List.of(row), List.of(code), null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void decimal_isNormalizedToPhysicalScale6_withPgRounding() {
    DatasetColumnResponse amount = col("amount", "DECIMAL", true);
    // 0.5 는 0 에서 먼 쪽으로 — 음수도 절댓값 기준(PG numeric 과 동일; DB 대조 테스트로 고정)
    assertThat(validateCell(service, amount, "1.1234565").value())
        .isEqualTo(new BigDecimal("1.123457"));
    assertThat(validateCell(service, amount, "-0.0000005").value())
        .isEqualTo(new BigDecimal("-0.000001"));
    assertThat(validateCell(service, amount, "0.0000004").value())
        .isEqualTo(new BigDecimal("0.000000"));
    assertThat(validateCell(service, amount, "1.5").value()).isEqualTo(new BigDecimal("1.500000"));
    assertThat(validateCell(service, amount, "1E11").value())
        .isEqualTo(new BigDecimal("100000000000.000000"));
  }

  @Test
  void decimal_tinyExponentAndLongFraction_areNormalizedInsteadOfFailingInDb() {
    // PG numeric 은 지수 -1000 미만·소수부 1000자리 초과 리터럴을 22003 으로 거부한다 — 정규화해 넘기면 0/반올림 값으로 저장된다.
    DatasetColumnResponse amount = col("amount", "DECIMAL", true);
    assertThat(validateCell(service, amount, "1E-1001").value())
        .isEqualTo(new BigDecimal("0.000000"));
    assertThat(validateCell(service, amount, "-1E-1001").value())
        .isEqualTo(new BigDecimal("0.000000"));
    assertThat(validateCell(service, amount, "0." + "1".repeat(1500)).value())
        .isEqualTo(new BigDecimal("0.111111"));
  }

  @Test
  void varchar_excessOnlySpaces_isTruncatedLikePostgres_otherwiseRejected() {
    DatasetColumnResponse code = varcharCol("code", 3);
    // 따옴표 안 공백은 trim 뒤에도 남는다 — 초과분이 전부 공백이면 PG 처럼 잘라 저장한다(SQL 표준).
    assertThat(validateCell(service, code, "\"abc   \"").value()).isEqualTo("abc");
    assertThat(validateCell(service, code, "\"😀😀😀  \"").value()).isEqualTo("😀😀😀");
    assertThat(validateCell(service, code, "\"abc  d\"").error().error())
        .isEqualTo("최대 3자까지 입력할 수 있습니다(입력 6자)");
    // 탭은 PG 기준 공백이 아니다
    assertThat(validateCell(service, code, "\"abc\t\"").error()).isNotNull();
  }

  @Test
  void nulCharacter_inTextOrVarchar_isRejectedWithRowAndColumn() {
    var textErr = validateCell(service, col("memo", "TEXT", true), "a\u0000b").error();
    assertThat(textErr.columnName()).isEqualTo("memo");
    assertThat(textErr.error()).isEqualTo("저장할 수 없는 문자(NUL)가 포함되어 있습니다");
    assertThat(validateCell(service, varcharCol("code", 10), "a\u0000b").error().error())
        .isEqualTo("저장할 수 없는 문자(NUL)가 포함되어 있습니다");
  }

  /**
   * Excel 날짜 셀은 임포트 파서가 "2026-05-10T00:00" 으로 읽는다 — DATE 컬럼은 자정 ISO 날짜시각을 날짜로 받아야 내보낸 Excel(WD-159,
   * 날짜 셀)을 다시 임포트할 수 있다. 자정이 아닌 시각은 자르면 값이 바뀌므로 거부한다.
   */
  @Test
  void convertValue_date_acceptsMidnightIsoDateTimeFromExcelDateCell() throws Exception {
    assertThat(service.convertValue("2026-05-10T00:00", "DATE"))
        .isEqualTo(LocalDate.of(2026, 5, 10));
    assertThat(service.convertValue("2026-05-10T00:00:00", "DATE"))
        .isEqualTo(LocalDate.of(2026, 5, 10));
    assertThatThrownBy(() -> service.convertValue("2026-05-10T10:30", "DATE"))
        .hasMessageContaining("날짜 형식이 아닙니다");
  }

  /** 내보낸 CSV 의 시각은 0 이 아닌 소수 초를 붙인다(WD-159) — 다시 임포트할 때 그대로 읽혀야 한다. */
  @Test
  void convertValue_timestamp_acceptsOptionalFractionalSeconds() throws Exception {
    assertThat(service.convertValue("2025-12-01 08:05:30.123", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2025, 12, 1, 8, 5, 30, 123_000_000));
    assertThat(service.convertValue("2025-12-01 08:05:30.000456", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2025, 12, 1, 8, 5, 30, 456_000));
    assertThat(service.convertValue("2025-12-01 08:05:30", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2025, 12, 1, 8, 5, 30));
  }
}
