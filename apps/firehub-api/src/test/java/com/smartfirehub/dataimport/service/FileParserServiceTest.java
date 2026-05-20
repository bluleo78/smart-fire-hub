package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for FileParserService — no Spring context required. All test data is created
 * programmatically as byte arrays.
 */
class FileParserServiceTest {

  private FileParserService service;

  @BeforeEach
  void setUp() {
    service = new FileParserService();
  }

  // Helper: CSV string → InputStream
  private static InputStream toStream(String csv) {
    return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
  }

  // Helper: CSV string → byte[]
  private static byte[] toBytes(String csv) {
    return csv.getBytes(StandardCharsets.UTF_8);
  }

  // Helper: XLSX bytes with header + data rows
  private static byte[] buildXlsx() throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("test");
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("name");
      h.createCell(1).setCellValue("age");
      h.createCell(2).setCellValue("active");

      Row r1 = sheet.createRow(1);
      r1.createCell(0).setCellValue("Alice");
      r1.createCell(1).setCellValue(30); // numeric integer
      r1.createCell(2).setCellValue(true); // boolean

      Row r2 = sheet.createRow(2);
      r2.createCell(0).setCellValue("Bob");
      r2.createCell(1).setCellValue(25.5); // numeric decimal
      r2.createCell(2).setCellValue(false);

