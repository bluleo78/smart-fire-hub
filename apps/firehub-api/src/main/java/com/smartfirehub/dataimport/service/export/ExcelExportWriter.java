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
 *   <li>Excel 셀 한도(32,767자)를 넘는 문자열은 잘라 쓰고 잘렸다는 표시를 붙인다(WD-248, {@link #fitCellText})
 *   <li>쓰기 중 예외가 나면 close() 에서 통합 문서를 출력 스트림에 쓰지 않는다(WD-248) — 동기 응답이 반쯤 쓰인 채 커밋되지 않게
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

  /** 헤더·행 쓰기 중 예외가 났는지 — 났으면 close() 가 부분 통합 문서를 출력 스트림에 쓰지 않는다(WD-248). */
  private boolean failed = false;

  /** Excel 이 셀 하나에 담을 수 있는 최대 글자 수(UTF-16 단위, POI 가 {@code String.length()} 로 검사한다). */
  static final int MAX_CELL_TEXT_LENGTH = 32_767;

  /** 한도를 넘어 잘린 셀 끝에 붙이는 표시 — 전체 값은 CSV 로 받을 수 있음을 알린다. */
  static final String TRUNCATED_SUFFIX = "…(잘림 — 전체 값은 CSV 로 내보내세요)";

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
    try {
      writeHeaderCells(displayNames);
    } catch (RuntimeException e) {
      failed = true;
      throw e;
    }
  }

  private void writeHeaderCells(List<String> displayNames) {
    SXSSFRow row = sheet.createRow(currentRow++);
    CellStyle bold = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    bold.setFont(font);
    for (int i = 0; i < displayNames.size(); i++) {
      SXSSFCell cell = row.createCell(i);
      setText(cell, displayNames.get(i));
      cell.setCellStyle(bold);
    }
  }

  @Override
  public void writeRow(Object[] values) {
    try {
      SXSSFRow row = sheet.createRow(currentRow++);
      for (int i = 0; i < values.length; i++) {
        writeCell(row.createCell(i), values[i]);
      }
    } catch (RuntimeException e) {
      // 실패 표시 후 그대로 던진다 — 호출자(try-with-resources)의 close() 가 부분 통합 문서를 응답에 쓰지 않게 한다
      failed = true;
      throw e;
    }
  }

  /** 문자열 셀은 모두 여기로 쓴다 — Excel 셀 한도를 넘는 값을 잘라 POI 예외를 막는다(WD-248). */
  private static void setText(SXSSFCell cell, String text) {
    cell.setCellValue(fitCellText(text));
  }

  /**
   * Excel 셀 한도(32,767자) 안으로 문자열을 맞춘다(WD-248).
   *
   * <p>왜: POI 는 한도를 넘는 문자열에 IllegalArgumentException 을 던진다. 동기 내보내기는 응답이 중간에 끊기고 비동기는 실패했다. 넘는 값은
   * 앞부분만 남기고 {@link #TRUNCATED_SUFFIX} 를 붙여 합쳐 한도 이하로 만든다. 자르는 자리가 서로게이트 쌍(이모지 등) 한가운데면 한 글자 앞에서 잘라
   * 깨진 문자를 남기지 않는다.
   */
  static String fitCellText(String text) {
    if (text == null || text.length() <= MAX_CELL_TEXT_LENGTH) {
      return text;
    }
    int cut = MAX_CELL_TEXT_LENGTH - TRUNCATED_SUFFIX.length();
    if (Character.isHighSurrogate(text.charAt(cut - 1))) {
      cut--;
    }
    return text.substring(0, cut) + TRUNCATED_SUFFIX;
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
      setText(cell, ExportValues.text(value));
    }
  }

  private void writeDate(SXSSFCell cell, LocalDate date, Object original) {
    if (!inExcelDateRange(date)) {
      setText(cell, ExportValues.text(original));
      return;
    }
    cell.setCellValue(date);
    cell.setCellStyle(dateStyle);
  }

  private void writeDateTime(SXSSFCell cell, LocalDateTime dateTime, Object original) {
    // Excel 날짜 셀은 밀리초까지만 담는다 — 더 잘게 나뉜 값은 문자열로 남겨 값이 바뀌지 않게 한다
    if (!inExcelDateRange(dateTime.toLocalDate()) || dateTime.getNano() % 1_000_000 != 0) {
      setText(cell, ExportValues.text(original));
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
      // 쓰기 중 실패했으면 부분 통합 문서를 쓰지 않는다 — 동기 응답이 200 으로 커밋된 뒤 끊기는 대신 예외가 커밋 전에 올라간다
      if (!failed) {
        workbook.write(outputStream);
      }
    } finally {
      workbook.dispose();
      workbook.close();
    }
  }
}
