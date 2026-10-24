package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelStreamingParserTest {

  private static byte[] buildXlsx() throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("data");
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("name");
      h.createCell(1).setCellValue("age");
      Row r1 = sheet.createRow(1);
      r1.createCell(0).setCellValue("alice");
      r1.createCell(1).setCellValue(30);
      Row r2 = sheet.createRow(2);
      r2.createCell(0).setCellValue("bob");
      r2.createCell(1).setCellValue(25);
      wb.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] buildXlsxWithDate() throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("data");
      CreationHelper helper = wb.getCreationHelper();
      CellStyle dateStyle = wb.createCellStyle();
      dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("when");
      Row r1 = sheet.createRow(1);
      var c = r1.createCell(0);
      c.setCellValue(new Date(0L)); // 1970-01-01
      c.setCellStyle(dateStyle);
      wb.write(out);
      return out.toByteArray();
    }
  }

  @Test
  void parses_xlsx_rows_in_order() throws Exception {
    byte[] data = buildXlsx();
    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        new ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsExactly("name", "age");
    assertThat(rows.get(1)).containsExactly("alice", "30");
    assertThat(rows.get(2)).containsExactly("bob", "25");
  }

  @Test
  void early_exit_stops_after_first_row() throws Exception {
    byte[] data = buildXlsx();
    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        new ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return false; // first row만 받고 종료
        });

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsExactly("name", "age");
  }

  @Test
  void date_cell_serialized_as_date_text() throws Exception {
    byte[] data = buildXlsxWithDate();
    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        new ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });

    assertThat(rows).hasSize(2);
    // new Date(0L) 의 일련값은 만들 때의 시간대에 따라 달라 날짜 모양만 본다 — ISO 'T' 가 아니라 날짜 또는 "날짜 시각"(WD-252)
    assertThat(rows.get(1).get(0)).matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?");
  }

  @Test
  void empty_xlsx_produces_no_rows() throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      wb.createSheet("empty");
      wb.write(out);
      List<List<String>> rows = new ArrayList<>();
      ExcelStreamingParser.parse(
          new ByteArrayInputStream(out.toByteArray()),
          (idx, cells) -> {
            rows.add(cells);
            return true;
          });
      assertThat(rows).isEmpty();
    }
  }

  private static byte[] buildXls() throws Exception {
    try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb =
            new org.apache.poi.hssf.usermodel.HSSFWorkbook();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("data");
      org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("name");
      h.createCell(1).setCellValue("age");
      org.apache.poi.ss.usermodel.Row r1 = sheet.createRow(1);
      r1.createCell(0).setCellValue("alice");
      r1.createCell(1).setCellValue(30);
      org.apache.poi.ss.usermodel.Row r2 = sheet.createRow(2);
      r2.createCell(0).setCellValue("bob");
      r2.createCell(1).setCellValue(25);
      wb.write(out);
      return out.toByteArray();
    }
  }

  @Test
  void parses_xls_rows_in_order() throws Exception {
    byte[] data = buildXls();
    java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
    ExcelStreamingParser.parse(
        new java.io.ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsExactly("name", "age");
    assertThat(rows.get(1)).containsExactly("alice", "30");
    assertThat(rows.get(2)).containsExactly("bob", "25");
  }

  @Test
  void xls_early_exit_stops_after_first_row() throws Exception {
    byte[] data = buildXls();
    java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
    ExcelStreamingParser.parse(
        new java.io.ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return false;
        });

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsExactly("name", "age");
  }

  /** XLS 날짜 셀이 날짜 문자열로 직렬화되는지 확인한다. */
  @Test
  void xls_date_cell_serialized_as_date_text() throws Exception {
    byte[] data;
    try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb =
            new org.apache.poi.hssf.usermodel.HSSFWorkbook();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("data");
      org.apache.poi.ss.usermodel.CreationHelper helper = wb.getCreationHelper();
      org.apache.poi.ss.usermodel.CellStyle dateStyle = wb.createCellStyle();
      dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
      org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("when");
      org.apache.poi.ss.usermodel.Row r = sheet.createRow(1);
      var c = r.createCell(0);
      c.setCellValue(new java.util.Date(0L));
      c.setCellStyle(dateStyle);
      wb.write(out);
      data = out.toByteArray();
    }
    java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
    ExcelStreamingParser.parse(
        new java.io.ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });
    // new Date(0L) 의 일련값은 만들 때의 시간대에 따라 달라 날짜 모양만 본다(WD-252)
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get(0)).matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?");
  }

  /**
   * XLSX 중간 빈 셀이 빈 문자열로 채워지는지 확인한다 (갭 보정 회귀 방지).
   *
   * <p>헤더가 3열(a, b, c)일 때 데이터 행의 B 열을 생략하면 ["x", "", "z"]가 되어야 한다.
   */
  @Test
  void xlsx_blank_cell_in_middle_is_filled_with_empty_string() throws Exception {
    byte[] data;
    try (Workbook wb = new XSSFWorkbook();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("data");
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("a");
      h.createCell(1).setCellValue("b");
      h.createCell(2).setCellValue("c");
      Row r = sheet.createRow(1);
      r.createCell(0).setCellValue("x");
      // B열(인덱스 1) 생략 — 빈 셀
      r.createCell(2).setCellValue("z");
      wb.write(out);
      data = out.toByteArray();
    }
    java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
    ExcelStreamingParser.parse(
        new java.io.ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1)).containsExactly("x", "", "z");
  }

  /**
   * XLSX 문자열 셀에 리터럴 "TRUE"가 입력된 경우 대문자가 그대로 보존되는지 확인한다 (불리언 정규화 제거 회귀 방지).
   *
   * <p>실제 불리언 셀과 달리, 문자열 타입으로 "TRUE"를 저장한 셀은 소문자로 변환되어서는 안 된다.
   */
  @Test
  void xlsx_string_cell_with_literal_TRUE_is_preserved_uppercase() throws Exception {
    byte[] data;
    try (Workbook wb = new XSSFWorkbook();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("data");
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("flag");
      Row r = sheet.createRow(1);
      r.createCell(0).setCellValue("TRUE"); // 문자열 셀 — 불리언 셀 아님
      wb.write(out);
      data = out.toByteArray();
    }
    java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
    ExcelStreamingParser.parse(
        new java.io.ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });
    assertThat(rows).hasSize(2);
    // 문자열 "TRUE"는 소문자로 변환되지 않고 그대로 보존되어야 한다
    assertThat(rows.get(1)).containsExactly("TRUE");
  }

  // =====================================================================
  // Task 4 회귀 테스트 — File 기반 랜덤액세스 전환 검증
  //
  // SXSSFWorkbook의 자연스러운 출력물은 ZIP 엔트리 크기가 데이터 디스크립터(size=-1)로 기록된다.
  // 이는 POI의 setThresholdBytesForTempFiles가 무력화되는 정확히 그 케이스이며,
  // 이 테스트들은 File 기반 오픈(OPCPackage.open(File, PackageAccess))이 그 케이스에서도
  // 여전히 안전하게 동작함을 증명한다.
  // =====================================================================

  private static final int LARGE_ROW_COUNT = 20_000;

  /**
   * SXSSFWorkbook으로 대용량(2만 행) 숫자 위주 시트를 생성해 File로 저장한다. 숫자 위주로 구성해 sharedStrings/styles 엔트리가 아닌
   * sheet1.xml 엔트리 자체가 커지도록 한다.
   */
  private static void writeLargeDataDescriptorFixture(File dest) throws Exception {
    try (SXSSFWorkbook wb = new SXSSFWorkbook(100);
        FileOutputStream out = new FileOutputStream(dest)) {
      Sheet sheet = wb.createSheet("data");
      Row header = sheet.createRow(0);
      for (int c = 0; c < 10; c++) {
        header.createCell(c).setCellValue("col" + c);
      }
      for (int r = 1; r <= LARGE_ROW_COUNT; r++) {
        Row row = sheet.createRow(r);
        for (int c = 0; c < 10; c++) {
          row.createCell(c).setCellValue(r * 1000.0 + c);
        }
      }
      wb.write(out);
      wb.dispose(); // SXSSF 임시 파일 정리
    }
  }

  /**
   * 픽스처가 실제로 data-descriptor(엔트리 크기 미상, size=-1) zip인지 직접 검증한다.
   *
   * <p>브리프의 MUST 조건 — "테스트 픽스처는 반드시 data-descriptor zip이어야 한다" — 을 SXSSF 자연 출력에 의존하는 방식으로만 만족시키면,
   * 향후 POI/SXSSF 구현이 바뀌어 알려진 크기를 쓰게 되더라도 테스트가 조용히 통과해버려 더 이상 목표 케이스를 검증하지 못하는 상태가 될 수 있다. {@code
   * ZipArchiveInputStream}(POI가 내부적으로 사용하는 것과 동일한 commons-compress 구현체)으로 sheet1.xml 엔트리를 {@code
   * getNextEntry()} 하자마자 {@code getSize()}가 -1을 반환하는지 명시적으로 확인해 이 전제를 고정한다.
   */
  private static void assertSheetEntryIsDataDescriptor(File fixture) throws Exception {
    try (ZipArchiveInputStream zis = new ZipArchiveInputStream(new FileInputStream(fixture))) {
      ZipArchiveEntry entry;
      boolean found = false;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
          assertThat(entry.getSize())
              .as("SXSSF 자연 출력 픽스처는 getNextEntry() 시점에 크기를 알 수 없어야 한다(data descriptor)")
              .isEqualTo(-1L);
          found = true;
        }
      }
      assertThat(found).as("sheet1.xml 엔트리를 찾지 못함").isTrue();
    }
  }

  /**
   * 핵심 회귀 테스트: data-descriptor(size=-1) 대용량 xlsx를 {@code IOUtils.setByteArrayMaxOverride}로 POI의
   * 엔트리당 허용 크기를 인위적으로 낮춘 상태에서도, File 기반 파싱(현재 {@code parse(InputStream)}이 내부적으로 temp 파일에 스필 후 위임)은
   * 성공적으로 스트리밍됨을 검증한다.
   *
   * <p>대조군으로 같은 픽스처를 {@code OPCPackage.open(InputStream)}으로 직접 열면 실패한다는 것도 함께 확인한다 — InputStream 기반
   * 오픈은 엔트리 크기를 모르므로 POI가 기본 상한(100MB)을 기준으로 판단하는데, override가 그보다 작게 설정되면 실제 데이터 크기와 무관하게 즉시 실패한다.
   * 반면 File 기반 오픈은 ZIP 중앙 디렉터리에서 엔트리 크기를 확정적으로 읽는 완전히 다른 코드 경로(ZipFileZipEntrySource)를 사용하므로 이
   * override의 영향을 받지 않는다.
   */
  @Test
  void large_data_descriptor_xlsx_streams_via_file_based_path_even_with_low_byte_array_override(
      @TempDir File tempDir) throws Exception {
    File fixture = new File(tempDir, "large-data-descriptor.xlsx");
    writeLargeDataDescriptorFixture(fixture);
    assertSheetEntryIsDataDescriptor(fixture);

    // 대용량 파일에서 POI가 메모리에 허용할 최대 엔트리 크기를 1MB로 인위적으로 낮춘다.
    // (전역 static 상태이므로 반드시 finally에서 원복한다.)
    IOUtils.setByteArrayMaxOverride(1_000_000);
    try {
      // 대조군: InputStream 기반 직접 오픈은 엔트리 크기 미상(size=-1) + 낮은 override 조합으로 실패한다.
      assertThatThrownBy(
              () -> {
                try (OPCPackage pkg = OPCPackage.open(new FileInputStream(fixture))) {
                  // no-op — 오픈 자체에서 실패해야 함
                }
              })
          .isInstanceOf(Throwable.class);

      // 본 검증: parse(InputStream)은 내부적으로 temp 파일에 스필한 뒤 File 기반 랜덤액세스로 열므로
      // 동일한 override 하에서도 성공적으로 전체 행을 스트리밍한다.
      List<List<String>> rows = new ArrayList<>();
      ExcelStreamingParser.parse(
          new FileInputStream(fixture),
          (idx, cells) -> {
            rows.add(cells);
            return true;
          });

      assertThat(rows).hasSize(LARGE_ROW_COUNT + 1); // 헤더 포함
      assertThat(rows.get(0))
          .containsExactly(
              "col0", "col1", "col2", "col3", "col4", "col5", "col6", "col7", "col8", "col9");
    } finally {
      // 전역 static 상태 원복 — 다른 테스트에 영향(cross-test pollution)을 주지 않도록 필수
      IOUtils.setByteArrayMaxOverride(-1);
    }
  }

  /**
   * File 기반 core 진입점({@code parse(File, RowConsumer)})이 첫 행에서 early-exit 하면 대용량 파일을 전량 읽지 않고 즉시
   * 중단됨을 검증한다(스트리밍 특성 보존 확인).
   */
  @Test
  void file_based_parse_stops_immediately_on_early_exit_for_large_fixture(@TempDir File tempDir)
      throws Exception {
    File fixture = new File(tempDir, "large-early-exit.xlsx");
    writeLargeDataDescriptorFixture(fixture);

    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        fixture,
        (idx, cells) -> {
          rows.add(cells);
          return false; // 첫 행만 받고 즉시 중단
        });

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsExactly(
            "col0", "col1", "col2", "col3", "col4", "col5", "col6", "col7", "col8", "col9");
  }

  /** File 기반 core 진입점이 소규모 XLSX도 기존과 동일하게 파싱함을 확인한다(회귀 방지). */
  @Test
  void file_based_parse_matches_existing_inputstream_result_for_small_xlsx(@TempDir File tempDir)
      throws Exception {
    byte[] data = buildXlsx();
    File file = new File(tempDir, "small.xlsx");
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(data);
    }

    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        file,
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsExactly("name", "age");
    assertThat(rows.get(1)).containsExactly("alice", "30");
    assertThat(rows.get(2)).containsExactly("bob", "25");
  }

  /** 콜백 예외 전파 확인용 표식 예외 — 파서가 이를 「파일을 읽을 수 없음」으로 바꾸면 안 된다(WD-204). */
  private static final class ConsumerBoom extends RuntimeException {}

  /**
   * RowConsumer 가 던진 예외(임포트 잡의 fail-fast 검증 중단·DB 오류 등)는 파일 형식 오류로 바뀌지 않고 같은 인스턴스 그대로 전파돼야 한다
   * (WD-204). XLSX(SAX 경로)·XLS(HSSF 이벤트 경로) 둘 다 확인한다.
   */
  @Test
  void consumer_exception_propagates_unchanged_for_xlsx_and_xls(@TempDir File tempDir)
      throws Exception {
    for (byte[] data : List.of(buildXlsx(), buildXls())) {
      File f = new File(tempDir, "boom-" + System.nanoTime() + ".bin");
      try (FileOutputStream out = new FileOutputStream(f)) {
        out.write(data);
      }
      ConsumerBoom boom = new ConsumerBoom();
      assertThatThrownBy(
              () ->
                  ExcelStreamingParser.parse(
                      f,
                      (idx, cells) -> {
                        throw boom;
                      }))
          .isSameAs(boom);
    }
  }

  // ---------------------------------------------------------------------
  // WD-252: 셀 값이 경고 없이 바뀌지 않는다
  // ---------------------------------------------------------------------

  /** 2025-01-21 의 엑셀 1900 체계 일련값. */
  private static final double SERIAL_2025_01_21 = 45678d;

  /**
   * WD-252 재현 파일과 같은 셀 종류를 한 행에 담는다: 큰 수·작은 수·소수·부동소수 수식·오류 셀·오류 수식·"ERROR:" 글자 문자열·h:mm 시각·날짜·숨은
   * 시각 날짜·날짜시각.
   */
  private static void fillCellTypes(Workbook wb, boolean evaluate) {
    Sheet sheet = wb.createSheet("data");
    CreationHelper helper = wb.getCreationHelper();
    CellStyle time = wb.createCellStyle();
    time.setDataFormat(helper.createDataFormat().getFormat("h:mm"));
    CellStyle date = wb.createCellStyle();
    date.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
    CellStyle dateTime = wb.createCellStyle();
    dateTime.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));

    String[] headers = {
      "big",
      "neg",
      "dec",
      "tiny",
      "f_dec",
      "err",
      "f_err",
      "err_txt",
      "time",
      "d",
      "d_hidden",
      "ts",
      "long"
    };
    Row h = sheet.createRow(0);
    for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
    Row r = sheet.createRow(1);
    r.createCell(0).setCellValue(1e20);
    r.createCell(1).setCellValue(-1e19);
    r.createCell(2).setCellValue(12345678.5);
    r.createCell(3).setCellValue(0.00001);
    r.createCell(4).setCellFormula("0.1+0.2");
    r.createCell(5).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.DIV0.getCode());
    r.createCell(6).setCellFormula("1/0");
    r.createCell(7).setCellValue("ERROR:#DIV/0!");
    var t = r.createCell(8);
    t.setCellValue(0.5);
    t.setCellStyle(time);
    var d = r.createCell(9);
    d.setCellValue(SERIAL_2025_01_21);
    d.setCellStyle(date);
    var dh = r.createCell(10);
    dh.setCellValue(SERIAL_2025_01_21 + 0.25);
    dh.setCellStyle(date);
    var ts = r.createCell(11);
    ts.setCellValue(SERIAL_2025_01_21 + 0.5);
    ts.setCellStyle(dateTime);
    r.createCell(12).setCellValue(1234567890123456d);
    if (evaluate) {
      helper.createFormulaEvaluator().evaluateAll();
    }
  }

  private static List<List<String>> parseAll(byte[] data) throws Exception {
    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        new ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });
    return rows;
  }

  private static byte[] write(Workbook wb) throws Exception {
    try (wb;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      wb.write(out);
      return out.toByteArray();
    }
  }

  private static void assertCellTypesRow(List<String> row) {
    assertThat(row.get(0)).isEqualTo("100000000000000000000");
    assertThat(row.get(1)).isEqualTo("-10000000000000000000");
    assertThat(row.get(2)).isEqualTo("12345678.5");
    assertThat(row.get(3)).isEqualTo("0.00001");
    assertThat(row.get(4)).isEqualTo("0.3");
    assertThat(LegacyExcelDataFormatter.errorCodeOf(row.get(5))).isEqualTo("#DIV/0!");
    assertThat(LegacyExcelDataFormatter.errorCodeOf(row.get(6))).isEqualTo("#DIV/0!");
    // 같은 글자를 입력한 문자열 셀은 오류 셀이 아니다
    assertThat(row.get(7)).isEqualTo("ERROR:#DIV/0!");
    assertThat(row.get(8)).isEqualTo("12:00:00");
    assertThat(row.get(9)).isEqualTo("2025-01-21");
    assertThat(row.get(10)).isEqualTo("2025-01-21 06:00:00");
    assertThat(row.get(11)).isEqualTo("2025-01-21 12:00:00");
    assertThat(row.get(12)).isEqualTo("1234567890123456");
  }

  @Test
  void xlsx_cell_types_are_kept_as_excel_shows_them() throws Exception {
    XSSFWorkbook wb = new XSSFWorkbook();
    fillCellTypes(wb, true);
    List<List<String>> rows = parseAll(write(wb));
    assertThat(rows).hasSize(2);
    assertCellTypesRow(rows.get(1));
  }

  @Test
  void xls_cell_types_are_kept_as_excel_shows_them() throws Exception {
    org.apache.poi.hssf.usermodel.HSSFWorkbook wb =
        new org.apache.poi.hssf.usermodel.HSSFWorkbook();
    fillCellTypes(wb, true);
    List<List<String>> rows = parseAll(write(wb));
    assertThat(rows).hasSize(2);
    assertCellTypesRow(rows.get(1));
  }

  /** XLS 불리언 결과 수식은 예전에 캐시값 0.0 을 숫자로 읽어 "0" 이 됐다. */
  @Test
  void xls_boolean_formula_result_is_boolean() throws Exception {
    org.apache.poi.hssf.usermodel.HSSFWorkbook wb =
        new org.apache.poi.hssf.usermodel.HSSFWorkbook();
    Sheet sheet = wb.createSheet("data");
    sheet.createRow(0).createCell(0).setCellValue("b");
    sheet.createRow(1).createCell(0).setCellFormula("1=1");
    wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
    List<List<String>> rows = parseAll(write(wb));
    assertThat(rows.get(1)).containsExactly("true");
  }

  /** JVM 기본 시간대를 바꿔도 같은 값이 나와야 한다(공유 컨텍스트 없는 단위 테스트라 잠시 바꾸고 복원한다). */
  @Test
  void xlsx_and_xls_results_do_not_depend_on_default_time_zone() throws Exception {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : new String[] {"Asia/Seoul", "UTC", "America/New_York"}) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        XSSFWorkbook x = new XSSFWorkbook();
        fillCellTypes(x, true);
        assertCellTypesRow(parseAll(write(x)).get(1));
        org.apache.poi.hssf.usermodel.HSSFWorkbook b =
            new org.apache.poi.hssf.usermodel.HSSFWorkbook();
        fillCellTypes(b, true);
        assertCellTypesRow(parseAll(write(b)).get(1));
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  /** workbookPr date1904="1" 워크북의 날짜가 1900 체계로 읽혀 1,462일 앞당겨지지 않는다. */
  @Test
  void xlsx_date1904_workbook_dates_are_not_shifted() throws Exception {
    XSSFWorkbook wb = new XSSFWorkbook();
    var pr =
        wb.getCTWorkbook().isSetWorkbookPr()
            ? wb.getCTWorkbook().getWorkbookPr()
            : wb.getCTWorkbook().addNewWorkbookPr();
    pr.setDate1904(true);
    Sheet sheet = wb.createSheet("data");
    CellStyle date = wb.createCellStyle();
    date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
    sheet.createRow(0).createCell(0).setCellValue("d");
    var c = sheet.createRow(1).createCell(0);
    c.setCellValue(SERIAL_2025_01_21 - 1462); // 1904 체계 일련값
    c.setCellStyle(date);
    List<List<String>> rows = parseAll(write(wb));
    assertThat(rows.get(1)).containsExactly("2025-01-21");
  }

  @Test
  void xls_date1904_workbook_dates_are_not_shifted() throws Exception {
    org.apache.poi.hssf.usermodel.HSSFWorkbook wb =
        new org.apache.poi.hssf.usermodel.HSSFWorkbook();
    org.apache.poi.hssf.record.DateWindow1904Record rec =
        (org.apache.poi.hssf.record.DateWindow1904Record)
            wb.getInternalWorkbook()
                .findFirstRecordBySid(org.apache.poi.hssf.record.DateWindow1904Record.sid);
    rec.setWindowing((short) 1);
    Sheet sheet = wb.createSheet("data");
    CellStyle date = wb.createCellStyle();
    date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
    sheet.createRow(0).createCell(0).setCellValue("d");
    var c = sheet.createRow(1).createCell(0);
    c.setCellValue(SERIAL_2025_01_21 - 1462);
    c.setCellStyle(date);
    List<List<String>> rows = parseAll(write(wb));
    assertThat(rows.get(1)).containsExactly("2025-01-21");
  }
}