      wb.write(out);
      return out.toByteArray();
    }
  }

  // Helper: XLSX with a date-formatted cell
  private static byte[] buildXlsxWithDate() throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("dates");
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("name");
      h.createCell(1).setCellValue("when");

      CreationHelper createHelper = wb.getCreationHelper();
      CellStyle dateStyle = wb.createCellStyle();
      dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd"));

      Row r1 = sheet.createRow(1);
      r1.createCell(0).setCellValue("Alice");
      Cell dateCell = r1.createCell(1);
      dateCell.setCellValue(new Date(0L)); // epoch
      dateCell.setCellStyle(dateStyle);

      wb.write(out);
      return out.toByteArray();
    }
  }

  // Helper: empty XLSX (no rows at all)
  private static byte[] buildEmptyXlsx() throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      wb.createSheet("empty");
      wb.write(out);
      return out.toByteArray();
    }
  }

  // -----------------------------------------------------------------------
  // parseCSV (InputStream)
  // -----------------------------------------------------------------------

  @Test
  void parseCSV_standardCsvWithHeader_returnsCorrectRows() throws Exception {
    String csv = "name,age,city\nAlice,30,Seoul\nBob,25,Busan";

    List<Map<String, String>> rows = service.parseCSV(toStream(csv));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("name", "Alice")
        .containsEntry("age", "30")
        .containsEntry("city", "Seoul");
    assertThat(rows.get(1))
        .containsEntry("name", "Bob")
        .containsEntry("age", "25")
        .containsEntry("city", "Busan");
  }

  @Test
  void parseCSV_tabDelimited_parsesCorrectly() throws Exception {
    String tsv = "name\tage\tcity\nAlice\t30\tSeoul\nBob\t25\tBusan";
    ParseOptions opts = new ParseOptions("\t", "UTF-8", true, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(tsv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice").containsEntry("age", "30");
    assertThat(rows.get(1)).containsEntry("name", "Bob").containsEntry("city", "Busan");
  }

  @Test
  void parseCSV_semicolonDelimited_parsesCorrectly() throws Exception {
    String csv = "name;age;city\nAlice;30;Seoul\nBob;25;Busan";
    ParseOptions opts = new ParseOptions(";", "UTF-8", true, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(csv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice").containsEntry("city", "Seoul");
  }

  @Test
  void parseCSV_quotedValuesWithCommasInside_handlesCorrectly() throws Exception {
    String csv = "name,address\nAlice,\"Seoul, Gangnam\"\nBob,\"Busan, Haeundae\"";

    List<Map<String, String>> rows = service.parseCSV(toStream(csv));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("address", "Seoul, Gangnam");
    assertThat(rows.get(1)).containsEntry("address", "Busan, Haeundae");
  }

  @Test
  void parseCSV_emptyInputStream_returnsEmptyList() throws Exception {
    List<Map<String, String>> rows = service.parseCSV(toStream(""));

    assertThat(rows).isEmpty();
  }

  @Test
  void parseCSV_headerOnlyNoDataRows_returnsEmptyList() throws Exception {
    String csv = "name,age,city";

    List<Map<String, String>> rows = service.parseCSV(toStream(csv));

    assertThat(rows).isEmpty();
  }

  @Test
  void parseCSV_noHeaderMode_autoGeneratesColumnNames() throws Exception {
    String csv = "Alice,30,Seoul\nBob,25,Busan";
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(csv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsKey("column_1").containsKey("column_2").containsKey("column_3");
    assertThat(rows.get(0))
        .containsEntry("column_1", "Alice")
        .containsEntry("column_2", "30")
        .containsEntry("column_3", "Seoul");
  }

  @Test
  void parseCSV_withSkipRows_skipsBeforeHeader() throws Exception {
    // 앞의 2개 비데이터 행(메타/공백)을 건너뛰고 헤더부터 파싱되는지 검증
    String csv = "# report export\n# generated\nname,age\nAlice,30\nBob,25";
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 2);

    List<Map<String, String>> rows = service.parseCSV(toStream(csv), opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice");
    assertThat(rows.get(1)).containsEntry("name", "Bob");
  }

  @Test
  void parseCSV_autoEncodingFallsBackToUtf8Stream() throws Exception {
    // InputStream 기반 파서는 AUTO를 UTF-8로 fallback — 한글 데이터 정상 파싱되는지 확인
    String csv = "이름,도시\n홍길동,서울";
    ParseOptions opts = new ParseOptions(",", "AUTO", true, 0);

    List<Map<String, String>> rows = service.parseCSV(toStream(csv), opts);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("이름", "홍길동").containsEntry("도시", "서울");
  }

  // -----------------------------------------------------------------------
  // parse(byte[]) — CSV / XLSX / unsupported
  // -----------------------------------------------------------------------

  @Test
  void parse_csvFileType_routesToCsvParser() throws Exception {
    String csv = "id,value\n1,foo\n2,bar";
    List<Map<String, String>> rows = service.parse(toBytes(csv), "csv");

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("id", "1").containsEntry("value", "foo");
  }

  @Test
  void parse_xlsxFileType_routesToExcelParser() throws Exception {
    byte[] data = buildXlsx();

    List<Map<String, String>> rows = service.parse(data, "xlsx");

    assertThat(rows).hasSize(2);
    // POI XSSFSheetXMLHandler는 불리언 셀을 "TRUE"/"FALSE"(대문자)로 직렬화한다.
    // 문자열 셀에 "TRUE"/"FALSE"를 입력한 경우 값 손상을 방지하기 위해 정규화를 제거하였다.
    assertThat(rows.get(0))
        .containsEntry("name", "Alice")
        .containsEntry("age", "30")
        .containsEntry("active", "TRUE");
    assertThat(rows.get(1))
        .containsEntry("name", "Bob")
        .containsEntry("age", "25.5")
        .containsEntry("active", "FALSE");
  }

  @Test
  void parse_xlsFileTypeLowercase_routesToExcelParser() throws Exception {
    // xlsx와 xls 모두 Excel parser 로 라우팅되는지 확인 (대소문자 구분 없음)
    byte[] data = buildXlsx();

    List<Map<String, String>> rows = service.parse(data, "XLSX");

    assertThat(rows).hasSize(2);
  }

  @Test
  void parse_unsupportedFileType_throwsUnsupportedFileTypeException() {
    byte[] data = "dummy content".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.parse(data, "pdf"))
        .isInstanceOf(UnsupportedFileTypeException.class)
        .hasMessageContaining("Unsupported file type");
  }

  @Test
  void parse_xlsxWithDateFormattedCell_convertsToIsoString() throws Exception {
    byte[] data = buildXlsxWithDate();

    List<Map<String, String>> rows = service.parse(data, "xlsx");

    assertThat(rows).hasSize(1);
    // epoch millis = 0 → 시스템 타임존에 따라 1969-12-31 또는 1970-01-01
    String dateStr = rows.get(0).get("when");
    assertThat(dateStr).matches("\\d{4}-\\d{2}-\\d{2}T.*");
  }

  @Test
  void parse_emptyXlsx_returnsEmptyList() throws Exception {
    byte[] data = buildEmptyXlsx();

    List<Map<String, String>> rows = service.parse(data, "xlsx");

    assertThat(rows).isEmpty();
  }

  // -----------------------------------------------------------------------
  // parseHeaders(byte[])
  // -----------------------------------------------------------------------

  @Test
  void parseHeaders_csv_returnsHeaderNames() throws Exception {
    String csv = "name,age,city\nAlice,30,Seoul";

    List<String> headers = service.parseHeaders(toBytes(csv), "csv");

    assertThat(headers).containsExactly("name", "age", "city");
  }

  @Test
  void parseHeaders_csvNoHeaderMode_returnsGeneratedColumnNames() throws Exception {
    String csv = "Alice,30,Seoul";
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);

    List<String> headers = service.parseHeaders(toBytes(csv), "csv", opts);

    assertThat(headers).containsExactly("column_1", "column_2", "column_3");
  }

  @Test
  void parseHeaders_csvEmpty_returnsEmptyList() throws Exception {
    List<String> headers = service.parseHeaders(toBytes(""), "csv");

    assertThat(headers).isEmpty();
  }

  @Test
  void parseHeaders_xlsx_returnsHeaderNames() throws Exception {
    byte[] data = buildXlsx();

    List<String> headers = service.parseHeaders(data, "xlsx");

    assertThat(headers).containsExactly("name", "age", "active");
  }

  @Test
  void parseHeaders_emptyXlsx_returnsEmptyList() throws Exception {
    byte[] data = buildEmptyXlsx();

    List<String> headers = service.parseHeaders(data, "xlsx");

    assertThat(headers).isEmpty();
  }

  @Test
  void parseHeaders_unsupportedFileType_throws() {
    assertThatThrownBy(() -> service.parseHeaders(toBytes("x"), "doc"))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  @Test
  void parseHeaders_csvSkipRowsExceedsFile_returnsEmpty() throws Exception {
    // 파일 전체 행 수보다 큰 skipRows 는 빈 헤더 리스트를 반환해야 함
    String csv = "name,age\nAlice,30";
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 99);

    List<String> headers = service.parseHeaders(toBytes(csv), "csv", opts);

    assertThat(headers).isEmpty();
  }

  // -----------------------------------------------------------------------
  // parseSampleRows(byte[])
  // -----------------------------------------------------------------------

  @Test
  void parseSampleRows_csv_limitsToRequestedMaxRows() throws Exception {
    String csv = "id,name\n1,a\n2,b\n3,c\n4,d\n5,e";

    List<Map<String, String>> rows = service.parseSampleRows(toBytes(csv), "csv", 3);

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("id", "1");
    assertThat(rows.get(2)).containsEntry("id", "3");
  }

  @Test
  void parseSampleRows_csvNoHeader_generatesColumnNames() throws Exception {
    String csv = "1,a\n2,b\n3,c";
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);

    List<Map<String, String>> rows = service.parseSampleRows(toBytes(csv), "csv", 2, opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("column_1", "1").containsEntry("column_2", "a");
  }

  @Test
  void parseSampleRows_csvEmpty_returnsEmpty() throws Exception {
    List<Map<String, String>> rows = service.parseSampleRows(toBytes(""), "csv", 10);

    assertThat(rows).isEmpty();
  }

  @Test
  void parseSampleRows_xlsx_limitsToRequestedMaxRows() throws Exception {
    byte[] data = buildXlsx();

    List<Map<String, String>> rows = service.parseSampleRows(data, "xlsx", 1);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("name", "Alice");
  }

  @Test
  void parseSampleRows_unsupportedFileType_throws() {
    assertThatThrownBy(() -> service.parseSampleRows(toBytes("x"), "rtf", 5))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  @Test
  void parseSampleRows_csvSkipRowsExceedsFile_returnsEmpty() throws Exception {
    String csv = "name\nAlice";
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 10);

    List<Map<String, String>> rows = service.parseSampleRows(toBytes(csv), "csv", 3, opts);

    assertThat(rows).isEmpty();
  }

  // -----------------------------------------------------------------------
  // countRows(byte[])
  // -----------------------------------------------------------------------

  @Test
  void countRows_csvWith3DataRows_returns3() throws Exception {
    String csv = "name,age\nAlice,30\nBob,25\nCharlie,35";

    int count = service.countRows(toBytes(csv), "csv");

    assertThat(count).isEqualTo(3);
  }

  @Test
  void countRows_csvNoHeader_countsAllRows() throws Exception {
    String csv = "Alice,30\nBob,25\nCharlie,35";
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);

    int count = service.countRows(toBytes(csv), "csv", opts);

    assertThat(count).isEqualTo(3);
  }

  @Test
  void countRows_csvHeaderOnly_returns0() throws Exception {
    String csv = "name,age";

    int count = service.countRows(toBytes(csv), "csv");

    assertThat(count).isEqualTo(0);
  }

  @Test
  void countRows_csvEmpty_returns0() throws Exception {
    int count = service.countRows(toBytes(""), "csv");

    assertThat(count).isEqualTo(0);
  }

  @Test
  void countRows_csvSkipRowsExceedsFile_returns0() throws Exception {
    String csv = "name,age\nAlice,30";
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 99);

    int count = service.countRows(toBytes(csv), "csv", opts);

    assertThat(count).isEqualTo(0);
  }

  @Test
  void countRows_xlsx_returnsDataRowCount() throws Exception {
    byte[] data = buildXlsx();

    int count = service.countRows(data, "xlsx");

    // lastRowNum=2, returns max(0, 2) = 2 (excludes header implicitly via getLastRowNum)
    assertThat(count).isEqualTo(2);
  }

  @Test
  void countRows_emptyXlsx_returns0() throws Exception {
    byte[] data = buildEmptyXlsx();

    int count = service.countRows(data, "xlsx");

    assertThat(count).isEqualTo(0);
  }

  @Test
  void countRows_unsupportedFileType_throws() {
    assertThatThrownBy(() -> service.countRows(toBytes("x"), "json"))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  // -----------------------------------------------------------------------
  // InputStream 오버로드 — parseHeaders / parseSampleRows / countRows / parse
  // OOM 방지(#145): previewImport·validateImport 경로에서 사용하는 스트리밍 메서드 검증
  // -----------------------------------------------------------------------

  @Test
  void parseHeaders_csvViaInputStream_returnsHeaders() throws Exception {
    String csv = "name,age,city\nAlice,30,Seoul";
    ParseOptions opts = ParseOptions.defaults();

    List<String> headers = service.parseHeaders(toStream(csv), "csv", opts);

    assertThat(headers).containsExactly("name", "age", "city");
  }

  @Test
  void parseHeaders_xlsxViaInputStream_returnsHeaders() throws Exception {
    byte[] data = buildXlsx();
    ParseOptions opts = ParseOptions.defaults();

    List<String> headers = service.parseHeaders(new ByteArrayInputStream(data), "xlsx", opts);

    assertThat(headers).containsExactly("name", "age", "active");
  }

  @Test
  void parseHeaders_unsupportedTypeViaInputStream_throws() {
    ParseOptions opts = ParseOptions.defaults();
    assertThatThrownBy(() -> service.parseHeaders(toStream("x"), "pdf", opts))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  @Test
  void parseSampleRows_csvViaInputStream_limitsRows() throws Exception {
    String csv = "id,name\n1,a\n2,b\n3,c\n4,d\n5,e";
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> rows = service.parseSampleRows(toStream(csv), "csv", 3, opts);

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("id", "1");
  }

  @Test
  void parseSampleRows_xlsxViaInputStream_limitsRows() throws Exception {
    byte[] data = buildXlsx();
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> rows =
        service.parseSampleRows(new ByteArrayInputStream(data), "xlsx", 1, opts);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("name", "Alice");
  }

  @Test
  void countRows_csvViaInputStream_returnsDataRowCount() throws Exception {
    String csv = "name,age\nAlice,30\nBob,25\nCharlie,35";
    ParseOptions opts = ParseOptions.defaults();

    int count = service.countRows(toStream(csv), "csv", opts);

    assertThat(count).isEqualTo(3);
  }

  @Test
  void countRows_xlsxViaInputStream_returnsDataRowCount() throws Exception {
    byte[] data = buildXlsx();
    ParseOptions opts = ParseOptions.defaults();

    int count = service.countRows(new ByteArrayInputStream(data), "xlsx", opts);

    assertThat(count).isEqualTo(2);
  }

  @Test
  void parse_csvViaInputStream_returnsAllRows() throws Exception {
    String csv = "id,value\n1,foo\n2,bar";
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> rows = service.parse(toStream(csv), "csv", opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("id", "1").containsEntry("value", "foo");
    assertThat(rows.get(1)).containsEntry("id", "2").containsEntry("value", "bar");
  }

  @Test
  void parse_xlsxViaInputStream_returnsAllRows() throws Exception {
    byte[] data = buildXlsx();
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> rows = service.parse(new ByteArrayInputStream(data), "xlsx", opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice");
  }

  @Test
  void parse_csvAutoEncodingViaInputStream_detectsAndParses() throws Exception {
    // AUTO 인코딩 + InputStream: mark/reset으로 인코딩 감지 후 정상 파싱되는지 확인
    String csv = "name,age\nAlice,30\nBob,25";
    ParseOptions opts = new ParseOptions(",", "AUTO", true, 0);

    List<Map<String, String>> rows = service.parse(toStream(csv), "csv", opts);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice").containsEntry("age", "30");
  }

  // -----------------------------------------------------------------------
  // parseExcel(InputStream) — direct call
  // -----------------------------------------------------------------------

  @Test
  void parseExcel_withHeaderAndRows_returnsDataRows() throws Exception {
    byte[] data = buildXlsx();

    List<Map<String, String>> rows = service.parseExcel(new ByteArrayInputStream(data));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("name", "Alice")
        .containsEntry("age", "30")
        .containsEntry("active", "TRUE");
  }

  @Test
  void parseExcel_emptyWorkbook_returnsEmpty() throws Exception {
    byte[] data = buildEmptyXlsx();

    List<Map<String, String>> rows = service.parseExcel(new ByteArrayInputStream(data));

    assertThat(rows).isEmpty();
  }

  // -----------------------------------------------------------------------
  // byte[] encoding detection path (AUTO)
  // -----------------------------------------------------------------------

  @Test
  void parse_csvWithUtf8Bom_detectsUtf8() throws Exception {
    byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] body = "name,age\nAlice,30".getBytes(StandardCharsets.UTF_8);
    byte[] data = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, data, 0, bom.length);
    System.arraycopy(body, 0, data, bom.length, body.length);

    List<Map<String, String>> rows = service.parse(data, "csv");

    assertThat(rows).hasSize(1);
    // BOM is attached to the first header key
    assertThat(rows.get(0).values()).contains("Alice", "30");
  }

  @Test
  void parse_csvEucKrEncoded_detectedAsEucKr() throws Exception {
    // 한글 데이터는 UTF-8 이 아니면 EUC-KR 로 판정되어야 함
    Charset eucKr = Charset.forName("EUC-KR");
    byte[] data = "이름,도시\n홍길동,서울".getBytes(eucKr);

    List<Map<String, String>> rows = service.parse(data, "csv");

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("이름", "홍길동").containsEntry("도시", "서울");
  }

  // -----------------------------------------------------------------------
  // 인코딩 자동 감지 회귀 — #263
  // -----------------------------------------------------------------------

  /**
   * 회귀 #263: BOM 없는 UTF-8 파일이지만 peek 버퍼(64KB) 끝에 한글 멀티바이트 시퀀스가
   * 잘려 있는 경우, 이전 구현은 MALFORMED으로 판정해 EUC-KR로 폴백했다.
   * CharsetDecoder 기반 구현은 truncation을 UNDERFLOW로 처리해 UTF-8로 올바로 감지한다.
   */
  @Test
  void detectEncoding_utf8WithMultibyteTruncatedAtPeekBoundary_detectedAsUtf8() throws Exception {
    // 8192바이트 경계에 한글 한 글자(3바이트)가 걸치도록 페이로드 구성.
    // peek 버퍼가 64KB로 커진 후에도 어떤 크기든 비슷한 경계가 존재할 수 있다.
    StringBuilder padding = new StringBuilder();
    while (padding.length() < 8190) padding.append('a');
    String csv = "재난번호,신고접수일시,종별\n" + padding + "재난재난,20251101000000,화재";
    byte[] full = csv.getBytes(StandardCharsets.UTF_8);
    byte[] peek = new byte[Math.min(full.length, 8191)]; // 한글 3바이트 중 첫 1바이트만 포함
    System.arraycopy(full, 0, peek, 0, peek.length);

    // detectEncoding이 truncated 한글을 보고도 UTF-8 판정해야 한다
    assertThat(ParseOptions.detectEncoding(peek)).isEqualTo("UTF-8");

    // 그리고 실제 파싱도 한글이 깨지지 않아야 한다
    List<Map<String, String>> rows = service.parse(full, "csv");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsKey("재난번호").containsEntry("종별", "화재");
  }

  @Test
  void detectEncoding_emptyData_returnsUtf8() {
    assertThat(ParseOptions.detectEncoding(new byte[0])).isEqualTo("UTF-8");
    assertThat(ParseOptions.detectEncoding(null)).isEqualTo("UTF-8");
  }

  @Test
  void detectEncoding_asciiOnly_returnsUtf8() {
    byte[] data = "name,age\nAlice,30".getBytes(StandardCharsets.US_ASCII);
    assertThat(ParseOptions.detectEncoding(data)).isEqualTo("UTF-8");
  }

  @Test
  void detectEncoding_cp949Korean_returnsMs949Fallback() {
    // MS949(=CP949)는 EUC-KR의 superset. AUTO 감지 시 폴백 라벨이 MS949가 되어야 한다.
    byte[] data = "재난번호,신고접수일시\nA001,20251101".getBytes(Charset.forName("MS949"));
    assertThat(ParseOptions.detectEncoding(data)).isEqualTo("MS949");
  }

  @Test
  void parse_csvCp949Encoded_decodedCorrectlyViaMs949Fallback() throws Exception {
    // CP949(MS949)로 인코딩된 한국 행정안전부 스타일 CSV가 AUTO 감지로 정상 파싱되는지
    byte[] data = "이름,도시\n홍길동,서울".getBytes(Charset.forName("MS949"));
    List<Map<String, String>> rows = service.parse(data, "csv");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("이름", "홍길동").containsEntry("도시", "서울");
  }

  @Test
  void parse_csvUtf16LeBom_detectedAsUtf16Le() throws Exception {
    byte[] bom = {(byte) 0xFF, (byte) 0xFE};
    byte[] body = "name,age\nAlice,30".getBytes(Charset.forName("UTF-16LE"));
    byte[] data = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, data, 0, bom.length);
    System.arraycopy(body, 0, data, bom.length, body.length);

    assertThat(ParseOptions.detectEncoding(data)).isEqualTo("UTF-16LE");
    List<Map<String, String>> rows = service.parse(data, "csv");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).values()).contains("Alice", "30");
  }
}
