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

  @Override
  public void writeHeader(List<String> displayNames) {
    csvWriter.writeNext(displayNames.toArray(new String[0]));
  }

  @Override
  public void writeRow(String[] values) {
    csvWriter.writeNext(values);
  }

  @Override
  public void close() throws IOException {
    csvWriter.close();
  }
}
