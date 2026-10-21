package com.smartfirehub.dataimport.service.export;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.streaming.SXSSFCell;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Excel (.xlsx) ExportWriter.
 *
 * <ul>
 *   <li>SXSSFWorkbook(100) 스트리밍 모드: 메모리에 100행만 유지
 *   <li>setCompressTempFiles(true) 디스크 사용 최소화
 *   <li>close()에서 dispose() 호출 필수
 *   <li>헤더 행 Bold 스타일
 *   <li>시트명: "데이터"
 *   <li>숫자·참거짓·날짜는 해당 셀 형식으로 쓴다(WD-159, {@link #writeCell})
 * </ul>
 */
public class ExcelExportWriter implements ExportWriter {

  private final SXSSFWorkbook workbook;
  private final SXSSFSheet sheet;
  private final OutputStream outputStream;
  private final CellStyle dateStyle;
  private final CellStyle dateTimeStyle;
  private final CellStyle dateTimeMillisStyle;
  private int currentRow = 0;

  /** Excel 날짜 셀로 쓸 수 있는 가장 이른 날짜 — 그 전은 1900 윤년 버그로 하루 어긋나거나 날짜 체계 밖이다. */
  private static final LocalDate EXCEL_MIN_DATE = LocalDate.of(1900, 3, 1);

  public ExcelExportWriter(OutputStream outputStream) {
    this.outputStream = outputStream;
    this.workbook = new SXSSFWorkbook(100);
    this.workbook.setCompressTempFiles(true);
    this.sheet = workbook.createSheet("데이터");
    // 날짜 셀 서식은 통합 문서당 한 번만 만든다 — 셀마다 만들면 5만 행 이상에서 Excel 셀 서식 상한(약 64,000)에 걸린다
    this.dateStyle = dateStyle("yyyy-mm-dd");
    this.dateTimeStyle = dateStyle("yyyy-mm-dd hh:mm:ss");
    this.dateTimeMillisStyle = dateStyle("yyyy-mm-dd hh:mm:ss.000");
  }

  private CellStyle dateStyle(String format) {
    CellStyle style = workbook.createCellStyle();
    style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(format));
    return style;
  }

  @Override
  public void writeHeader(List<String> displayNames) {
    SXSSFRow row = sheet.createRow(currentRow++);
    CellStyle bold = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    bold.setFont(font);
    for (int i = 0; i < displayNames.size(); i++) {
      SXSSFCell cell = row.createCell(i);
      cell.setCellValue(displayNames.get(i));
      cell.setCellStyle(bold);
    }
  }

  @Override
  public void writeRow(Object[] values) {
    SXSSFRow row = sheet.createRow(currentRow++);
    for (int i = 0; i < values.length; i++) {
      writeCell(row.createCell(i), values[i]);
    }
  }

  /**
   * 값의 Java 타입대로 셀을 쓴다(WD-159). 예전에는 모두 문자열 셀이라 「텍스트로 저장된 숫자」 경고가 뜨고 SUM·정렬·날짜 필터가 동작하지 않았다.
   *
   * <ul>
   *   <li>숫자 → 숫자 셀. 유효 숫자 15자리를 넘으면 Excel 이 다른 값으로 보이므로 문자열 셀({@link
   *       ExportValues#fitsExcelNumber})
   *   <li>참거짓 → 참거짓 셀
   *   <li>날짜·시각 → 날짜 셀(yyyy-mm-dd / yyyy-mm-dd hh:mm:ss[.000]). Excel 이 나타낼 수 없는 범위(1900-03-01
   *       전·9999년 뒤)와 밀리초보다 잘게 나뉜 시각은 문자열 셀 — 1900년 전은 Excel 날짜 체계 밖이고, 1900-01~02 는 Excel 의 1900
   *       윤년 버그로 하루 어긋난다
   *   <li>그 밖(문자열·#769 의 PG 원문 날짜·지오메트리) → 문자열 셀({@link ExportValues#text})
   * </ul>
   *
   * <p>XLSX 문자열 셀(inlineStr)은 수식으로 실행되지 않으므로 CSV 수식 중화를 하지 않는다(WD-156).
   */
  private void writeCell(SXSSFCell cell, Object value) {
    if (value == null) {
      cell.setBlank();
    } else if (value instanceof Boolean b) {
      cell.setCellValue(b);
    } else if (value instanceof Number n && ExportValues.fitsExcelNumber(n)) {
      cell.setCellValue(n.doubleValue());
    } else if (value instanceof java.sql.Timestamp ts) {
      writeDateTime(cell, ts.toLocalDateTime(), value);
    } else if (value instanceof LocalDateTime ldt) {
      writeDateTime(cell, ldt, value);
    } else if (value instanceof java.sql.Date d) {
      writeDate(cell, d.toLocalDate(), value);
    } else if (value instanceof LocalDate ld) {
      writeDate(cell, ld, value);
    } else {
      cell.setCellValue(ExportValues.text(value));
    }
  }

  private void writeDate(SXSSFCell cell, LocalDate date, Object original) {
    if (!inExcelDateRange(date)) {
      cell.setCellValue(ExportValues.text(original));
      return;
    }
    cell.setCellValue(date);
    cell.setCellStyle(dateStyle);
  }

  private void writeDateTime(SXSSFCell cell, LocalDateTime dateTime, Object original) {
    // Excel 날짜 셀은 밀리초까지만 담는다 — 더 잘게 나뉜 값은 문자열로 남겨 값이 바뀌지 않게 한다
    if (!inExcelDateRange(dateTime.toLocalDate()) || dateTime.getNano() % 1_000_000 != 0) {
      cell.setCellValue(ExportValues.text(original));
      return;
    }
    cell.setCellValue(dateTime);
    cell.setCellStyle(dateTime.getNano() == 0 ? dateTimeStyle : dateTimeMillisStyle);
  }

  /** Excel 이 날짜 셀로 정확히 나타내는 범위 — 1900-03-01 ~ 9999-12-31. */
  private static boolean inExcelDateRange(LocalDate date) {
    return !date.isBefore(EXCEL_MIN_DATE) && date.getYear() <= 9999;
  }

  @Override
  public void close() throws IOException {
    try {
      workbook.write(outputStream);
    } finally {
      workbook.dispose();
      workbook.close();
    }
  }
}
