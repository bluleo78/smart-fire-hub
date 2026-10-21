package com.smartfirehub.dataimport.service.export;

import java.io.IOException;
import java.util.List;

public interface ExportWriter extends AutoCloseable {

  void writeHeader(List<String> displayNames) throws IOException;

  /**
   * 데이터 행 하나를 쓴다. 값은 행 조회가 돌려준 Java 값 그대로다(Long·BigDecimal·Boolean·java.sql.Date/Timestamp·String,
   * null) — 형식마다 타입을 살려 쓰기 위해서다(WD-159: Excel 숫자·날짜 셀, GeoJSON 숫자·참거짓·null). CSV 는 호출부가 수식 주입을 중화한
   * 문자열을 넘긴다({@link CsvFormulaGuard}).
   */
  void writeRow(Object[] values) throws IOException;

  @Override
  void close() throws IOException;
}
