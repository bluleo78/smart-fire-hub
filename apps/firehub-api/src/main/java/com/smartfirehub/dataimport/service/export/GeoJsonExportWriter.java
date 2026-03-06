package com.smartfirehub.dataimport.service.export;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
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
  public void writeRow(String[] values) throws IOException {
    generator.writeStartObject();
    generator.writeStringField("type", "Feature");

    // geometry
    int geomIdx = columnNames.indexOf(geometryColumnName);
    if (geomIdx >= 0 && values[geomIdx] != null && !values[geomIdx].isEmpty()) {
      generator.writeFieldName("geometry");
      generator.writeRawValue(values[geomIdx]);
    } else {
      generator.writeNullField("geometry");
    }

    // properties
    generator.writeObjectFieldStart("properties");
    for (int i = 0; i < columnNames.size(); i++) {
      if (i == geomIdx) continue;
      generator.writeStringField(columnNames.get(i), values[i] != null ? values[i] : "");
    }
    generator.writeEndObject();

    generator.writeEndObject();
  }

  @Override
  public void close() throws IOException {
    generator.writeEndArray();
    generator.writeEndObject();
    generator.close();
  }
}
