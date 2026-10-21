package com.smartfirehub.dataimport.service.export;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.util.JsSafeNumbers;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON (RFC 7946) ExportWriter.
 *
 * <ul>
 *   <li>FeatureCollection 형식
 *   <li>GEOMETRY 컬럼 → geometry 필드, 나머지 → properties
 *   <li>스트리밍: Jackson JsonGenerator 사용
 *   <li>생성자에서 geometryColumnName 지정
 * </ul>
 */
public class GeoJsonExportWriter implements ExportWriter {

  private final JsonGenerator generator;
  private final String geometryColumnName;
  private List<String> columnNames;

  public GeoJsonExportWriter(OutputStream outputStream, String geometryColumnName)
      throws IOException {
    this.geometryColumnName = geometryColumnName;
    ObjectMapper mapper = new ObjectMapper();
    this.generator = mapper.getFactory().createGenerator(outputStream, JsonEncoding.UTF8);
    generator.writeStartObject();
    generator.writeStringField("type", "FeatureCollection");
    generator.writeArrayFieldStart("features");
  }

  @Override
  public void writeHeader(List<String> displayNames) {
    this.columnNames = new ArrayList<>(displayNames);
  }

  @Override
  public void writeRow(Object[] values) throws IOException {
    generator.writeStartObject();
    generator.writeStringField("type", "Feature");

    // geometry
    int geomIdx = columnNames.indexOf(geometryColumnName);
    String geometry = geomIdx >= 0 && values[geomIdx] != null ? values[geomIdx].toString() : null;
    if (geometry != null && !geometry.isEmpty()) {
      generator.writeFieldName("geometry");
      generator.writeRawValue(geometry);
    } else {
      generator.writeNullField("geometry");
    }

    // properties
    generator.writeObjectFieldStart("properties");
    for (int i = 0; i < columnNames.size(); i++) {
      if (i == geomIdx) continue;
      generator.writeFieldName(columnNames.get(i));
      writePropertyValue(values[i]);
    }
    generator.writeEndObject();

    generator.writeEndObject();
  }

  /**
   * 속성 값 하나를 JSON 타입을 살려 쓴다(WD-159). 예전에는 모두 문자열로 쓰고 null 을 {@code ""} 로 바꿔 QGIS 등에서 숫자 칸으로 쓸 수 없었고
   * 빈 값과 빈 문자열을 구분할 수 없었다.
   *
   * <ul>
   *   <li>null → JSON null, 참거짓 → JSON boolean
   *   <li>정수 → JSON 숫자. 단 ±(2^53-1) 밖의 정수와 double 로 깎이는 소수는 화면 API(WD-101)와 같이 문자열 — JSON 소비자 대부분이
   *       숫자를 double 로 읽기 때문이다. 소수는 끝자리 0 을 지운 평문 숫자({@code 1E+3} 같은 지수 표기 없음)
   *   <li>날짜·시각·문자열 → {@link ExportValues#text} 문자열
   * </ul>
   */
  private void writePropertyValue(Object value) throws IOException {
    if (value == null) {
      generator.writeNull();
    } else if (value instanceof Boolean b) {
      generator.writeBoolean(b);
    } else if (value instanceof BigDecimal decimal) {
      if (JsSafeNumbers.isLossyDecimal(decimal)) {
        generator.writeString(JsSafeNumbers.plainDecimal(decimal));
      } else {
        generator.writeNumber(JsSafeNumbers.plainDecimal(decimal));
      }
    } else if (value instanceof Long || value instanceof BigInteger) {
      if (JsSafeNumbers.isUnsafeInteger(value)) {
        generator.writeString(value.toString());
      } else {
        generator.writeNumber(value.toString());
      }
    } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
      generator.writeNumber(((Number) value).longValue());
    } else if (value instanceof Double d && !d.isNaN() && !d.isInfinite()) {
      generator.writeNumber(d);
    } else if (value instanceof Float f && !f.isNaN() && !f.isInfinite()) {
      generator.writeNumber(f);
    } else {
      generator.writeString(ExportValues.text(value));
    }
  }

  @Override
  public void close() throws IOException {
    generator.writeEndArray();
    generator.writeEndObject();
    generator.close();
  }
}
