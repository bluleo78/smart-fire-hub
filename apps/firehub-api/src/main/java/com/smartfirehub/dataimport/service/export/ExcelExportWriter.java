package com.smartfirehub.dataimport.service.export;

import java.io.IOException;
import java.io.OutputStream;
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
 * </ul>
 */
public class ExcelExportWriter implements ExportWriter {

  private final SXSSFWorkbook workbook;
  private final SXSSFSheet sheet;
  private final OutputStream outputStream;
  private int currentRow = 0;

  public ExcelExportWriter(OutputStream outputStream) {
    this.outputStream = outputStream;
    this.workbook = new SXSSFWorkbook(100);
    this.workbook.setCompressTempFiles(true);
    this.sheet = workbook.createSheet("데이터");
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
  public void writeRow(String[] values) {
    SXSSFRow row = sheet.createRow(currentRow++);
    for (int i = 0; i < values.length; i++) {
      SXSSFCell cell = row.createCell(i);
      cell.setCellValue(values[i] != null ? values[i] : "");
    }
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
