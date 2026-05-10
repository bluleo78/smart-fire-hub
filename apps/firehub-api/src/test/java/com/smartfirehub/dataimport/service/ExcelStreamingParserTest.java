package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

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
  void date_cell_serialized_as_iso_local_date_time() throws Exception {
    byte[] data = buildXlsxWithDate();
    List<List<String>> rows = new ArrayList<>();
    ExcelStreamingParser.parse(
        new ByteArrayInputStream(data),
        (idx, cells) -> {
          rows.add(cells);
          return true;
        });

    assertThat(rows).hasSize(2);
    // 시스템 타임존 의존 — 날짜 패턴만 검증
    assertThat(rows.get(1).get(0)).matches("\\d{4}-\\d{2}-\\d{2}T.*");
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

  /** XLS 날짜 셀이 ISO LocalDateTime 형식 문자열로 직렬화되는지 확인한다. */
  @Test
  void xls_date_cell_serialized_as_iso_local_date_time() throws Exception {
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
    // 시스템 타임존 의존 — 날짜 패턴만 검증
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get(0)).matches("\\d{4}-\\d{2}-\\d{2}T.*");
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
}
