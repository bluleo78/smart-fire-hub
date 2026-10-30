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

  /** 천 단위 자리가 아닌 쉼표는 지우지 않고 형식 오류로 막는다(WD-370) — 예전에는 「1,2,3」→123, 「12,34」→1234 로 저장됐다. */
  @Test
  void convertValue_integer_misplacedComma_rejected() {
    for (String v : new String[] {"1,2,3", "12,34", "1,0000", ",123", "123,", "1,,000"}) {
      assertThatThrownBy(() -> service.convertValue(v, "INTEGER"))
          .as(v)
          .hasMessage("정수 형식이 아닙니다: " + v + DataValidationService.COMMA_FORMAT_HINT);
    }
  }

  /** 소수 쉼표(「12,5」 = 12.5)를 지워 125(10배)로 저장하지 않는다(WD-370). 소수부의 쉼표도 막는다. */
  @Test
  void convertValue_decimal_misplacedComma_rejected() {
    for (String v : new String[] {"12,5", "1,2,3", "1,234.5,6", "0,5"}) {
      assertThatThrownBy(() -> service.convertValue(v, "DECIMAL"))
          .as(v)
          .hasMessage("소수 형식이 아닙니다: " + v + DataValidationService.COMMA_FORMAT_HINT);
    }
  }

  /** 올바른 천 단위 쉼표는 여러 묶음·부호가 있어도 그대로 받는다. */
  @Test
  void convertValue_validThousandsGroups_parsed() throws Exception {
    assertThat(service.convertValue("1,000", "INTEGER")).isEqualTo(1000L);
    assertThat(service.convertValue("-1,234,567", "INTEGER")).isEqualTo(-1234567L);
    assertThat(((BigDecimal) service.convertValue("12,345,678.9", "DECIMAL")))
        .isEqualByComparingTo("12345678.9");
    assertThat(((BigDecimal) service.convertValue("+1,000.", "DECIMAL")))
        .isEqualByComparingTo("1000");
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
    // 자정이 아닌 시각은 자르면 값이 바뀌므로 거부하고, 왜인지(숨은 시각) 짚어 준다(WD-252)
    assertThatThrownBy(() -> service.convertValue("2026-05-10T10:30", "DATE"))
        .hasMessageContaining("날짜에 시각(10:30:00)이 들어 있어");
  }

  // -----------------------------------------------------------------------
  // WD-252: 엑셀 파서 출력(날짜·시각·오류 셀·큰 수)
  // -----------------------------------------------------------------------

  /**
   * 엑셀 파서는 날짜시각 서식 셀을 "yyyy-MM-dd HH:mm:ss" 로 읽는다 — 자정이면 DATE 열이 날짜로 받아야 한다(예전 ISO 'T' 와 같은 동작).
   */
  @Test
  void convertValue_date_acceptsMidnightSpaceDateTimeFromExcelParser() throws Exception {
    assertThat(service.convertValue("2025-01-21 00:00:00", "DATE"))
        .isEqualTo(LocalDate.of(2025, 1, 21));
    assertThat(service.convertValue("2025-01-21", "DATE")).isEqualTo(LocalDate.of(2025, 1, 21));
    assertThatThrownBy(() -> service.convertValue("2025-01-21 06:00:00", "DATE"))
        .hasMessageContaining("날짜에 시각(06:00:00)이 들어 있어")
        .hasMessageContaining("2025-01-21 06:00:00");
    assertThat(service.convertValue("2025-01-21 06:00:00", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2025, 1, 21, 6, 0));
  }

  /** h:mm 서식 셀(시각만)은 날짜·날짜시간 열에 가짜 날짜(1899-12-31)를 붙여 저장하지 않고 거부한다. */
  @Test
  void convertValue_timeOnlyValue_isRejectedForDateAndTimestamp() throws Exception {
    assertThatThrownBy(() -> service.convertValue("12:00:00", "TIMESTAMP"))
        .hasMessageContaining("시각만 있는 값은 날짜로 저장할 수 없습니다")
        .hasMessageContaining("12:00:00");
    assertThatThrownBy(() -> service.convertValue("12:00:00", "DATE"))
        .hasMessageContaining("시각만 있는 값은 날짜로 저장할 수 없습니다");
    assertThat(service.convertValue("12:00:00", "TEXT")).isEqualTo("12:00:00");
  }

  /** 엑셀 오류 셀 표식은 어떤 타입으로도 받지 않고, 행·열과 엑셀 표시 문자열로 알린다. */
  @Test
  void excelErrorCell_isRejectedWithRowColumnAndExcelDisplay() {
    String marker = LegacyExcelDataFormatter.errorCellFromXssf("ERROR:#DIV/0!");
    for (String type : List.of("TEXT", "INTEGER", "DECIMAL", "DATE", "TIMESTAMP", "BOOLEAN")) {
      assertThatThrownBy(() -> service.convertValue(marker, type))
          .as(type)
          .hasMessageContaining("엑셀 오류 값(#DIV/0!)은 가져올 수 없습니다");
    }
    Map<String, String> row = new HashMap<>();
    row.put("err", marker);
    DataValidationService.ValidationResult result =
        service.validate(List.of(row), List.of(col("err", "TEXT", true)));
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.errors().get(0))
        .startsWith("1행 'err' 컬럼")
        .contains("엑셀 오류 값(#DIV/0!)")
        .doesNotContain("\u0000");
    // 매핑 경로의 상세 값은 표식이 아니라 엑셀이 보여 주는 모양
    ValidationErrorDetail detail = validateCell(service, col("err", "TEXT", true), marker).error();
    assertThat(detail.value()).isEqualTo("#DIV/0!");
    // CSV 처럼 같은 글자를 입력한 값은 그대로 문자열이다
    assertThat(validateCell(service, col("err", "TEXT", true), "ERROR:#DIV/0!").error()).isNull();
  }

  /** 엑셀 1e20 셀은 평문으로 읽혀 INTEGER 열에서 Long 최댓값으로 포화되지 않고 범위 오류가 난다. */
  @Test
  void convertValue_integer_excelHugeNumber_isRangeError() {
    assertThatThrownBy(() -> service.convertValue("100000000000000000000", "INTEGER"))
        .hasMessageContaining("정수 범위를 벗어났습니다: 100000000000000000000");
    assertThatThrownBy(() -> service.convertValue("-10000000000000000000", "INTEGER"))
        .hasMessageContaining("정수 범위를 벗어났습니다");
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

  // -----------------------------------------------------------------------
  // WD-246 — 일·월이 앞에 오는 날짜는 열 단위로 한 가지 순서로 해석
  // -----------------------------------------------------------------------

  /** 한 열짜리 DATE/TIMESTAMP 행 목록을 만든다. */
  private static List<Map<String, String>> dateRows(String column, String... values) {
    return java.util.Arrays.stream(values).map(v -> Map.of(column, v)).toList();
  }

  /** 임포트 잡과 같은 순서(검증 → 같은 상태로 변환)로 열 값을 변환해 첫 칸 값만 모은다. */
  private List<Object> importColumn(List<DatasetColumnResponse> columns, String... values) {
    DataValidationService.DateOrderContext ctx = new DataValidationService.DateOrderContext();
    List<Map<String, String>> rows = dateRows(columns.get(0).columnName(), values);
    DataValidationService.ValidationResult vr = service.validate(rows, columns, 0, ctx);
    assertThat(vr.errors()).isEmpty();
    return service.toRows(rows, columns, null, ctx).stream().map(r -> r.get(0)).toList();
  }

  @Test
  void dayMonthLeading_monthFirstColumn_ambiguousRowsBeforeEvidenceAreMonthFirstToo() {
    // 12/31 이 월/일 근거 — 앞에 나온 01/02 도 1월 2일, 03-04 도 3월 4일이어야 한다(예전: 2월 1일·4월 3일)
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    assertThat(importColumn(columns, "01/02/2026", "12/31/2026", "03-04-2026"))
        .containsExactly(
            LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 3, 4));
  }

  @Test
  void dayMonthLeading_dayFirstColumn_isReadDayFirst() {
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    assertThat(importColumn(columns, "01/02/2026", "31/12/2026", "05.06.2026"))
        .containsExactly(
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 6, 5));
  }

  @Test
  void dayMonthLeading_mixedColumn_isRejectedInKoreanWithBothExamples() {
    // t2 재현 — 같은 열에 31/12(일/월)와 12/31(월/일)이 함께 있으면 섞인 열로 거부한다
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    DataValidationService.ValidationResult vr =
        service.validate(dateRows("dt", "31/12/2026", "01/02/2026", "12/31/2026"), columns);
    assertThat(vr.validCount()).isEqualTo(2);
    assertThat(vr.errors())
        .singleElement()
        .asString()
        .startsWith("3행 'dt' 컬럼 - 같은 열에 일/월/연도와 월/일/연도 형식이 섞여 있습니다")
        .contains("31/12/2026(1행, 일/월/연도)")
        .contains("12/31/2026(월/일/연도)");
  }

  @Test
  void dayMonthLeading_allAmbiguous_keepsDayFirstDefault() {
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    assertThat(importColumn(columns, "01/02/2026", "03-04-2026", "1/2/2026"))
        .containsExactly(
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 2, 1));
  }

  @Test
  void dayMonthLeading_yearFirstFormats_unchangedInMonthFirstColumn() {
    // 연도가 앞인 형식은 열 순서와 무관하게 연-월-일 그대로
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    assertThat(importColumn(columns, "2026-01-02", "12/31/2026", "2026/03/04", "20260506"))
        .containsExactly(
            LocalDate.of(2026, 1, 2),
            LocalDate.of(2026, 12, 31),
            LocalDate.of(2026, 3, 4),
            LocalDate.of(2026, 5, 6));
  }

  @Test
  void dayMonthLeading_evidenceInLaterBatch_appliesToEarlierBatchAndConflictAcrossBatchesFails() {
    // 임포트 잡처럼 배치마다 같은 상태를 넘긴다 — 샘플(앞 배치)이 모두 모호하고 뒤 배치에서 월/일 근거가 나와도
    // 삽입 변환은 앞 배치까지 월/일로 해석한다.
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    DataValidationService.DateOrderContext ctx = new DataValidationService.DateOrderContext();
    List<Map<String, String>> batch1 = dateRows("dt", "01/02/2026", "03/04/2026");
    List<Map<String, String>> batch2 = dateRows("dt", "01/13/2026");
    assertThat(service.validate(batch1, columns, 0, ctx).errors()).isEmpty();
    assertThat(service.validate(batch2, columns, 2, ctx).errors()).isEmpty();
    assertThat(service.toRows(batch1, columns, null, ctx))
        .containsExactly(List.of(LocalDate.of(2026, 1, 2)), List.of(LocalDate.of(2026, 3, 4)));

    // 앞 배치가 일/월로 정한 열에 뒤 배치의 월/일 값이 나오면 그 행에서 거부(전역 행 번호)
    DataValidationService.DateOrderContext ctx2 = new DataValidationService.DateOrderContext();
    assertThat(service.validate(dateRows("dt", "31/12/2026"), columns, 0, ctx2).errors()).isEmpty();
    assertThat(service.validate(dateRows("dt", "12/31/2026"), columns, 2000, ctx2).errors())
        .singleElement()
        .asString()
        .startsWith("2001행 'dt' 컬럼 - 같은 열에 일/월/연도와 월/일/연도 형식이 섞여 있습니다")
        .contains("31/12/2026(1행, 일/월/연도)");
  }

  @Test
  void dayMonthLeading_mappingPath_usesSameColumnOrder() {
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    List<ColumnMappingEntry> mappings = List.of(new ColumnMappingEntry("날짜", "dt"));
    List<Map<String, String>> rows = dateRows("날짜", "01/02/2026", "12/31/2026");
    DataValidationService.DateOrderContext ctx = new DataValidationService.DateOrderContext();
    assertThat(service.validateWithMapping(rows, columns, mappings, 0, ctx).errors()).isEmpty();
    assertThat(service.toRows(rows, columns, mappings, ctx).get(0))
        .containsExactly(LocalDate.of(2026, 1, 2));
    // 섞인 열은 매핑 경로에서도 상세 사유로 거부
    List<ValidationErrorDetail> errors =
        service
            .validateWithMapping(dateRows("날짜", "31/12/2026", "12/31/2026"), columns, mappings)
            .errors();
    assertThat(errors)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.rowNumber()).isEqualTo(2);
              assertThat(e.columnName()).isEqualTo("dt");
              assertThat(e.error()).contains("섞여 있습니다");
            });
  }

  @Test
  void dayMonthLeading_legacyToRowsWithoutContext_isSelfConsistentWithinBatch() {
    List<DatasetColumnResponse> columns = List.of(col("dt", "DATE", true));
    assertThat(service.toRows(dateRows("dt", "01/02/2026", "12/31/2026"), columns, null))
        .containsExactly(List.of(LocalDate.of(2026, 1, 2)), List.of(LocalDate.of(2026, 12, 31)));
  }

  @Test
  void dayMonthLeading_timestampColumn_followsSameRule_withAndWithoutTime() {
    // TIMESTAMP 도 같은 규칙 — 예전엔 시각부가 있으면 일/월, 날짜만이면 일/월→월/일 첫 성공이었다
    List<DatasetColumnResponse> columns = List.of(col("ts", "TIMESTAMP", true));
    assertThat(importColumn(columns, "01/02/2026 10:30:00", "12/31/2026", "2026-05-06 07:08:09"))
        .containsExactly(
            LocalDateTime.of(2026, 1, 2, 10, 30, 0),
            LocalDateTime.of(2026, 12, 31, 0, 0, 0),
            LocalDateTime.of(2026, 5, 6, 7, 8, 9));
    DataValidationService.ValidationResult mixed =
        service.validate(dateRows("ts", "31/12/2026 01:00:00", "12/31/2026"), columns);
    assertThat(mixed.errors()).singleElement().asString().contains("섞여 있습니다");
  }

  @Test
  void dayMonthLeading_nonexistentDate_isRejectedInsteadOfClampedToMonthEnd() {
    // 02/31 은 기본 해석기라면 2월 28일로 조용히 바뀐다 — 없는 날짜로 거부해야 한다
    assertThatThrownBy(() -> service.convertValue("02/31/2026", "DATE"))
        .hasMessageContaining("존재하지 않는 날짜입니다: 02/31/2026");
    // 두 칸 모두 12 초과는 어느 순서로도 날짜가 아니다
    assertThatThrownBy(() -> service.convertValue("13/13/2026", "DATE"))
        .hasMessageContaining("날짜 형식이 아닙니다");
    // DATE 는 시각부를 받지 않는다
    assertThatThrownBy(() -> service.convertValue("01/02/2026 10:00:00", "DATE"))
        .hasMessageContaining("날짜 형식이 아닙니다");
  }

  // -----------------------------------------------------------------------
  // 달력에 없는 날짜·시각 (WD-379)
  //
  // 기본 SMART 해석기는 2026-02-30 을 2월 28일로, 24:00:00 을 다음 날 0시로 바꿔 「검증 성공」 뒤 다른 값을 저장했다.
  // 행 추가 API 처럼 거부해야 하고, 형식 오류와 구분되는 「달력에 없는」 사유로 알려야 한다.
  // -----------------------------------------------------------------------

  @Test
  void date_nonexistentCalendarDates_areRejectedNotClamped() {
    for (String value :
        List.of("2026-02-30", "2026-02-29", "2026-04-31", "2026/04/31", "20260230")) {
      assertThatThrownBy(() -> service.convertValue(value, "DATE"))
          .as(value)
          .hasMessageContaining(DataValidationService.NONEXISTENT_DATE_MESSAGE + value);
    }
  }

  @Test
  void date_midnightDateTimeWith24Hour_isRejectedNotRolledToNextDay() {
    // 예전엔 2026-03-02 로 바뀌어 DATE 열에 저장됐다
    assertThatThrownBy(() -> service.convertValue("2026-03-01 24:00:00", "DATE"))
        .hasMessageContaining(
            DataValidationService.NONEXISTENT_DATE_MESSAGE + "2026-03-01 24:00:00");
  }

  @Test
  void date_leapDayAndValidFormats_stillAccepted() throws Exception {
    // 윤년 2월 29일은 있는 날짜
    assertThat(service.convertValue("2024-02-29", "DATE")).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(service.convertValue("2024/02/29", "DATE")).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(service.convertValue("20240229", "DATE")).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(service.convertValue("2026-04-30", "DATE")).isEqualTo(LocalDate.of(2026, 4, 30));
    assertThat(service.convertValue("2026-12-31", "DATE")).isEqualTo(LocalDate.of(2026, 12, 31));
    // 자정 날짜시각(ISO·엑셀 파서 출력 모양)은 날짜로 받는다
    assertThat(service.convertValue("2026-05-10T00:00", "DATE"))
        .isEqualTo(LocalDate.of(2026, 5, 10));
    assertThat(service.convertValue("2026-05-10 00:00:00", "DATE"))
        .isEqualTo(LocalDate.of(2026, 5, 10));
  }

  @Test
  void timestamp_nonexistentCalendarValues_areRejectedNotClampedOrRolled() {
    for (String value :
        List.of(
            "2026-02-30 10:00:00",
            "2026-02-29 10:00:00",
            "2026/04/31 10:00:00",
            "2026-02-30T10:00:00",
            "20260230100000",
            "2026-03-01 24:00:00",
            "2026-03-01T24:00:00",
            "20260301240000",
            // 날짜만 있는 값을 자정으로 받는 경로(#516)도 같은 규칙
            "2026-02-30",
            "2026-04-31",
            "20260230")) {
      assertThatThrownBy(() -> service.convertValue(value, "TIMESTAMP"))
          .as(value)
          .hasMessageContaining(DataValidationService.NONEXISTENT_DATE_MESSAGE + value);
    }
  }

  @Test
  void timestamp_validValuesInAllAcceptedFormats_stillAccepted() throws Exception {
    LocalDateTime expected = LocalDateTime.of(2024, 2, 29, 23, 59, 59);
    for (String value :
        List.of(
            "2024-02-29 23:59:59",
            "2024/02/29 23:59:59",
            "2024-02-29T23:59:59",
            "20240229235959",
            "29/02/2024 23:59:59")) {
      assertThat(service.convertValue(value, "TIMESTAMP")).as(value).isEqualTo(expected);
    }
    assertThat(service.convertValue("2024-02-29 23:59:59.123456", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 123_456_000));
    assertThat(service.convertValue("2024-02-29T23:59", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2024, 2, 29, 23, 59));
    assertThat(service.convertValue("20240229", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2024, 2, 29, 0, 0));
  }

  @Test
  void timestamp_offsetIsoValue_isRejectedWithZoneLessIsoInAllowedFormats() {
    // 시간대가 붙은 값은 받지 않되, 허용 형식 안내가 「시간대 없는 ISO」임을 밝혀야 한다
    for (String value : List.of("2026-03-01T10:00:00Z", "2026-03-01T10:00:00+09:00")) {
      assertThatThrownBy(() -> service.convertValue(value, "TIMESTAMP"))
          .as(value)
          .hasMessageContaining("날짜시간 형식이 아닙니다: " + value)
          .hasMessageContaining("ISO(시간대 없이, 예: 2026-03-01T10:00:00)");
    }
  }

  @Test
  void nonexistentDate_isReportedAsCellErrorOnImportPath_andNeverInserted() {
    // 임포트 검증(매핑 경로)에서 칸 단위 오류로 잡히고, 삽입 변환(toRows)도 같은 변환을 써서 조용히 넣지 않는다
    DatasetColumnResponse dt = col("dt", "DATE", true);
    var err = validateCell(service, dt, "2026-02-30").error();
    assertThat(err).isNotNull();
    assertThat(err.error())
        .startsWith(DataValidationService.NONEXISTENT_DATE_MESSAGE + "2026-02-30");

    Map<String, String> row = new HashMap<>();
    row.put("dt", "2026-02-30");
    assertThatThrownBy(() -> service.toRows(List.of(row), List.of(dt), null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void dayMonthLeading_nonexistentDate_usesSameWordingAsYearLeading() {
    // 값 모양이 달라도 같은 결함이면 같은 문구 — 일·월 앞 형식은 해석 순서만 덧붙인다(WD-379)
    assertThatThrownBy(() -> service.convertValue("30/02/2026", "DATE"))
        .hasMessage(DataValidationService.nonexistentDateMessage("30/02/2026", "일/월/연도"));
    assertThatThrownBy(() -> service.convertValue("2026-02-30", "DATE"))
        .hasMessage(DataValidationService.nonexistentDateMessage("2026-02-30", null));
  }

  @Test
  void yearZeroAndNegativeYears_areRejectedAsFormatError() {
    // uuuu 는 0년·음수 연도를 읽지만 예전 yyyy 는 거부했고 PG 범위 밖이라 INSERT 에서 실패한다(WD-379)
    for (String value : List.of("0000-01-01", "-2026-01-01", "00000101", "0000/01/01")) {
      assertThatThrownBy(() -> service.convertValue(value, "DATE"))
          .as(value)
          .hasMessageContaining("날짜 형식이 아닙니다: " + value);
      assertThatThrownBy(() -> service.convertValue(value, "TIMESTAMP"))
          .as(value)
          .hasMessageContaining("날짜시간 형식이 아닙니다: " + value);
    }
    for (String value : List.of("0000-01-01 10:00:00", "-2026-01-01T10:00:00", "00000101100000")) {
      assertThatThrownBy(() -> service.convertValue(value, "TIMESTAMP"))
          .as(value)
          .hasMessageContaining("날짜시간 형식이 아닙니다: " + value);
    }
  }

  @Test
  void implausibleFields_areFormatErrorNotNonexistentDate() {
    // 8·14자리 아무 숫자(56월)나 13월은 「존재하지 않는 날짜」가 아니라 형식 오류다(WD-379)
    for (String value : List.of("12345678", "2026-13-01", "2026-00-10", "2026-01-32")) {
      assertThatThrownBy(() -> service.convertValue(value, "DATE"))
          .as(value)
          .hasMessageContaining("날짜 형식이 아닙니다: " + value);
      assertThatThrownBy(() -> service.convertValue(value, "TIMESTAMP"))
          .as(value)
          .hasMessageContaining("날짜시간 형식이 아닙니다: " + value);
    }
    for (String value : List.of("12345678901234", "2026-03-01 25:00:00", "2026-03-01T23:59:60")) {
      assertThatThrownBy(() -> service.convertValue(value, "TIMESTAMP"))
          .as(value)
          .hasMessageContaining("날짜시간 형식이 아닙니다: " + value);
    }
  }

  @Test
  void date_realDateWithOutOfRangeTime_isFormatErrorNotNonexistentDate() {
    // 날짜는 있는 날인데 시각이 범위 밖이면 달력 문구가 아닌 기존 형식 오류로 알린다(WD-379)
    for (String value :
        List.of("2026-03-01 25:00:00", "2026-03-01T23:59:60", "2026-03-01 24:30:00")) {
      assertThatThrownBy(() -> service.convertValue(value, "DATE"))
          .as(value)
          .hasMessageContaining("날짜 형식이 아닙니다: " + value);
    }
  }
}
