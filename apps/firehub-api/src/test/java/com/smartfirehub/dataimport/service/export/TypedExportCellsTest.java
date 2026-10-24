package com.smartfirehub.dataimport.service.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.service.DataValidationService;
import com.smartfirehub.dataimport.service.FileParserService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 내보내기 셀이 값 타입을 살려 나가는지 검증한다(WD-159).
 *
 * <p>수정 전에는 모든 값을 {@code toString()} 으로 써서 Excel 은 모두 문자열 셀(합계·정렬·날짜 필터 불가), GeoJSON 은 숫자·참거짓도
 * 문자열이고 null 은 {@code ""}, CSV 는 {@code 1234567.891000}·{@code …00.0} 모양이었다.
 */
class TypedExportCellsTest {

  /** 화재통계 한 행 — 정수·소수·날짜·시각·참거짓·문자열·null. */
  private static final Object[] ROW = {
    3L,
    new BigDecimal("1234567.891000"),
    Date.valueOf("2026-01-31"),
    Timestamp.valueOf("2026-01-31 23:30:00"),
    true,
    "서울",
    null
  };

  private static final List<String> HEADER =
      List.of("cnt", "amount", "occurred_on", "occurred_at", "extinguished", "region", "memo");

  private static byte[] excel(Object[]... rows) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ExcelExportWriter w = new ExcelExportWriter(bos)) {
      w.writeHeader(HEADER);
      for (Object[] r : rows) w.writeRow(r);
    }
    return bos.toByteArray();
  }

  @Test
  void text_matchesScreenShape() {
    assertThat(ExportValues.text(new BigDecimal("1234567.891000"))).isEqualTo("1234567.891");
    assertThat(ExportValues.text(new BigDecimal("0.100000"))).isEqualTo("0.1");
    // stripTrailingZeros 만 쓰면 1E+3 이 된다 — 평문이어야 한다
    assertThat(ExportValues.text(new BigDecimal("1000.000000"))).isEqualTo("1000");
    assertThat(ExportValues.text(new BigDecimal("0E-6"))).isEqualTo("0");
    assertThat(ExportValues.text(Timestamp.valueOf("2026-01-31 23:30:00")))
        .isEqualTo("2026-01-31 23:30:00");
    assertThat(ExportValues.text(Timestamp.valueOf("2024-01-01 10:00:00.123")))
        .isEqualTo("2024-01-01 10:00:00.123");
    assertThat(ExportValues.text(Timestamp.valueOf("2024-01-01 10:00:00.000456")))
        .isEqualTo("2024-01-01 10:00:00.000456");
    assertThat(ExportValues.text(LocalDateTime.of(2026, 1, 2, 3, 4)))
        .isEqualTo("2026-01-02 03:04:00");
    assertThat(ExportValues.text(Date.valueOf("2026-01-31"))).isEqualTo("2026-01-31");
    assertThat(ExportValues.text(true)).isEqualTo("true");
    assertThat(ExportValues.text(null)).isEmpty();
  }

  @Test
  void excel_writesNumberBooleanAndDateCells() throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel(ROW)))) {
      Row row = wb.getSheetAt(0).getRow(1);
      assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(3.0);
      assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(1234567.891);

      Cell date = row.getCell(2);
      assertThat(date.getCellType()).isEqualTo(CellType.NUMERIC);
      assertThat(DateUtil.isCellDateFormatted(date)).isTrue();
      assertThat(date.getLocalDateTimeCellValue().toLocalDate())
          .isEqualTo(LocalDate.of(2026, 1, 31));
      assertThat(date.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");

      Cell ts = row.getCell(3);
      assertThat(DateUtil.isCellDateFormatted(ts)).isTrue();
      assertThat(ts.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 30));
      assertThat(ts.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss");

      assertThat(row.getCell(4).getCellType()).isEqualTo(CellType.BOOLEAN);
      assertThat(row.getCell(4).getBooleanCellValue()).isTrue();
      assertThat(row.getCell(5).getStringCellValue()).isEqualTo("서울");
      assertThat(row.getCell(6).getCellType()).isEqualTo(CellType.BLANK);
    }
  }

  @Test
  void excel_unrepresentableValuesStayStringCells() throws Exception {
    Object[] row = {
      9_007_199_254_740_993L, // 16자리 — Excel 숫자는 15자리까지만 정확
      new BigDecimal("123456789012.123456"), // 18자리
      Date.valueOf("1899-12-31"), // Excel 날짜 체계 밖
      Timestamp.valueOf("2024-01-01 10:00:00.000456"), // 밀리초보다 잘게
      "10000-01-01", // #769 PG 원문
      "=1+1", // XLSX 문자열 셀은 수식이 아니다 — 따옴표 없이 그대로
      new BigDecimal("-5")
    };
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel(row)))) {
      Row r = wb.getSheetAt(0).getRow(1);
      assertThat(r.getCell(0).getStringCellValue()).isEqualTo("9007199254740993");
      assertThat(r.getCell(1).getStringCellValue()).isEqualTo("123456789012.123456");
      assertThat(r.getCell(2).getStringCellValue()).isEqualTo("1899-12-31");
      assertThat(r.getCell(3).getStringCellValue()).isEqualTo("2024-01-01 10:00:00.000456");
      assertThat(r.getCell(4).getStringCellValue()).isEqualTo("10000-01-01");
      assertThat(r.getCell(5).getStringCellValue()).isEqualTo("=1+1");
      assertThat(r.getCell(6).getNumericCellValue()).isEqualTo(-5.0);
    }
  }

  @Test
  void excel_millisecondTimestampUsesMillisFormat() throws Exception {
    Object[] row = {
      null, null, null, Timestamp.valueOf("2024-01-01 10:00:00.123"), null, null, null
    };
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel(row)))) {
      Cell ts = wb.getSheetAt(0).getRow(1).getCell(3);
      assertThat(ts.getLocalDateTimeCellValue())
          .isEqualTo(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_000_000));
      assertThat(ts.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm:ss.000");
    }
  }

  @Test
  void excel_reimportsWithSameValues() throws Exception {
    // 내보낸 Excel 을 고쳐 다시 임포트하는 흐름 — 타입 셀로 바뀌어도 같은 값으로 읽혀야 한다
    List<Map<String, String>> parsed = new FileParserService().parse(excel(ROW), "xlsx");
    assertThat(parsed).hasSize(1);
    Map<String, String> row = parsed.get(0);
    DataValidationService v = new DataValidationService();
    assertThat(v.convertValue(row.get("cnt"), "INTEGER")).isEqualTo(3L);
    assertThat((BigDecimal) v.convertValue(row.get("amount"), "DECIMAL"))
        .isEqualByComparingTo("1234567.891");
    // Excel 날짜 셀은 "2026-01-31T00:00" 으로 읽힌다 — DATE 컬럼이 받아야 한다
    assertThat(v.convertValue(row.get("occurred_on"), "DATE")).isEqualTo(LocalDate.of(2026, 1, 31));
    assertThat(v.convertValue(row.get("occurred_at"), "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 30));
    assertThat(v.convertValue(row.get("extinguished"), "BOOLEAN")).isEqualTo(true);
    assertThat(v.convertValue(row.get("region"), "TEXT")).isEqualTo("서울");
  }

  @Test
  void geojson_writesJsonTypesAndNull() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GeoJsonExportWriter w = new GeoJsonExportWriter(bos, "geom")) {
      w.writeHeader(List.of("cnt", "amount", "extinguished", "memo", "occurred_at", "big", "geom"));
      w.writeRow(
          new Object[] {
            3L,
            new BigDecimal("1000.000000"),
            false,
            null,
            Timestamp.valueOf("2026-01-31 23:30:00"),
            9_007_199_254_740_993L,
            "{\"type\":\"Point\",\"coordinates\":[127,37.5]}"
          });
    }
    String json = bos.toString(StandardCharsets.UTF_8);
    JsonNode props = new ObjectMapper().readTree(json).get("features").get(0).get("properties");
    assertThat(props.get("cnt").isIntegralNumber()).isTrue();
    assertThat(props.get("cnt").asLong()).isEqualTo(3L);
    assertThat(props.get("amount").isNumber()).isTrue();
    assertThat(json).contains("\"amount\":1000,"); // 지수 표기(1E+3) 아님
    assertThat(props.get("extinguished").isBoolean()).isTrue();
    assertThat(props.get("memo").isNull()).isTrue();
    assertThat(props.get("occurred_at").asText()).isEqualTo("2026-01-31 23:30:00");
    // JSON 소비자가 double 로 읽어 깎이는 정수는 화면 API 와 같이 문자열
    assertThat(props.get("big").isTextual()).isTrue();
    assertThat(props.get("big").asText()).isEqualTo("9007199254740993");
    assertThat(
            new ObjectMapper()
                .readTree(json)
                .get("features")
                .get(0)
                .get("geometry")
                .get("type")
                .asText())
        .isEqualTo("Point");
  }

  @Test
  void csv_writesScreenShapedText() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (CsvExportWriter w = new CsvExportWriter(bos)) {
      w.writeHeader(HEADER);
      w.writeRow(ROW);
    }
    String csv = bos.toString(StandardCharsets.UTF_8);
    assertThat(csv)
        .contains(
            "\"3\",\"1234567.891\",\"2026-01-31\",\"2026-01-31 23:30:00\",\"true\",\"서울\",\"\"");
  }

  // --- WD-248: Excel 셀 한도(32,767자) ---

  private static final int MAX = ExcelExportWriter.MAX_CELL_TEXT_LENGTH;

  /** 한도를 넘는 문자열 셀은 앞부분 + 잘림 표시로 정확히 한도 길이가 되고, 같은 행의 숫자·날짜·참거짓 셀은 그대로다. */
  @Test
  void excel_overLongTextIsTruncatedWithMarker_otherCellsUnchanged() throws Exception {
    String longText = "가".repeat(MAX + 1) + "끝";
    Object[] row = {
      3L,
      new BigDecimal("1234567.891000"),
      Date.valueOf("2026-01-31"),
      Timestamp.valueOf("2026-01-31 23:30:00"),
      true,
      longText,
      null
    };
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel(row)))) {
      Row r = wb.getSheetAt(0).getRow(1);
      String cell = r.getCell(5).getStringCellValue();
      assertThat(cell).hasSize(MAX).endsWith(ExcelExportWriter.TRUNCATED_SUFFIX);
      assertThat(cell)
          .isEqualTo(
              longText.substring(0, MAX - ExcelExportWriter.TRUNCATED_SUFFIX.length())
                  + ExcelExportWriter.TRUNCATED_SUFFIX);
      assertThat(cell).contains("전체 값은 CSV 로 내보내세요");
      assertThat(r.getCell(0).getNumericCellValue()).isEqualTo(3.0);
      assertThat(r.getCell(1).getNumericCellValue()).isEqualTo(1234567.891);
      assertThat(DateUtil.isCellDateFormatted(r.getCell(2))).isTrue();
      assertThat(r.getCell(3).getLocalDateTimeCellValue())
          .isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 30));
      assertThat(r.getCell(4).getBooleanCellValue()).isTrue();
      assertThat(r.getCell(6).getCellType()).isEqualTo(CellType.BLANK);
    }
  }

  /** 정확히 한도 길이인 값은 자르지도 표시를 붙이지도 않는다 — 한 글자 넘으면 그때 자른다. */
  @Test
  void excel_textAtExactLimitIsKeptAsIs() throws Exception {
    String exact = "a".repeat(MAX - 1) + "z";
    try (XSSFWorkbook wb =
        new XSSFWorkbook(
            new ByteArrayInputStream(
                excel(new Object[] {null, null, null, null, null, exact, null})))) {
      assertThat(wb.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).isEqualTo(exact);
    }
    assertThat(ExcelExportWriter.fitCellText(exact)).isSameAs(exact);
    assertThat(ExcelExportWriter.fitCellText(exact + "!")).hasSize(MAX);
    assertThat(ExcelExportWriter.fitCellText(null)).isNull();
  }

  /** 자르는 자리가 서로게이트 쌍(이모지) 한가운데면 그 앞에서 잘라 짝 잃은 상위 서로게이트를 남기지 않는다. */
  @Test
  void excel_truncationDoesNotSplitSurrogatePair() throws Exception {
    int cut = MAX - ExcelExportWriter.TRUNCATED_SUFFIX.length();
    // cut-1 자리에 상위 서로게이트, cut 자리에 하위 서로게이트가 오도록 맞춘다
    String text = "a".repeat(cut - 1) + "\uD83D\uDD25" + "b".repeat(100);
    assertThat(Character.isHighSurrogate(text.charAt(cut - 1))).isTrue();
    String fitted = ExcelExportWriter.fitCellText(text);
    assertThat(fitted).hasSize(MAX - 1).endsWith(ExcelExportWriter.TRUNCATED_SUFFIX);
    String head =
        fitted.substring(0, fitted.length() - ExcelExportWriter.TRUNCATED_SUFFIX.length());
    assertThat(head).isEqualTo("a".repeat(cut - 1));
    assertThat(Character.isHighSurrogate(head.charAt(head.length() - 1))).isFalse();
    // 이모지가 자르는 자리보다 앞에서 끝나면(쌍 전체가 안쪽) 그대로 남는다
    String whole = "a".repeat(cut - 2) + "\uD83D\uDD25" + "b".repeat(100);
    assertThat(ExcelExportWriter.fitCellText(whole))
        .hasSize(MAX)
        .startsWith("a".repeat(cut - 2) + "\uD83D\uDD25");
    // 실제 셀로 써도 열린다
    try (XSSFWorkbook wb =
        new XSSFWorkbook(
            new ByteArrayInputStream(
                excel(new Object[] {null, null, null, null, null, text, null})))) {
      assertThat(wb.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).isEqualTo(fitted);
    }
  }

  /** 행 쓰기 중 예외가 나면 close() 가 부분 통합 문서를 출력에 쓰지 않는다 — 동기 응답이 200 으로 커밋된 뒤 끊기지 않게. */
  @Test
  void excel_failedRowWritesNothingOnClose() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    Object boom =
        new Object() {
          @Override
          public String toString() {
            throw new IllegalStateException("셀 값 변환 실패");
          }
        };
    assertThatThrownBy(
            () -> {
              try (ExcelExportWriter w = new ExcelExportWriter(bos)) {
                w.writeHeader(HEADER);
                w.writeRow(ROW);
                w.writeRow(new Object[] {boom});
              }
            })
        .isInstanceOf(IllegalStateException.class);
    assertThat(bos.size()).isZero();
  }
}
