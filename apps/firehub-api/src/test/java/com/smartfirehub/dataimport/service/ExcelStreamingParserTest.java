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
        null,
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
        null,
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
        null,
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
          null,
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
        null,
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
        null,
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
        null,
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
        null,
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
        null,
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
          null,
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
        null,
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
        null,
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
                      null,
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
        null,
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

  // ---------------------------------------------------------------------
  // 시트 고르기 (WD-253) — 기본은 첫 번째 보이는 시트, 이름을 주면 그 시트
  // ---------------------------------------------------------------------

  /**
   * 시트마다 헤더 "sheet" 와 값 한 행(시트 이름)을 넣은 워크북을 만든다. 읽은 값으로 어느 시트를 읽었는지 확인한다.
   *
   * <p>엑셀은 활성(선택된) 시트를 숨길 수 없으므로, 첫 번째 보이는 시트를 활성·선택 탭으로 옮긴 뒤 숨긴다.
   */
  private static byte[] multiSheet(
      Workbook wb, List<String> names, List<org.apache.poi.ss.usermodel.SheetVisibility> vis)
      throws Exception {
    for (String n : names) {
      Sheet s = wb.createSheet(n);
      s.createRow(0).createCell(0).setCellValue("sheet");
      s.createRow(1).createCell(0).setCellValue(n);
    }
    int firstVisible = vis.indexOf(org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE);
    if (firstVisible >= 0) {
      wb.getSheetAt(0).setSelected(false);
      wb.setActiveSheet(firstVisible);
      wb.setSelectedTab(firstVisible);
    }
    for (int i = 0; i < vis.size(); i++) {
      wb.setSheetVisibility(i, vis.get(i));
    }
    return write(wb);
  }

  private static final org.apache.poi.ss.usermodel.SheetVisibility VISIBLE =
      org.apache.poi.ss.usermodel.SheetVisibility.VISIBLE;
  private static final org.apache.poi.ss.usermodel.SheetVisibility HIDDEN =
      org.apache.poi.ss.usermodel.SheetVisibility.HIDDEN;
  private static final org.apache.poi.ss.usermodel.SheetVisibility VERY_HIDDEN =
      org.apache.poi.ss.usermodel.SheetVisibility.VERY_HIDDEN;

  /** xlsx·xls 두 형식으로 같은 시트 구성을 만든다. */
  private static List<byte[]> bothFormats(
      List<String> names, List<org.apache.poi.ss.usermodel.SheetVisibility> vis) throws Exception {
    return List.of(
        multiSheet(new XSSFWorkbook(), names, vis),
        multiSheet(new org.apache.poi.hssf.usermodel.HSSFWorkbook(), names, vis));
  }

  /** 지정한 시트(null = 기본)를 읽어 데이터 행의 첫 값(= 읽은 시트 이름)을 돌려준다. */
  private static String readSheetMarker(byte[] data, String sheetName) throws Exception {
    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        new ByteArrayInputStream(data),
        sheetName,
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsExactly("sheet");
    return rows.get(1).get(0);
  }

  private static File toFile(File dir, byte[] data) throws Exception {
    File f = File.createTempFile("sheets-", ".bin", dir);
    java.nio.file.Files.write(f.toPath(), data);
    return f;
  }

  @Test
  void default_skips_hidden_first_sheet_for_xlsx_and_xls() throws Exception {
    // 예전엔 숨긴 첫 시트를 읽었다 — 화면에 보이는 첫 시트를 읽어야 한다
    for (byte[] data : bothFormats(List.of("숨김", "데이터"), List.of(HIDDEN, VISIBLE))) {
      assertThat(readSheetMarker(data, null)).isEqualTo("데이터");
    }
  }

  @Test
  void default_skips_very_hidden_sheets_for_xlsx_and_xls() throws Exception {
    for (byte[] data :
        bothFormats(
            List.of("VH", "H", "보임", "뒤"), List.of(VERY_HIDDEN, HIDDEN, VISIBLE, VISIBLE))) {
      assertThat(readSheetMarker(data, null)).isEqualTo("보임");
    }
  }

  @Test
  void explicit_sheet_name_reads_that_sheet_even_if_hidden() throws Exception {
    for (byte[] data : bothFormats(List.of("첫째", "숨김", "셋째"), List.of(VISIBLE, HIDDEN, VISIBLE))) {
      assertThat(readSheetMarker(data, "숨김")).isEqualTo("숨김");
      assertThat(readSheetMarker(data, "셋째")).isEqualTo("셋째");
      // 엑셀 시트 이름은 대소문자를 구별하지 않는다 — 앞뒤 공백도 무시한다
      assertThat(readSheetMarker(data, " 첫째 ")).isEqualTo("첫째");
    }
  }

  @Test
  void sheet_name_match_is_case_insensitive() throws Exception {
    for (byte[] data : bothFormats(List.of("Data", "Other"), List.of(VISIBLE, VISIBLE))) {
      assertThat(readSheetMarker(data, "other")).isEqualTo("Other");
    }
  }

  @Test
  void unknown_sheet_name_fails_with_korean_message_listing_sheets() throws Exception {
    for (byte[] data : bothFormats(List.of("숨김", "데이터"), List.of(HIDDEN, VISIBLE))) {
      assertThatThrownBy(() -> readSheetMarker(data, "없는시트"))
          .isInstanceOf(com.smartfirehub.dataimport.exception.ExcelSheetSelectionException.class)
          .isInstanceOf(com.smartfirehub.dataimport.exception.FileUnreadableException.class)
          .hasMessage("'없는시트' 시트를 찾을 수 없습니다. 파일에 있는 시트: 숨김(숨김), 데이터");
    }
  }

  @Test
  void all_hidden_without_sheet_name_fails_but_explicit_name_works() throws Exception {
    for (byte[] data : bothFormats(List.of("A", "B"), List.of(HIDDEN, VERY_HIDDEN))) {
      assertThatThrownBy(() -> readSheetMarker(data, null))
          .isInstanceOf(com.smartfirehub.dataimport.exception.ExcelSheetSelectionException.class)
          .hasMessageStartingWith("보이는 시트가 없습니다.")
          .hasMessageEndingWith("파일에 있는 시트: A(숨김), B(숨김)");
      assertThat(readSheetMarker(data, "B")).isEqualTo("B");
    }
  }

  @Test
  void describe_sheets_lists_sheets_in_tab_order_with_hidden_flags(@TempDir File dir)
      throws Exception {
    for (byte[] data :
        bothFormats(
            List.of("숨김", "보임1", "매우숨김", "보임2"), List.of(HIDDEN, VISIBLE, VERY_HIDDEN, VISIBLE))) {
      File f = toFile(dir, data);
      ExcelStreamingParser.SheetSelection sel = ExcelStreamingParser.describeSheets(f, null);
      assertThat(sel.sheets())
          .containsExactly(
              new com.smartfirehub.dataimport.dto.ExcelSheetInfo("숨김", true),
              new com.smartfirehub.dataimport.dto.ExcelSheetInfo("보임1", false),
              new com.smartfirehub.dataimport.dto.ExcelSheetInfo("매우숨김", true),
              new com.smartfirehub.dataimport.dto.ExcelSheetInfo("보임2", false));
      assertThat(sel.selectedName()).isEqualTo("보임1");
      assertThat(ExcelStreamingParser.describeSheets(f, "매우숨김").selectedName()).isEqualTo("매우숨김");
      assertThatThrownBy(() -> ExcelStreamingParser.describeSheets(f, "nope"))
          .isInstanceOf(com.smartfirehub.dataimport.exception.ExcelSheetSelectionException.class);
    }
  }

  @Test
  void describe_sheets_of_corrupt_file_is_korean_unreadable_error(@TempDir File dir)
      throws Exception {
    File f = toFile(dir, "not an excel file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(() -> ExcelStreamingParser.describeSheets(f, null))
        .isInstanceOf(com.smartfirehub.dataimport.exception.FileUnreadableException.class)
        .hasMessage(com.smartfirehub.dataimport.exception.FileUnreadableException.EXCEL_UNREADABLE);
  }

  /** 시트 고르기 규칙 검증용 시트 목록 항목 — 형식별 위치(relId·substream)는 고르기에 쓰이지 않아 비워 둔다. */
  private static ExcelStreamingParser.SheetEntry entry(String name, boolean hidden) {
    return new ExcelStreamingParser.SheetEntry(name, hidden, null, -1);
  }

  @Test
  void resolve_sheet_index_rules() {
    var a = entry("A", true);
    var b = entry("B", false);
    assertThat(ExcelStreamingParser.resolveSheetIndex(List.of(a, b), null)).isEqualTo(1);
    assertThat(ExcelStreamingParser.resolveSheetIndex(List.of(a, b), "A")).isZero();
    // 워크시트가 하나도 없으면 읽을 행이 없을 뿐 오류는 아니다(예전 동작 유지)
    assertThat(ExcelStreamingParser.resolveSheetIndex(List.of(), null)).isEqualTo(-1);
    // 너무 긴 이름은 잘라서 되풀이한다
    assertThatThrownBy(() -> ExcelStreamingParser.resolveSheetIndex(List.of(b), "x".repeat(100)))
        .hasMessageStartingWith("'" + "x".repeat(31) + "…' 시트를 찾을 수 없습니다.");
  }

  // ---- XLSB: POI 로 .xlsb 를 쓸 수 없어 workbook.bin 레코드를 직접 만들어 시트 목록 해석을 검증한다 ----

  /** BIFF12 레코드 하나(가변 길이 레코드 번호 + 가변 길이 크기 + 데이터)를 쓴다. */
  private static void xlsbRecord(ByteArrayOutputStream out, int id, byte[] data) {
    if (id < 0x80) {
      out.write(id);
    } else {
      out.write((id & 0x7F) | 0x80);
      out.write(id >> 7);
    }
    int len = data.length;
    do {
      int b = len & 0x7F;
      len >>= 7;
      out.write(len > 0 ? b | 0x80 : b);
    } while (len > 0);
    out.writeBytes(data);
  }

  /** BrtBundleSh 데이터: hsState · iTabID · strRelID(null 이면 0xFFFFFFFF) · strName. */
  private static byte[] bundleSheet(int state, int tabId, String relId, String name) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(le(state));
    out.writeBytes(le(tabId));
    if (relId == null) {
      out.writeBytes(le(0xFFFFFFFF));
    } else {
      out.writeBytes(le(relId.length()));
      out.writeBytes(relId.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    }
    out.writeBytes(le(name.length()));
    out.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    return out.toByteArray();
  }

  private static byte[] le(int v) {
    byte[] b = new byte[4];
    org.apache.poi.util.LittleEndian.putInt(b, 0, v);
    return b;
  }

  @Test
  void xlsb_workbook_records_give_sheet_order_hidden_flags_and_1904() throws Exception {
    ByteArrayOutputStream wb = new ByteArrayOutputStream();
    xlsbRecord(wb, 131, new byte[0]); // BrtBeginBook
    xlsbRecord(wb, 153, le(1)); // BrtWbProp — f1904
    xlsbRecord(wb, 143, new byte[0]); // BrtBeginBundleShs
    xlsbRecord(wb, 156, bundleSheet(1, 1, "rId1", "숨김"));
    xlsbRecord(wb, 156, bundleSheet(0, 2, "rId2", "데이터"));
    xlsbRecord(wb, 156, bundleSheet(2, 3, "rId3", "매우숨김"));
    xlsbRecord(wb, 156, bundleSheet(0, 4, null, "관계없음")); // r:id 없는 시트는 뺀다
    xlsbRecord(wb, 156, bundleSheet(0, 5, "rId5", "차트")); // 워크시트가 아닌 관계는 뺀다
    xlsbRecord(wb, 144, new byte[0]); // BrtEndBundleShs — 여기서 멈춘다
    xlsbRecord(wb, 156, new byte[] {1, 2}); // 멈춘 뒤의 깨진 레코드는 읽지 않는다

    ExcelStreamingParser.WorkbookInfo info =
        ExcelStreamingParser.readXlsbWorkbookRecords(
            new ByteArrayInputStream(wb.toByteArray()), relId -> !"rId5".equals(relId));

    assertThat(info.date1904()).isTrue();
    assertThat(info.sheets())
        .extracting(
            ExcelStreamingParser.SheetEntry::name,
            ExcelStreamingParser.SheetEntry::hidden,
            ExcelStreamingParser.SheetEntry::relId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("숨김", true, "rId1"),
            org.assertj.core.groups.Tuple.tuple("데이터", false, "rId2"),
            org.assertj.core.groups.Tuple.tuple("매우숨김", true, "rId3"));
  }

  /**
   * 시트 정의가 깨지면 파싱 오류다. 그 앞에서 읽은 1904 날짜 체계는 오류에 담겨, 첫 시트로 떨어지는 대체 경로가 날짜를 1,462일 당기지 않는다(PR #792
   * 리뷰).
   */
  @Test
  void xlsb_truncated_bundle_sheet_record_is_a_parse_error_keeping_1904() {
    ByteArrayOutputStream wb = new ByteArrayOutputStream();
    xlsbRecord(wb, 153, le(1)); // BrtWbProp — f1904
    byte[] broken = bundleSheet(0, 1, "rId1", "데이터");
    xlsbRecord(wb, 156, java.util.Arrays.copyOf(broken, broken.length - 4));
    assertThatThrownBy(
            () ->
                ExcelStreamingParser.readXlsbWorkbookRecords(
                    new ByteArrayInputStream(wb.toByteArray()), relId -> true))
        .isInstanceOfSatisfying(
            ExcelStreamingParser.XlsbWorkbookUnreadableException.class,
            e -> assertThat(e.date1904()).isTrue())
        .hasCauseInstanceOf(org.apache.poi.xssf.binary.XSSFBParseException.class);
  }

  // ---- 이름 비교·오류 문구 (코드 리뷰 반영) ----

  @Test
  void loose_match_trims_both_names_and_rejects_ambiguous_matches() {
    var dataWithSpace = entry("Data ", false);
    var other = entry("Other", false);
    // 시트 이름 쪽 공백도 빼고 비교한다 — 'Data' 로 'Data ' 시트를 찾는다
    assertThat(ExcelStreamingParser.resolveSheetIndex(List.of(other, dataWithSpace), "Data"))
        .isEqualTo(1);
    // 느슨한 비교에 두 시트가 걸리면 어느 쪽인지 단정하지 않는다
    var data = entry("Data", false);
    assertThatThrownBy(
            () -> ExcelStreamingParser.resolveSheetIndex(List.of(data, dataWithSpace), "DATA"))
        .isInstanceOf(com.smartfirehub.dataimport.exception.ExcelSheetSelectionException.class)
        .hasMessage(
            "'DATA' 과(와) 이름이 같은 시트가 여러 개입니다(공백·대소문자 차이). 정확한 시트 이름을 지정해 주세요. 파일에 있는 시트: Data,"
                + " Data ");
    // 정확히 같은 이름이 있으면 그 시트다(모호하지 않다)
    assertThat(ExcelStreamingParser.resolveSheetIndex(List.of(data, dataWithSpace), "Data "))
        .isEqualTo(1);
  }

  @Test
  void trailing_space_sheet_name_is_found_in_real_xlsx() throws Exception {
    byte[] data =
        multiSheet(new XSSFWorkbook(), List.of("Data ", "Other"), List.of(VISIBLE, VISIBLE));
    assertThat(readSheetMarker(data, "data")).isEqualTo("Data ");
  }

  @Test
  void error_message_lists_at_most_20_sheets() {
    List<ExcelStreamingParser.SheetEntry> many = new ArrayList<>();
    for (int i = 1; i <= 25; i++) {
      many.add(entry("S" + i, false));
    }
    assertThatThrownBy(() -> ExcelStreamingParser.resolveSheetIndex(many, "없음"))
        .hasMessageEndingWith("S19, S20 외 5개")
        .hasMessageContaining("파일에 있는 시트: S1, S2,")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("S21"));
  }

  // ---- XLSB 실파일: POI 가 .xlsb 를 쓰지 못해 OPC 컨테이너를 직접 짠다 ----

  /** 문자열 셀만 있는 시트 레코드(행 0: "sheet", 행 1: marker). 스타일이 필요 없는 BrtCellSt 만 쓴다. */
  private static byte[] xlsbSheetBin(String marker) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String[] values = {"sheet", marker};
    for (int row = 0; row < values.length; row++) {
      byte[] rowHdr = new byte[17];
      org.apache.poi.util.LittleEndian.putInt(rowHdr, 0, row);
      xlsbRecord(out, 0, rowHdr); // BrtRowHdr
      ByteArrayOutputStream cell = new ByteArrayOutputStream();
      cell.writeBytes(le(0)); // 열 0
      cell.writeBytes(le(0)); // 스타일 0 + 플래그
      cell.writeBytes(le(values[row].length()));
      cell.writeBytes(values[row].getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
      xlsbRecord(out, 6, cell.toByteArray()); // BrtCellSt
    }
    xlsbRecord(out, 146, new byte[0]); // BrtEndSheetData
    return out.toByteArray();
  }

  /**
   * .xlsb 컨테이너를 만든다. 시트 i(0부터)는 관계 rId{i+1} → worksheets/sheet{i+1}.bin 이고 값 행은 markers[i] 다.
   *
   * @param workbookBin workbook.bin 레코드(시트 정의 포함)
   */
  private static byte[] buildXlsb(byte[] workbookBin, List<String> markers) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
      java.util.function.BiConsumer<String, byte[]> put =
          (name, data) -> {
            try {
              zip.putNextEntry(new java.util.zip.ZipEntry(name));
              zip.write(data);
              zip.closeEntry();
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          };
      put.accept(
          "[Content_Types].xml",
          ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
               + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
               + " Extension=\"rels\""
               + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
               + " Extension=\"bin\" ContentType=\"application/vnd.ms-excel.worksheet\"/><Override"
               + " PartName=\"/xl/workbook.bin\""
               + " ContentType=\"application/vnd.ms-excel.sheet.binary.macroEnabled.main\"/>"
               + "</Types>")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      put.accept(
          "_rels/.rels",
          ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
               + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
               + " Id=\"rId1\""
               + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
               + " Target=\"xl/workbook.bin\"/></Relationships>")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder rels =
          new StringBuilder(
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
      for (int i = 0; i < markers.size(); i++) {
        rels.append("<Relationship Id=\"rId")
            .append(i + 1)
            .append(
                "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                    + " Target=\"worksheets/sheet")
            .append(i + 1)
            .append(".bin\"/>");
        put.accept("xl/worksheets/sheet" + (i + 1) + ".bin", xlsbSheetBin(markers.get(i)));
      }
      rels.append("</Relationships>");
      put.accept(
          "xl/_rels/workbook.bin.rels",
          rels.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      put.accept("xl/workbook.bin", workbookBin);
    }
    return bytes.toByteArray();
  }

  @Test
  void xlsb_file_default_skips_hidden_sheet_and_explicit_name_reads_it(@TempDir File dir)
      throws Exception {
    ByteArrayOutputStream wb = new ByteArrayOutputStream();
    xlsbRecord(wb, 143, new byte[0]);
    xlsbRecord(wb, 156, bundleSheet(1, 1, "rId1", "숨김"));
    xlsbRecord(wb, 156, bundleSheet(0, 2, "rId2", "데이터"));
    xlsbRecord(wb, 144, new byte[0]);
    byte[] xlsb = buildXlsb(wb.toByteArray(), List.of("숨김", "데이터"));

    assertThat(readSheetMarker(xlsb, null)).isEqualTo("데이터");
    assertThat(readSheetMarker(xlsb, "숨김")).isEqualTo("숨김");
    ExcelStreamingParser.SheetSelection sel =
        ExcelStreamingParser.describeSheets(toFile(dir, xlsb), null);
    assertThat(sel.selectedName()).isEqualTo("데이터");
    assertThat(sel.sheets())
        .containsExactly(
            new com.smartfirehub.dataimport.dto.ExcelSheetInfo("숨김", true),
            new com.smartfirehub.dataimport.dto.ExcelSheetInfo("데이터", false));
  }

  /**
   * 옛 베타 엑셀이 쓴 시트 정의(앞 8바이트가 더 붙은 BrtBundleSh)라 이 파서의 시트 목록 해석이 실패하는 xlsb. POI 의 시트 반복자는 이 형식을 읽는다.
   * 시트 「첫째」 하나이고 값은 1열(머리 "sheet", 값 "첫째")이다. 가져오기 서비스 테스트도 같은 파일을 쓴다.
   */
  static byte[] xlsbWithUnreadableSheetList() throws Exception {
    ByteArrayOutputStream beta = new ByteArrayOutputStream();
    beta.writeBytes(new byte[8]); // 베타 형식의 알 수 없는 8바이트(iTabID 자리가 0 이라 POI 가 옛 형식으로 다시 읽는다)
    beta.writeBytes(le(1)); // iTabID
    beta.writeBytes(le(4));
    beta.writeBytes("rId1".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    beta.writeBytes(le(2));
    beta.writeBytes("첫째".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    ByteArrayOutputStream wb = new ByteArrayOutputStream();
    xlsbRecord(wb, 156, beta.toByteArray());
    return buildXlsb(wb.toByteArray(), List.of("첫째"));
  }

  /**
   * 시트 목록이 깨진 xlsb 는 시트를 지정하지 않으면 예전처럼 첫 시트를 가져와야 한다(이 기능 전에 가져와지던 파일의 회귀 방지). 미리보기가 돌려준 그 첫 시트
   * 이름을 다시 지정해도(화면·AI 가 검증·가져오기에 되돌려 보낸다) 같은 시트를 읽어야 한다(PR #792 리뷰). 다른 이름은 고를 수 없다는 한국어 오류다.
   */
  @Test
  void xlsb_with_unreadable_sheet_list_falls_back_to_first_sheet(@TempDir File dir)
      throws Exception {
    byte[] xlsb = xlsbWithUnreadableSheetList();

    assertThat(readSheetMarker(xlsb, null)).isEqualTo("첫째");
    ExcelStreamingParser.SheetSelection sel =
        ExcelStreamingParser.describeSheets(toFile(dir, xlsb), null);
    assertThat(sel.selectedName()).isEqualTo("첫째");
    assertThat(sel.sheets())
        .containsExactly(new com.smartfirehub.dataimport.dto.ExcelSheetInfo("첫째", false));

    // 미리보기가 돌려준 이름 그대로 다시 고르면 같은 시트다
    assertThat(ExcelStreamingParser.describeSheets(toFile(dir, xlsb), "첫째").selectedName())
        .isEqualTo("첫째");
    assertThat(readSheetMarker(xlsb, "첫째")).isEqualTo("첫째");

    // 느슨하게만 같은 이름은 보이지 않는 다른 시트일 수 있어 받지 않는다
    assertThatThrownBy(() -> readSheetMarker(xlsb, " 첫째 "))
        .isInstanceOf(com.smartfirehub.dataimport.exception.ExcelSheetSelectionException.class);
    assertThatThrownBy(() -> readSheetMarker(xlsb, "다른시트"))
        .isInstanceOf(com.smartfirehub.dataimport.exception.ExcelSheetSelectionException.class)
        .hasMessage(
            "파일의 시트 목록을 읽을 수 없어 '다른시트' 시트를 고를 수 없습니다. 시트를 지정하지 않으면 첫 번째 시트를 가져옵니다.");
  }
}
