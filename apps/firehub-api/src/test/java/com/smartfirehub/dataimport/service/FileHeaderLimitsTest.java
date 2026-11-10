package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WD-612: 임포트 파일 헤더 크기 상한(열 수·열 이름 길이·CSV 헤더 줄 길이) 단위 테스트.
 *
 * <p>미리보기 응답이 헤더를 여러 번 되풀이해 열 수·열 이름 길이에 비례해 커지던 문제(60MB 한 줄 → 120MB 응답)를 막는다. 모든 헤더 읽기 지점(헤더·샘플·행
 * 수·전체·스트리밍)이 같은 400 예외로 거절하고, 상한 「딱 그 값」은 통과하는지 확인한다. 상한 상수는 바꿀 수 없으므로 상한 바로 위·아래 입력을 테스트 안에서 만든다.
 */
class FileHeaderLimitsTest {

  private static final int MAX_COLS = ImportFileLimits.MAX_HEADER_COLUMNS;
  private static final int MAX_NAME = ImportFileLimits.MAX_HEADER_NAME_LENGTH;

  private final FileParserService service = new FileParserService();

  @TempDir private Path tempDir;

  // ── helpers ──────────────────────────────────────────────────────────────

  /** c1,c2,…,cN 헤더 + 같은 너비의 데이터 한 줄 CSV. */
  private static String csvWithColumns(int columns) {
    StringBuilder header = new StringBuilder();
    StringBuilder data = new StringBuilder();
    for (int i = 1; i <= columns; i++) {
      if (i > 1) {
        header.append(',');
        data.append(',');
      }
      header.append('c').append(i);
      data.append(i);
    }
    return header + "\n" + data + "\n";
  }

  private Path write(String ext, byte[] data) throws Exception {
    Path file = tempDir.resolve("wd612-" + System.nanoTime() + "." + ext);
    Files.write(file, data);
    return file;
  }

  private Path writeCsv(String csv) throws Exception {
    return write("csv", csv.getBytes(StandardCharsets.UTF_8));
  }

