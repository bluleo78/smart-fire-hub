package com.smartfirehub.dataimport.service.export;

import com.opencsv.CSVWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV ExportWriter.
 *
 * <ul>
 *   <li>UTF-8 BOM (0xEF 0xBB 0xBF) 출력 — Excel 한글 호환
 *   <li>OpenCSV CSVWriter 사용
 *   <li>RFC 4180 준수
 *   <li>헤더 셀(표시명·쿼리 별칭)은 {@link CsvFormulaGuard} 로 수식 주입을 중화한다(OWASP CSV Injection) — 표시명은 {@code
 *       =} 로 시작할 수 있다. 행 값은 컬럼 타입을 아는 호출부가 중화해서 넘긴다(이 writer 는 String[] 만 받아 타입을 모른다).
 * </ul>
 */
public class CsvExportWriter implements ExportWriter {

  private final CSVWriter csvWriter;

  public CsvExportWriter(OutputStream outputStream) throws IOException {
    outputStream.write(0xEF);
    outputStream.write(0xBB);
    outputStream.write(0xBF);
    this.csvWriter = new CSVWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
  }

  /** 헤더 행을 쓴다. 헤더는 언제나 문자열이므로 모든 셀을 수식 주입 중화한 뒤 쓴다(OWASP CSV Injection). */
  @Override
  public void writeHeader(List<String> displayNames) {
    csvWriter.writeNext(
        displayNames.stream().map(CsvFormulaGuard::neutralize).toArray(String[]::new));
  }

  /** 값을 {@link ExportValues#text} 모양의 문자열로 써서 한 행을 쓴다(호출부가 이미 중화한 문자열은 그대로). */
  @Override
  public void writeRow(Object[] values) {
    String[] cells = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      cells[i] = ExportValues.text(values[i]);
    }
    csvWriter.writeNext(cells);
  }

  @Override
  public void close() throws IOException {
    csvWriter.close();
  }
}
