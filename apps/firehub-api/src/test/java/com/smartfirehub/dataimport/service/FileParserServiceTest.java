package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.exception.FileUnreadableException;
import com.smartfirehub.dataimport.exception.InvalidFileHeaderException;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure unit tests for FileParserService — no Spring context required. All test data is created
 * programmatically as byte arrays.
 */
class FileParserServiceTest {

  private FileParserService service;

  @TempDir private Path tempDir;

  @BeforeEach
  void setUp() {
    service = new FileParserService();
  }

  // Helper: CSV 문자열을 임시 파일로 저장 (Path 오버로드 테스트용)
  private Path writeCsvFile(String content) throws Exception {
    Path file = tempDir.resolve("test-" + System.nanoTime() + ".csv");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  // Helper: XLSX bytes를 임시 파일로 저장 (Path 오버로드 테스트용)
  private Path writeXlsxFile(byte[] data) throws Exception {
    Path file = tempDir.resolve("test-" + System.nanoTime() + ".xlsx");
    Files.write(file, data);
    return file;
  }

  // Helper: N개 데이터 행을 가진 CSV 문자열 생성(헤더 포함)
  private static String buildCsvWithRows(int dataRows) {
    StringBuilder sb = new StringBuilder("id,value\n");
    for (int i = 0; i < dataRows; i++) {
      sb.append(i).append(",v").append(i).append('\n');
    }
    return sb.toString();
  }

  // Helper: N개 데이터 행을 가진 XLSX(헤더 포함) 생성
  private static byte[] buildXlsxWithRows(int dataRows) throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("test");
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("id");
      h.createCell(1).setCellValue("value");
      for (int i = 0; i < dataRows; i++) {
        Row r = sheet.createRow(i + 1);
        r.createCell(0).setCellValue(i);
        r.createCell(1).setCellValue("v" + i);
      }
      wb.write(out);
      return out.toByteArray();
    }
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
    // epoch millis = 0 → 만들 때의 시스템 타임존에 따라 일련값이 달라 날짜 모양만 본다(ISO 'T' 아님, WD-252)
    String dateStr = rows.get(0).get("when");
    assertThat(dateStr).matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?");
  }

  /** 헤더 셀이 엑셀 오류 셀(#REF!)이면 표식이 열 이름이 되지 않게 한국어 헤더 오류로 거부한다(WD-252). */
  @Test
  void parseHeaders_xlsxHeaderErrorCell_isRejected() throws Exception {
    byte[] data;
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Row h = wb.createSheet("s").createRow(0);
      h.createCell(0).setCellValue("name");
      h.createCell(1).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());
      wb.write(out);
      data = out.toByteArray();
    }
    assertThatThrownBy(() -> service.parseHeaders(data, "xlsx"))
        .isInstanceOf(com.smartfirehub.dataimport.exception.InvalidFileHeaderException.class)
        .hasMessageContaining("헤더 행에 엑셀 오류 값이 있습니다: 2번째 열 #REF!");
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

  // -----------------------------------------------------------------------
  // countRows(InputStream) — readNext() 스트리밍 카운트 검증 (Task 2: 대용량 CSV OOM 방지)
  // skipRows > 0(파일 범위 내) + hasHeader true/false 조합에서도 카운트가 정확한지 확인
  // -----------------------------------------------------------------------

  @Test
  void countRows_csvViaInputStream_skipRowsWithinFile_hasHeaderTrue_excludesSkippedAndHeader()
      throws Exception {
    // 앞 2행(주석성 skip 행) + 헤더 1행 + 데이터 4행
    String csv = "# comment1\n# comment2\nname,age\nAlice,30\nBob,25\nCharlie,35\nDave,40";
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 2);

    int count = service.countRows(toStream(csv), "csv", opts);

    assertThat(count).isEqualTo(4);
  }

  @Test
  void countRows_csvViaInputStream_skipRowsWithinFile_hasHeaderFalse_countsAllRemainingRows()
      throws Exception {
    // 앞 1행 skip 후 헤더 없이 남은 4행 전부 데이터로 카운트
    String csv = "# comment\nAlice,30\nBob,25\nCharlie,35\nDave,40";
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 1);

    int count = service.countRows(toStream(csv), "csv", opts);

    assertThat(count).isEqualTo(4);
  }

  @Test
  void countRows_csvViaInputStream_manyRows_countsCorrectlyWithoutMaterializingAll()
      throws Exception {
    // readNext() 루프 기반 스트리밍 카운트가 대량 행에서도 정확한지 확인(#OOM 방지 회귀 방지용 규모)
    StringBuilder sb = new StringBuilder("id,value\n");
    int dataRows = 20_000;
    for (int i = 0; i < dataRows; i++) {
      sb.append(i).append(",v").append(i).append('\n');
    }
    ParseOptions opts = ParseOptions.defaults();

    int count = service.countRows(toStream(sb.toString()), "csv", opts);

    assertThat(count).isEqualTo(dataRows);
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
    // WD-245: BOM 은 읽기 단계에서 소비되어 첫 헤더 키가 정확히 "name" 이어야 한다
    assertThat(rows.get(0)).containsEntry("name", "Alice").containsEntry("age", "30");
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
   * 회귀 #263: BOM 없는 UTF-8 파일이지만 peek 버퍼(64KB) 끝에 한글 멀티바이트 시퀀스가 잘려 있는 경우, 이전 구현은 MALFORMED으로 판정해
   * EUC-KR로 폴백했다. CharsetDecoder 기반 구현은 truncation을 UNDERFLOW로 처리해 UTF-8로 올바로 감지한다.
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

  // -----------------------------------------------------------------------
  // WD-245: 파일 앞 BOM 소비 — 바이트·스트림 두 경로 모두 첫 헤더에 U+FEFF 가 남지 않아야 한다
  // -----------------------------------------------------------------------

  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
  private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

  /** BOM 바이트 + 본문을 이어 붙인다. */
  private static byte[] withBom(byte[] bom, String body, Charset charset) {
    byte[] b = body.getBytes(charset);
    byte[] out = new byte[bom.length + b.length];
    System.arraycopy(bom, 0, out, 0, bom.length);
    System.arraycopy(b, 0, out, bom.length, b.length);
    return out;
  }

  /** 원시 바이트를 임시 CSV 파일로 저장한다(스트림 경로 테스트용). */
  private Path writeCsvBytes(byte[] data) throws Exception {
    Path file = tempDir.resolve("bom-" + System.nanoTime() + ".csv");
    Files.write(file, data);
    return file;
  }

  /** 스트리밍 경로(실제 임포트·검증)로 모든 행을 모은다. */
  private List<Map<String, String>> streamAll(Path file, ParseOptions opts) throws Exception {
    List<Map<String, String>> all = new ArrayList<>();
    service.parseStreaming(file, "csv", opts, 100, all::addAll);
    return all;
  }

  @Test
  void bom_utf8_bytesPath_headerAndValuesMapped() throws Exception {
    byte[] data = withBom(UTF8_BOM, "name,도시\n홍길동,서울\n", StandardCharsets.UTF_8);
    ParseOptions opts = ParseOptions.defaults();

    // 미리보기(헤더·샘플)·전체 파싱 모두 첫 헤더가 정확히 "name"
    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("name", "도시");
    assertThat(service.parseSampleRows(data, "csv", 5, opts).get(0))
        .containsEntry("name", "홍길동")
        .containsEntry("도시", "서울");
    assertThat(service.parse(data, "csv", opts).get(0)).containsEntry("name", "홍길동");
  }

  @Test
  void bom_utf8_streamPath_headerAndValuesMapped() throws Exception {
    Path file = writeCsvBytes(withBom(UTF8_BOM, "name,도시\n홍길동,서울\n", StandardCharsets.UTF_8));
    ParseOptions opts = ParseOptions.defaults();

    assertThat(service.parseHeaders(file, "csv", opts)).containsExactly("name", "도시");
    assertThat(service.parseSampleRows(file, "csv", 5, opts).get(0)).containsEntry("name", "홍길동");
    assertThat(service.parse(file, "csv", opts).get(0)).containsEntry("name", "홍길동");
    // 실제 임포트가 지나는 스트리밍 경로 — 값이 null 로 빠지던 결함의 직접 재현
    List<Map<String, String>> rows = streamAll(file, opts);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("name", "홍길동").containsEntry("도시", "서울");
    assertThat(rows.get(0).keySet()).noneMatch(k -> k.indexOf('\uFEFF') >= 0);
    // 행 수 세기(헤더 제외)도 그대로
    assertThat(service.countRows(file, "csv", opts)).isEqualTo(1);
  }

  @Test
  void bom_utf8_inputStreamOverloads_headerMapped() throws Exception {
    byte[] data = withBom(UTF8_BOM, "name,age\nAlice,30\n", StandardCharsets.UTF_8);

    assertThat(service.parseHeaders(new ByteArrayInputStream(data), "csv", ParseOptions.defaults()))
        .containsExactly("name", "age");
    // 옛 parseCSV(InputStream) 경로도 같은 빌더를 지나 BOM 이 소비된다
    assertThat(service.parseCSV(new ByteArrayInputStream(data)).get(0))
        .containsEntry("name", "Alice");
  }

  @Test
  void bom_utf16le_bothPaths_bomConsumed() throws Exception {
    byte[] data = withBom(UTF16LE_BOM, "name,도시\n홍길동,서울\n", Charset.forName("UTF-16LE"));
    ParseOptions opts = ParseOptions.defaults();

    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("name", "도시");
    Path file = writeCsvBytes(data);
    assertThat(service.parseHeaders(file, "csv", opts)).containsExactly("name", "도시");
    assertThat(streamAll(file, opts).get(0)).containsEntry("name", "홍길동").containsEntry("도시", "서울");
  }

  @Test
  void bom_utf16be_bothPaths_bomConsumed() throws Exception {
    byte[] data = withBom(UTF16BE_BOM, "name,도시\n홍길동,서울\n", Charset.forName("UTF-16BE"));
    ParseOptions opts = ParseOptions.defaults();

    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("name", "도시");
    Path file = writeCsvBytes(data);
    assertThat(service.parseHeaders(file, "csv", opts)).containsExactly("name", "도시");
    assertThat(streamAll(file, opts).get(0)).containsEntry("name", "홍길동");
  }

  @Test
  void bom_explicitEncodingMatchingBom_bomConsumed() throws Exception {
    // 사용자가 BOM 과 같은 인코딩을 명시해도 BOM 은 소비된다
    byte[] data = withBom(UTF8_BOM, "name,age\nAlice,30\n", StandardCharsets.UTF_8);
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 0);

    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("name", "age");
    assertThat(streamAll(writeCsvBytes(data), opts).get(0)).containsEntry("name", "Alice");

    byte[] le = withBom(UTF16LE_BOM, "name,age\nAlice,30\n", Charset.forName("UTF-16LE"));
    ParseOptions leOpts = new ParseOptions(",", "UTF-16LE", true, 0);
    assertThat(service.parseHeaders(le, "csv", leOpts)).containsExactly("name", "age");
    assertThat(streamAll(writeCsvBytes(le), leOpts).get(0)).containsEntry("name", "Alice");
  }

  @Test
  void bom_explicitEucKrButUtf8Bom_bomWins() throws Exception {
    // UTF-8 BOM 이 있으면 실제 인코딩은 UTF-8 — EUC-KR 로 읽으면 BOM 이 깨진 글자로 헤더에 붙고 한글이 깨지므로 BOM 을 우선한다
    byte[] data = withBom(UTF8_BOM, "이름,도시\n홍길동,서울\n", StandardCharsets.UTF_8);
    ParseOptions opts = new ParseOptions(",", "EUC-KR", true, 0);

    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("이름", "도시");
    assertThat(service.parse(data, "csv", opts).get(0)).containsEntry("이름", "홍길동");
    assertThat(streamAll(writeCsvBytes(data), opts).get(0))
        .containsEntry("이름", "홍길동")
        .containsEntry("도시", "서울");
  }

  @Test
  void noBom_explicitEucKr_stillUsesChosenEncoding() throws Exception {
    // BOM 이 없으면 사용자가 고른 인코딩을 그대로 따른다(기존 동작)
    byte[] data = "이름,도시\n홍길동,서울\n".getBytes(Charset.forName("EUC-KR"));
    ParseOptions opts = new ParseOptions(",", "EUC-KR", true, 0);

    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("이름", "도시");
    assertThat(streamAll(writeCsvBytes(data), opts).get(0)).containsEntry("이름", "홍길동");
  }

  @Test
  void noBom_utf8_unchanged_firstCharNotDropped() throws Exception {
    // BOM 없는 파일은 첫 바이트를 건너뛰지 않는다(첫 헤더 글자가 잘리면 안 된다) — 짧은 파일 포함
    ParseOptions opts = ParseOptions.defaults();
    byte[] data = "name,age\nAlice,30\n".getBytes(StandardCharsets.UTF_8);
    assertThat(service.parseHeaders(data, "csv", opts)).containsExactly("name", "age");
    Path file = writeCsvBytes(data);
    assertThat(service.parseHeaders(file, "csv", opts)).containsExactly("name", "age");
    assertThat(streamAll(file, opts).get(0)).containsEntry("name", "Alice");

    // 명시 인코딩 + 3바이트 미만 파일도 그대로 읽힌다
    ParseOptions utf8 = new ParseOptions(",", "UTF-8", true, 0);
    assertThat(
            service.parseHeaders(writeCsvBytes("ab".getBytes(StandardCharsets.UTF_8)), "csv", utf8))
        .containsExactly("ab");
  }

  @Test
  void detectBom_returnsEncodingAndLength() {
    assertThat(ParseOptions.detectBom(UTF8_BOM)).isEqualTo(new ParseOptions.Bom("UTF-8", 3));
    assertThat(ParseOptions.detectBom(UTF16LE_BOM)).isEqualTo(new ParseOptions.Bom("UTF-16LE", 2));
    assertThat(ParseOptions.detectBom(UTF16BE_BOM)).isEqualTo(new ParseOptions.Bom("UTF-16BE", 2));
    assertThat(ParseOptions.detectBom("name".getBytes(StandardCharsets.UTF_8))).isNull();
    assertThat(ParseOptions.detectBom(new byte[0])).isNull();
    assertThat(ParseOptions.detectBom(null)).isNull();
  }

  // -----------------------------------------------------------------------
  // parseStreaming(Path, ...) — 배치 콜백 스트리밍 (Task 5)
  // -----------------------------------------------------------------------

  @Test
  void parseStreaming_csvWithHeader_batchesOfThreeSumToTotal() throws Exception {
    int dataRows = 10; // ceil(10/3) = 4 batches: 3,3,3,1
    Path file = writeCsvFile(buildCsvWithRows(dataRows));
    List<List<Map<String, String>>> batches = new ArrayList<>();

    service.parseStreaming(file, "csv", ParseOptions.defaults(), 3, batches::add);

    assertThat(batches).hasSize(4);
    int total = 0;
    for (List<Map<String, String>> batch : batches) {
      assertThat(batch.size()).isLessThanOrEqualTo(3);
      total += batch.size();
    }
    assertThat(total).isEqualTo(dataRows);
    assertThat(batches.get(0).get(0)).containsKeys("id", "value");
    assertThat(batches.get(0).get(0)).containsEntry("id", "0").containsEntry("value", "v0");
    // 마지막 배치는 나머지 1행
    assertThat(batches.get(3)).hasSize(1);
  }

  @Test
  void parseStreaming_csvNoHeader_firstRowIncludedAsData() throws Exception {
    // hasHeader=false: 첫 행도 데이터로 포함되어야 함
    String csv = "Alice,30\nBob,25\nCharlie,35\nDave,40\nEve,45";
    Path file = writeCsvFile(csv);
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);
    List<List<Map<String, String>>> batches = new ArrayList<>();

    service.parseStreaming(file, "csv", opts, 3, batches::add);

    int total = batches.stream().mapToInt(List::size).sum();
    assertThat(total).isEqualTo(5);
    assertThat(batches.get(0).get(0))
        .containsEntry("column_1", "Alice")
        .containsEntry("column_2", "30");
  }

  @Test
  void parseStreaming_csvWithSkipRows_skipsBeforeHeader() throws Exception {
    String csv = "# c1\n# c2\nname,age\nAlice,30\nBob,25\nCharlie,35\nDave,40";
    Path file = writeCsvFile(csv);
    ParseOptions opts = new ParseOptions(",", "UTF-8", true, 2);
    List<List<Map<String, String>>> batches = new ArrayList<>();

    service.parseStreaming(file, "csv", opts, 3, batches::add);

    int total = batches.stream().mapToInt(List::size).sum();
    assertThat(total).isEqualTo(4);
    assertThat(batches.get(0).get(0)).containsEntry("name", "Alice");
  }

  @Test
  void parseStreaming_xlsxWithHeader_batchesOfThreeSumToTotal() throws Exception {
    int dataRows = 10;
    byte[] data = buildXlsxWithRows(dataRows);
    Path file = writeXlsxFile(data);
    List<List<Map<String, String>>> batches = new ArrayList<>();

    service.parseStreaming(file, "xlsx", ParseOptions.defaults(), 3, batches::add);

    assertThat(batches).hasSize(4);
    int total = 0;
    for (List<Map<String, String>> batch : batches) {
      assertThat(batch.size()).isLessThanOrEqualTo(3);
      total += batch.size();
    }
    assertThat(total).isEqualTo(dataRows);
    assertThat(batches.get(0).get(0)).containsKeys("id", "value");
    assertThat(batches.get(0).get(0)).containsEntry("id", "0").containsEntry("value", "v0");
    assertThat(batches.get(3)).hasSize(1);
  }

  @Test
  void parseStreaming_csvExactMultipleOfBatchSize_noEmptyFinalBatch() throws Exception {
    // 정확히 나누어 떨어지는 경우 마지막 빈 배치가 flush되지 않아야 함
    int dataRows = 9; // 3배치 * 3
    Path file = writeCsvFile(buildCsvWithRows(dataRows));
    List<List<Map<String, String>>> batches = new ArrayList<>();

    service.parseStreaming(file, "csv", ParseOptions.defaults(), 3, batches::add);

    assertThat(batches).hasSize(3);
    batches.forEach(b -> assertThat(b).hasSize(3));
  }

  @Test
  void parseStreaming_unsupportedFileType_throws() throws Exception {
    Path file = writeCsvFile("a,b\n1,2");
    assertThatThrownBy(
            () -> service.parseStreaming(file, "pdf", ParseOptions.defaults(), 3, b -> {}))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  // -----------------------------------------------------------------------
  // Path 기반 오버로드 — InputStream 오버로드와 동일 결과 검증 (Task 5)
  // -----------------------------------------------------------------------

  @Test
  void parseHeaders_csvViaPath_matchesInputStreamOverload() throws Exception {
    String csv = "name,age,city\nAlice,30,Seoul";
    Path file = writeCsvFile(csv);
    ParseOptions opts = ParseOptions.defaults();

    List<String> pathHeaders = service.parseHeaders(file, "csv", opts);
    List<String> streamHeaders = service.parseHeaders(toStream(csv), "csv", opts);

    assertThat(pathHeaders).isEqualTo(streamHeaders);
  }

  @Test
  void parseHeaders_xlsxViaPath_matchesInputStreamOverload() throws Exception {
    byte[] data = buildXlsx();
    Path file = writeXlsxFile(data);
    ParseOptions opts = ParseOptions.defaults();

    List<String> pathHeaders = service.parseHeaders(file, "xlsx", opts);
    List<String> streamHeaders = service.parseHeaders(new ByteArrayInputStream(data), "xlsx", opts);

    assertThat(pathHeaders).isEqualTo(streamHeaders);
  }

  @Test
  void parseSampleRows_csvViaPath_matchesInputStreamOverload() throws Exception {
    String csv = "id,name\n1,a\n2,b\n3,c\n4,d\n5,e";
    Path file = writeCsvFile(csv);
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> pathRows = service.parseSampleRows(file, "csv", 3, opts);
    List<Map<String, String>> streamRows = service.parseSampleRows(toStream(csv), "csv", 3, opts);

    assertThat(pathRows).isEqualTo(streamRows);
  }

  @Test
  void parseSampleRows_xlsxViaPath_matchesInputStreamOverload() throws Exception {
    byte[] data = buildXlsx();
    Path file = writeXlsxFile(data);
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> pathRows = service.parseSampleRows(file, "xlsx", 1, opts);
    List<Map<String, String>> streamRows =
        service.parseSampleRows(new ByteArrayInputStream(data), "xlsx", 1, opts);

    assertThat(pathRows).isEqualTo(streamRows);
  }

  @Test
  void countRows_csvViaPath_matchesInputStreamOverload() throws Exception {
    String csv = "name,age\nAlice,30\nBob,25\nCharlie,35";
    Path file = writeCsvFile(csv);
    ParseOptions opts = ParseOptions.defaults();

    int pathCount = service.countRows(file, "csv", opts);
    int streamCount = service.countRows(toStream(csv), "csv", opts);

    assertThat(pathCount).isEqualTo(streamCount).isEqualTo(3);
  }

  @Test
  void countRows_xlsxViaPath_matchesInputStreamOverload() throws Exception {
    byte[] data = buildXlsx();
    Path file = writeXlsxFile(data);
    ParseOptions opts = ParseOptions.defaults();

    int pathCount = service.countRows(file, "xlsx", opts);
    int streamCount = service.countRows(new ByteArrayInputStream(data), "xlsx", opts);

    assertThat(pathCount).isEqualTo(streamCount).isEqualTo(2);
  }

  @Test
  void parse_csvViaPath_matchesInputStreamOverload() throws Exception {
    String csv = "id,value\n1,foo\n2,bar";
    Path file = writeCsvFile(csv);
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> pathRows = service.parse(file, "csv", opts);
    List<Map<String, String>> streamRows = service.parse(toStream(csv), "csv", opts);

    assertThat(pathRows).isEqualTo(streamRows);
  }

  @Test
  void parse_xlsxViaPath_matchesInputStreamOverload() throws Exception {
    byte[] data = buildXlsx();
    Path file = writeXlsxFile(data);
    ParseOptions opts = ParseOptions.defaults();

    List<Map<String, String>> pathRows = service.parse(file, "xlsx", opts);
    List<Map<String, String>> streamRows =
        service.parse(new ByteArrayInputStream(data), "xlsx", opts);

    assertThat(pathRows).isEqualTo(streamRows);
  }

  @Test
  void parseHeaders_viaPath_unsupportedFileType_throws() throws Exception {
    Path file = writeCsvFile("x");
    assertThatThrownBy(() -> service.parseHeaders(file, "doc", ParseOptions.defaults()))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  // -----------------------------------------------------------------------
  // WD-204 — 읽을 수 없는 파일은 한국어 안내 예외(FileUnreadableException)로 바뀌고 파서 원문은 새지 않는다
  // -----------------------------------------------------------------------

  /** 파서 원문(영문 POI/opencsv 메시지)의 흔적이 사용자 메시지에 없는지 확인한다. */
  private static void assertKoreanOnly(Throwable t, String expected) {
    assertThat(t).isInstanceOf(FileUnreadableException.class).hasMessage(expected);
    assertThat(t.getMessage())
        .doesNotContain("OOXML")
        .doesNotContain("Unsupported")
        .doesNotContain("POI")
        .doesNotContain("Unterminated");
  }

  /** 미리보기(헤더·샘플·행 수)와 임포트 잡(스트리밍) 경로 모두 같은 한국어 예외를 던지는지 확인한다. */
  private void assertAllPathsUnreadable(Path file, String type, String expected) {
    ParseOptions opts = ParseOptions.defaults();
    assertKoreanOnly(catchThrowable(() -> service.parseHeaders(file, type, opts)), expected);
    assertKoreanOnly(catchThrowable(() -> service.parseSampleRows(file, type, 5, opts)), expected);
    assertKoreanOnly(catchThrowable(() -> service.countRows(file, type, opts)), expected);
    assertKoreanOnly(
        catchThrowable(() -> service.parseStreaming(file, type, opts, 100, batch -> {})), expected);
  }

  @Test
  void excel_plainTextNamedXlsx_throwsKoreanUnreadable() throws Exception {
    // printf 'not an excel file' > fake.xlsx — 예전엔 IOException 이 빈 200 응답이 됐다
    Path file = writeXlsxFile("not an excel file".getBytes(StandardCharsets.UTF_8));
    assertAllPathsUnreadable(file, "xlsx", FileUnreadableException.EXCEL_UNREADABLE);
  }

  @Test
  void excel_corruptedZip_throwsKoreanUnreadable_withoutPoiText() throws Exception {
    // printf 'PK\x03\x04garbage' > t.xlsx — 예전엔 POI 영문 메시지가 400 본문·토스트에 그대로 보였다
    byte[] data = {'P', 'K', 3, 4, 'g', 'a', 'r', 'b', 'a', 'g', 'e'};
    Path file = writeXlsxFile(data);
    assertAllPathsUnreadable(file, "xlsx", FileUnreadableException.EXCEL_UNREADABLE);
  }

  @Test
  void excel_emptyFile_throwsKoreanUnreadable() throws Exception {
    Path file = writeXlsxFile(new byte[0]);
    assertAllPathsUnreadable(file, "xlsx", FileUnreadableException.EXCEL_UNREADABLE);
  }

  @Test
  void excel_ole2WithoutWorkbook_throwsKoreanUnreadable() throws Exception {
    // OLE2 컨테이너지만 Workbook 스트림이 없는 파일(워드 등 다른 오피스 문서와 같은 경우)
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      fs.writeFilesystem(bos);
    }
    Path file = tempDir.resolve("ole2-" + System.nanoTime() + ".xls");
    Files.write(file, bos.toByteArray());
    assertAllPathsUnreadable(file, "xls", FileUnreadableException.EXCEL_UNREADABLE);
  }

  @Test
  void excel_encryptedXlsx_throwsKoreanEncryptedMessage() throws Exception {
    // 암호가 걸린 xlsx 는 OLE2 컨테이너(EncryptedPackage) — 사용자가 직접 해결할 수 있어 별도 문구로 안내한다
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (POIFSFileSystem fs = new POIFSFileSystem()) {
      EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
      Encryptor enc = info.getEncryptor();
      enc.confirmPassword("secret");
      try (OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(buildXlsx()));
          java.io.OutputStream os = enc.getDataStream(fs)) {
        opc.save(os);
      }
      fs.writeFilesystem(bos);
    }
    Path file = writeXlsxFile(bos.toByteArray());
    assertAllPathsUnreadable(file, "xlsx", FileUnreadableException.EXCEL_ENCRYPTED);
  }

  @Test
  void csv_unterminatedQuote_throwsKoreanUnreadable() throws Exception {
    // 첫 줄부터 닫히지 않은 따옴표 — opencsv 가 CsvMalformedLineException(IOException 하위)을 던져 예전엔 빈 200 응답이 됐다
    Path file = writeCsvFile("\"id,name\n1,a\n2,b\n");
    assertAllPathsUnreadable(file, "csv", FileUnreadableException.CSV_UNREADABLE);
  }

  // -----------------------------------------------------------------------
  // WD-205: 같은 이름 열(trim·BOM 제거 기준)이 있는 헤더 거절 — 빈 이름 열은 몇 개든 기존 동작 유지
  // 행을 「헤더 이름 → 값」 Map 으로 담으므로 겹치는 이름이 있으면 뒤 열 값이 앞 열을 조용히 덮어쓴다.
  // 모든 형식·모든 헤더 읽기 지점(헤더/샘플/전체/스트리밍)이 같은 400 예외로 거절하는지 확인한다.
  // -----------------------------------------------------------------------

  private static final String DUP_MSG =
      "파일에 같은 이름의 열이 있습니다: region (1·2번째 열). 열 이름을 고친 뒤 다시 올려 주세요.";

  /** 헤더 한 줄 + 데이터 한 줄짜리 Excel(xlsx/xls) 바이트를 만든다. 빈 칸은 셀을 만들지 않는다. */
  private static byte[] buildWorkbook(Workbook wb, String[] header, String[] data)
      throws Exception {
    try (wb;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("Sheet1");
      String[][] rows = {header, data};
      for (int r = 0; r < rows.length; r++) {
        Row row = sheet.createRow(r);
        for (int c = 0; c < rows[r].length; c++) {
          if (!rows[r][c].isEmpty()) row.createCell(c).setCellValue(rows[r][c]);
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }

  private Path writeFile(String ext, byte[] data) throws Exception {
    Path file = tempDir.resolve("wd205-" + System.nanoTime() + "." + ext);
    Files.write(file, data);
    return file;
  }

  @Test
  void duplicateHeader_csv_everyEntryPointRejectsWithKoreanMessage() throws Exception {
    String csv = "region,region,cnt\n서울,부산,1\n";
    Path file = writeCsvFile(csv);
    ParseOptions opts = ParseOptions.defaults();
    List<List<Map<String, String>>> batches = new ArrayList<>();

    // Path 오버로드(실서비스 경로): 헤더·샘플·전체·스트리밍
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(DUP_MSG);
    assertThatThrownBy(() -> service.parseSampleRows(file, "csv", 5, opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(DUP_MSG);
    assertThatThrownBy(() -> service.parse(file, "csv", opts))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(DUP_MSG);
    assertThatThrownBy(() -> service.parseStreaming(file, "csv", opts, 3, batches::add))
        .isExactlyInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(DUP_MSG);
    assertThat(batches).isEmpty(); // 데이터 행을 하나도 넘기기 전에 거절

    // InputStream·byte[] 오버로드도 같은 규칙
    assertThatThrownBy(() -> service.parseCSV(toStream(csv), opts))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parseHeaders(toStream(csv), "csv", opts))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parseSampleRows(toStream(csv), "csv", 5, opts))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parse(toStream(csv), "csv", opts))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parse(toBytes(csv), "csv"))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parseHeaders(toBytes(csv), "csv"))
        .isInstanceOf(InvalidFileHeaderException.class);
    assertThatThrownBy(() -> service.parseSampleRows(toBytes(csv), "csv", 5))
        .isInstanceOf(InvalidFileHeaderException.class);
    // 기존 400 핸들러를 타도록 UnsupportedFileTypeException 계열이어야 한다
    assertThat(new InvalidFileHeaderException("x"))
        .isInstanceOf(UnsupportedFileTypeException.class);
  }

  @Test
  void duplicateHeader_csvWithBomAndSpaces_comparedAfterTrim() throws Exception {
    // 엑셀이 내보낸 UTF-8 CSV 는 첫 칸 앞에 BOM 이 붙는다 — BOM·공백을 걷어낸 이름으로 비교한다
    Path file = writeCsvFile("\uFEFFregion, region ,cnt\n서울,부산,1\n");
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .isInstanceOf(InvalidFileHeaderException.class)
        .hasMessage(DUP_MSG);
  }

  @Test
  void blankHeaders_twoOrMore_keepExistingBehavior() throws Exception {
    // 동작 고정: 이름이 빈 열은 몇 개든 거절하지 않는다(빈 이름 열은 기본 미매핑이라 피해가 작고, 거절하면 끝 빈 열 파일 회귀가 크다)
    Path file = writeCsvFile("a,b,,d,  \n1,2,3,4,5\n");
    assertThat(service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .containsExactly("a", "b", "", "d", "  ");
    assertThat(service.parseSampleRows(file, "csv", 5, ParseOptions.defaults())).hasSize(1);
  }

  @Test
  void duplicateNames_withBlanks_onlyNamedDuplicatesReported() throws Exception {
    // 겹치는 이름이 여러 개면 한 번에 모두 알리고, 빈 이름 열(2·4번째)은 메시지에 넣지 않는다
    Path file = writeCsvFile("x,,x,,y,y\n1,2,3,4,5,6\n");
    assertThatThrownBy(() -> service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .hasMessage("파일에 같은 이름의 열이 있습니다: x (1·3번째 열), y (5·6번째 열). 열 이름을 고친 뒤 다시 올려 주세요.");
  }

  @Test
  void trailingDelimiters_twoBlankHeaders_stillAccepted() throws Exception {
    // 회귀 방지: 엑셀 CSV 내보내기에서 흔한 끝 빈 열(a,b,,)은 계속 통과한다
    Path file = writeCsvFile("a,b,,\n1,2,,\n");
    assertThat(service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .containsExactly("a", "b", "", "");
    List<List<Map<String, String>>> batches = new ArrayList<>();
    service.parseStreaming(file, "csv", ParseOptions.defaults(), 10, batches::add);
    assertThat(batches).hasSize(1);
    assertThat(batches.get(0).get(0)).containsEntry("a", "1").containsEntry("b", "2");
  }

  @Test
  void singleBlankHeader_keepsExistingBehavior() throws Exception {
    // 이름 없는 열이 하나뿐이면 겹칠 대상이 없으므로 기존처럼 빈 문자열 키로 통과한다
    Path file = writeCsvFile("a,,c\n1,2,3\n");
    assertThat(service.parseHeaders(file, "csv", ParseOptions.defaults()))
        .containsExactly("a", "", "c");
    assertThat(service.parse(file, "csv", ParseOptions.defaults()))
        .containsExactly(Map.of("a", "1", "", "2", "c", "3"));
  }

  @Test
  void noHeaderMode_generatedNamesNeverRejected() throws Exception {
    // hasHeader=false 면 column_N 을 합성하므로 첫 행에 같은 값이 있어도 헤더 검사 대상이 아니다
    Path file = writeCsvFile("x,x\n1,2\n");
    ParseOptions opts = new ParseOptions(",", "UTF-8", false, 0);
    assertThat(service.parseHeaders(file, "csv", opts)).containsExactly("column_1", "column_2");
  }

  @Test
  void duplicateHeader_xlsxAndXls_everyEntryPointRejected() throws Exception {
    String[] header = {"region", "region", "cnt"};
    String[] data = {"서울", "부산", "1"};
    for (Object[] fmt :
        new Object[][] {
          {"xlsx", buildWorkbook(new XSSFWorkbook(), header, data)},
          {"xls", buildWorkbook(new HSSFWorkbook(), header, data)}
        }) {
      String type = (String) fmt[0];
      byte[] bytes = (byte[]) fmt[1];
      Path file = writeFile(type, bytes);
      ParseOptions opts = ParseOptions.defaults();
      List<List<Map<String, String>>> batches = new ArrayList<>();

      // SAX(XSSF)·이벤트(HSSF) 콜백 안에서 던진 예외가 감싸지지 않고 그대로 나오는지 정확한 타입으로 확인
      assertThatThrownBy(() -> service.parseHeaders(file, type, opts))
          .isExactlyInstanceOf(InvalidFileHeaderException.class)
          .hasMessage(DUP_MSG);
      assertThatThrownBy(() -> service.parseSampleRows(file, type, 5, opts))
          .isExactlyInstanceOf(InvalidFileHeaderException.class);
      assertThatThrownBy(() -> service.parse(file, type, opts))
          .isExactlyInstanceOf(InvalidFileHeaderException.class);
      assertThatThrownBy(() -> service.parseStreaming(file, type, opts, 3, batches::add))
          .isExactlyInstanceOf(InvalidFileHeaderException.class);
      assertThat(batches).isEmpty();
      // InputStream 오버로드
      assertThatThrownBy(() -> service.parseHeaders(new ByteArrayInputStream(bytes), type, opts))
          .isExactlyInstanceOf(InvalidFileHeaderException.class);
      assertThatThrownBy(
              () -> service.parseSampleRows(new ByteArrayInputStream(bytes), type, 5, opts))
          .isExactlyInstanceOf(InvalidFileHeaderException.class);
      assertThatThrownBy(() -> service.parseExcel(new ByteArrayInputStream(bytes)))
          .isExactlyInstanceOf(InvalidFileHeaderException.class);
    }
  }

  @Test
  void blankHeaders_xlsx_middleGapsAccepted() throws Exception {
    // 헤더 셀이 비어 있는 열 2개(2·4번째) — 열 위치 보정으로 빈 이름이 채워지지만 빈 이름은 거절 대상이 아니다
    byte[] bytes =
        buildWorkbook(
            new XSSFWorkbook(),
            new String[] {"a", "", "c", "", "e"},
            new String[] {"1", "2", "3", "4", "5"});
    Path file = writeFile("xlsx", bytes);
    assertThat(service.parseHeaders(file, "xlsx", ParseOptions.defaults()))
        .containsExactly("a", "", "c", "", "e");
  }

  @Test
  void normalHeaders_xlsx_stillParse() throws Exception {
    byte[] bytes =
        buildWorkbook(
            new XSSFWorkbook(), new String[] {"region", "city"}, new String[] {"서울", "부산"});
    Path file = writeFile("xlsx", bytes);
    assertThat(service.parse(file, "xlsx", ParseOptions.defaults()))
        .containsExactly(Map.of("region", "서울", "city", "부산"));
  }

  @Test
  void xlsStyledBlankHeaderCells_stillAccepted() throws Exception {
    // 회귀 방지: xls 헤더 행 오른쪽에 서식만 있는 빈 셀(BlankRecord)은 빈 이름 열로 잡히지만 거절하지 않는다
    byte[] bytes;
    try (Workbook wb = new HSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("Sheet1");
      CellStyle bordered = wb.createCellStyle();
      bordered.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("a");
      header.createCell(1).setCellValue("b");
      header.createCell(2).setCellStyle(bordered);
      header.createCell(3).setCellStyle(bordered);
      Row data = sheet.createRow(1);
      data.createCell(0).setCellValue("1");
      data.createCell(1).setCellValue("2");
      wb.write(out);
      bytes = out.toByteArray();
    }
    Path file = writeFile("xls", bytes);
    assertThat(service.parseHeaders(file, "xls", ParseOptions.defaults()))
        .containsExactly("a", "b", "", "");
  }

  @Test
  void xlsxStyledBlankHeaderCells_ignoredSoNotRejected() throws Exception {
    // xlsx 는 서식만 있는 빈 셀을 SAX 핸들러가 셀로 넘기지 않아 헤더에 아예 잡히지 않는다(xls 와 대비)
    byte[] bytes;
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("Sheet1");
      CellStyle bordered = wb.createCellStyle();
      bordered.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("a");
      header.createCell(1).setCellValue("b");
      header.createCell(2).setCellStyle(bordered);
      header.createCell(3).setCellStyle(bordered);
      Row data = sheet.createRow(1);
      data.createCell(0).setCellValue("1");
      data.createCell(1).setCellValue("2");
      wb.write(out);
      bytes = out.toByteArray();
    }
    Path file = writeFile("xlsx", bytes);
    assertThat(service.parseHeaders(file, "xlsx", ParseOptions.defaults()))
        .containsExactly("a", "b");
  }
}