  /** 헤더 한 줄 + 데이터 한 줄 XLSX. */
  private static byte[] xlsx(List<String> header, List<String> data) throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("Sheet1");
      Row h = sheet.createRow(0);
      for (int c = 0; c < header.size(); c++) h.createCell(c).setCellValue(header.get(c));
      Row d = sheet.createRow(1);
      for (int c = 0; c < data.size(); c++) d.createCell(c).setCellValue(data.get(c));
      wb.write(out);
      return out.toByteArray();
    }
  }

  private static List<String> names(int columns) {
    List<String> list = new ArrayList<>();
    for (int i = 1; i <= columns; i++) list.add("c" + i);
    return list;
  }

  /** Path 오버로드(실서비스 경로)의 헤더·샘플·행 수·전체·스트리밍이 모두 같은 메시지로 거절하는지 본다. */
  private void assertEveryPathRejects(Path file, String type, ParseOptions opts, String message) {
    List<List<Map<String, String>>> batches = new ArrayList<>();
    assertThatThrownBy(() -> service.parseHeaders(file, type, opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(message);
    assertThatThrownBy(() -> service.parseSampleRows(file, type, 5, opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(message);
    assertThatThrownBy(() -> service.countRows(file, type, opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(message);
    assertThatThrownBy(() -> service.parse(file, type, opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(message);
    assertThatThrownBy(() -> service.parseStreaming(file, type, opts, 10, batches::add))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(message);
    assertThat(batches).as("데이터 행을 하나도 넘기기 전에 거절").isEmpty();
  }

  private static String tooManyColumnsMessage(int columns) {
    return "파일의 열이 너무 많습니다: "
        + String.format(java.util.Locale.ROOT, "%,d", columns)
        + "개 (최대 1,600개). 가져올 열만 남긴 뒤 다시 올려 주세요.";
  }

  // ── 상수 자체 ────────────────────────────────────────────────────────────

  @Test
  void limits_matchPostgresColumnCapAndCoverWorstCaseQuotedHeader() {
    // 열 수 상한은 PostgreSQL 테이블 최대 컬럼 수와 같다
    assertThat(MAX_COLS).isEqualTo(1600);
    assertThat(MAX_NAME).isEqualTo(500);
    // 헤더 줄 상한: 모든 열을 따옴표로 감싸고 모든 글자를 "" 로 이스케이프한 최악의 정상 헤더보다 크다
    assertThat(ImportFileLimits.MAX_HEADER_LINE_CHARS)
        .isGreaterThanOrEqualTo((long) MAX_COLS * (2L * MAX_NAME + 3));
    // 400 핸들러(UnsupportedFileTypeException)를 타는 타입이어야 한다
    assertThat(new InvalidFileHeaderException("x"))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  // ── CSV: 열 수 ───────────────────────────────────────────────────────────

  @Test
  void csv_columnsOverLimit_everyEntryPointRejects() throws Exception {
    Path file = writeCsv(csvWithColumns(MAX_COLS + 1));
    assertEveryPathRejects(file, "csv", ParseOptions.defaults(), tooManyColumnsMessage(1601));

    // InputStream·byte[] 오버로드도 같은 규칙
    byte[] bytes = Files.readAllBytes(file);
    assertThatThrownBy(
            () ->
                service.parseHeaders(
                    new ByteArrayInputStream(bytes), "csv", ParseOptions.defaults()))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parseHeaders(bytes, "csv"))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parse(bytes, "csv"))
        .isInstanceOf(InvalidFileHeaderException.class);
  }

  @Test
  void csv_columnsAtLimit_accepted() throws Exception {
    Path file = writeCsv(csvWithColumns(MAX_COLS));
    assertThat(service.parseHeaders(file, "csv", ParseOptions.defaults())).hasSize(MAX_COLS);
    assertThat(service.parseSampleRows(file, "csv", 5, ParseOptions.defaults()))
        .singleElement()
        .satisfies(row -> assertThat(row).hasSize(MAX_COLS).containsEntry("c1600", "1600"));
  }

  @Test
  void csv_withoutHeader_widthOverLimit_rejected_atLimitAccepted() throws Exception {
    // 헤더 없는 파일도 column_N 이름이 응답에 되풀이되므로 열 수 상한이 같이 걸린다
    ParseOptions noHeader = new ParseOptions(",", "UTF-8", false, 0, null);
    Path over = writeCsv(csvWithColumns(MAX_COLS + 1));
    assertThatThrownBy(() -> service.parseHeaders(over, "csv", noHeader))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(tooManyColumnsMessage(1601));
    assertThatThrownBy(() -> service.parseStreaming(over, "csv", noHeader, 10, b -> {}))
        .isExactlyInstanceOf(InvalidFileHeaderException.class);

    Path atLimit = writeCsv(csvWithColumns(MAX_COLS));
    assertThat(service.parseHeaders(atLimit, "csv", noHeader))
        .hasSize(MAX_COLS)
        .endsWith("column_1600");
  }

  // ── CSV: 열 이름 길이 ────────────────────────────────────────────────────

  @Test
  void csv_headerNameOverLimit_everyEntryPointRejects() throws Exception {
    String longName = "가".repeat(MAX_NAME + 1);
    Path file = writeCsv("id," + longName + "\n1,x\n");
    assertEveryPathRejects(
        file,
        "csv",
        ParseOptions.defaults(),
        "열 이름이 너무 깁니다: 2번째 열(501자) (최대 500자). 열 이름을 줄인 뒤 다시 올려 주세요.");
  }

  @Test
  void csv_headerNameAtLimit_accepted() throws Exception {
    String name = "가".repeat(MAX_NAME);
    Path file = writeCsv("id," + name + "\n1,x\n");
    assertThat(service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .containsExactly("id", name);
  }

  @Test
  void csv_manyLongHeaderNames_messageListsFirstFiveOnly() throws Exception {
    // 메시지에 열 이름 자체는 넣지 않고, 앞 5개 열 번호·글자 수 + 「외 N개」로 줄인다
    String longName = "x".repeat(MAX_NAME + 1);
    StringBuilder header = new StringBuilder();
    for (int i = 0; i < 7; i++) header.append(i == 0 ? "" : ",").append(longName).append(i);
    Path file = writeCsv(header + "\n");
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(
            "열 이름이 너무 깁니다: 1번째 열(502자), 2번째 열(502자), 3번째 열(502자), 4번째 열(502자),"
                + " 5번째 열(502자) 외 2개 (최대 500자). 열 이름을 줄인 뒤 다시 올려 주세요.")
        .message()
        .doesNotContain(longName);
  }

  // ── CSV: 줄바꿈 없는 거대한 헤더 줄 — 읽는 도중에 끊는다 ──────────────────

  @Test
  void csv_hugeSingleLineWithoutNewline_cutWhileReading() throws Exception {
    // 헤더 줄 상한(약 160만 자)의 2배를 넘는, 줄바꿈 없는 한 셀짜리 파일(이슈 재현의 축소판)
    int size = (int) (ImportFileLimits.MAX_HEADER_LINE_CHARS * 2);
    Path file = writeCsv("a".repeat(size));
    assertEveryPathRejects(
        file, "csv", ParseOptions.defaults(), FileHeaderValidator.HEADER_LINE_TOO_LONG);
    assertThat(FileHeaderValidator.HEADER_LINE_TOO_LONG)
        .contains("1,600개")
        .contains("500자")
        .contains("따옴표");
  }

  @Test
  void csv_hugeHeaderWithManySmallColumns_cutWhileReading() throws Exception {
    // 100만 열(이슈 재현)과 같은 모양을 작게: 짧은 이름 열이 아주 많아 헤더 줄이 상한을 넘는다
    StringBuilder sb = new StringBuilder();
    int columns = 400_000; // "c123456," ≈ 8자 × 40만 = 약 320만 자
    for (int i = 0; i < columns; i++) sb.append(i == 0 ? "" : ",").append('c').append(i);
    Path file = writeCsv(sb.append("\n1\n").toString());
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(FileHeaderValidator.HEADER_LINE_TOO_LONG);
  }

  @Test
  void csv_unclosedQuoteInHeader_onLargeFile_rejectedAsHeaderTooLong() throws Exception {
    // 헤더의 닫히지 않은 따옴표는 뒤 줄 전부를 한 셀로 이어 읽는다 — 큰 파일이면 줄 길이 감시가 끊는다
    StringBuilder sb = new StringBuilder("\"id,name\n");
    while (sb.length() <= ImportFileLimits.MAX_HEADER_LINE_CHARS + 100_000) {
      sb.append("1234567890,abcdefghij\n");
    }
    Path file = writeCsv(sb.toString());
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(FileHeaderValidator.HEADER_LINE_TOO_LONG);
  }

  @Test
  void csv_unclosedQuoteThenManyShortLines_rejectedQuickly() throws Exception {
    // 따옴표 하나 뒤에 빈 줄만 100만 개(1MB) — 글자 수 상한 안이지만 opencsv 의 줄 이어 붙이기 비용(줄 수 × 글자 수)이 커지는 모양.
    // 물리 줄 수 상한으로 바로 끊겨야 한다(감시가 없으면 이 파일 하나로 스레드를 아주 오래 붙잡는다).
    Path file = writeCsv("\"" + "\n".repeat(1_000_000));
    long started = System.nanoTime();
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(FileHeaderValidator.HEADER_LINE_TOO_LONG);
    assertThat((System.nanoTime() - started) / 1_000_000).as("ms").isLessThan(5_000);
  }

  @Test
  void csv_quotedMultilineHeader_oneBreakPerColumnAtColumnLimit_accepted() throws Exception {
    // 열 이름마다 줄바꿈(엑셀 Alt+Enter)이 하나씩 있는 1,600열 헤더 — 물리 줄 1,601개(줄 수 상한과 같다)도 받는다
    StringBuilder header = new StringBuilder();
    for (int i = 1; i <= MAX_COLS; i++)
      header.append(i == 1 ? "" : ",").append("\"c\n").append(i).append('"');
    Path file = writeCsv(header + "\n1\n");
    List<String> headers = service.parseHeaders(file, "csv", ParseOptions.defaults());
    assertThat(headers).hasSize(MAX_COLS).startsWith("c\n1").endsWith("c\n1600");
    assertThat(ImportFileLimits.MAX_HEADER_PHYSICAL_LINES).isEqualTo(MAX_COLS + 1);
  }

  @Test
  void csv_longDataCell_notLimitedByHeaderGuard() throws Exception {
    // 감시는 헤더 줄에만 걸린다 — 긴 본문·GeoJSON 같은 데이터 셀(헤더 줄 상한보다 큰 값)은 그대로 읽는다
    String bigValue = "v".repeat((int) ImportFileLimits.MAX_HEADER_LINE_CHARS + 50_000);
    Path file = writeCsv("id,body\n1," + bigValue + "\n2,short\n");
    List<Map<String, String>> rows = service.parse(file, "csv", ParseOptions.defaults());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("body")).hasSize(bigValue.length());
    int[] streamed = {0};
    service.parseStreaming(
        file, "csv", ParseOptions.defaults(), 10, batch -> streamed[0] += batch.size());
    assertThat(streamed[0]).isEqualTo(2);
  }

  @Test
  void csv_skipRowsThenHeader_guardAppliesToHeaderOnly() throws Exception {
    // skipRows 로 건너뛰는 앞 줄은 감시 대상이 아니고, 그 뒤 헤더 줄만 상한을 본다
    ParseOptions skipOne = new ParseOptions(",", "UTF-8", true, 1, null);
    Path ok = writeCsv("title line\nid,name\n1,a\n");
    assertThat(service.parseHeaders(ok, "csv", skipOne)).containsExactly("id", "name");

    Path tooLong = writeCsv("title line\nid," + "n".repeat(MAX_NAME + 1) + "\n1,a\n");
    assertThatThrownBy(() -> service.parseHeaders(tooLong, "csv", skipOne))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessageContaining("2번째 열(501자)");
  }

  // ── XLSX ────────────────────────────────────────────────────────────────

  @Test
  void xlsx_columnsOverLimit_everyEntryPointRejects() throws Exception {
    Path file = write("xlsx", xlsx(names(MAX_COLS + 1), List.of("1")));
    assertEveryPathRejects(file, "xlsx", ParseOptions.defaults(), tooManyColumnsMessage(1601));
  }

  @Test
  void xlsx_columnsAtLimit_accepted() throws Exception {
    Path file = write("xlsx", xlsx(names(MAX_COLS), List.of("1")));
    assertThat(service.parseHeaders(file, "xlsx", ParseOptions.defaults())).hasSize(MAX_COLS);
  }

  @Test
  void xlsx_headerNameOverLimit_everyEntryPointRejects_atLimitAccepted() throws Exception {
    Path over = write("xlsx", xlsx(List.of("id", "n".repeat(MAX_NAME + 1)), List.of("1", "a")));
    assertEveryPathRejects(
        over,
        "xlsx",
        ParseOptions.defaults(),
        "열 이름이 너무 깁니다: 2번째 열(501자) (최대 500자). 열 이름을 줄인 뒤 다시 올려 주세요.");

    Path atLimit = write("xlsx", xlsx(List.of("id", "n".repeat(MAX_NAME)), List.of("1", "a")));
    assertThat(service.parseHeaders(atLimit, "xlsx", ParseOptions.defaults())).hasSize(2);
  }

  // ── 미리보기 샘플 값 표시 자르기 ───────────────────────────────────────────

  @Test
  void truncateForPreview_cutsLongValues_keepsShortAndNull() {
    int max = ImportFileLimits.MAX_PREVIEW_VALUE_LENGTH;
    assertThat(DataImportService.truncateForPreview(null)).isNull();
    assertThat(DataImportService.truncateForPreview("a".repeat(max))).hasSize(max);
    assertThat(DataImportService.truncateForPreview("a".repeat(max + 1)))
        .isEqualTo("a".repeat(max) + "…");
    // 자르는 자리가 서로게이트 쌍 한가운데면 한 글자 앞에서 잘라 깨진 문자를 만들지 않는다
    String emoji = "😀"; // 😀
    String value = "a".repeat(max - 1) + emoji + "tail";
    assertThat(DataImportService.truncateForPreview(value)).isEqualTo("a".repeat(max - 1) + "…");
  }
}
