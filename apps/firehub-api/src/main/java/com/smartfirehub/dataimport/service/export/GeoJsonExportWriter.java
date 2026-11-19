package com.smartfirehub.dataimport.service.export;

import com.smartfirehub.global.util.JsSafeNumbers;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

  /**
   * 생성기를 만드는 데만 쓰는 공유 매퍼. 내보내기마다 매퍼를 새로 만들면(설정·캐시 초기화) 헛일이라 한 번만 만든다 — 매퍼는 만든 뒤 스레드 안전하고 생성기는
   * 내보내기마다 따로다. Jackson 3(WD-793): 예전 Jackson 2 new ObjectMapper() 와 같은 기본값에서 출발한다.
   */
  private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

  private final JsonGenerator generator;
  private final String geometryColumnName;
  private List<String> columnNames;

  public GeoJsonExportWriter(OutputStream outputStream, String geometryColumnName)
      throws IOException {
    this.geometryColumnName = geometryColumnName;
    this.generator = unwrapIo(() -> MAPPER.createGenerator(outputStream, JsonEncoding.UTF8));
    unwrapIo(
        () -> {
          generator.writeStartObject();
          generator.writeStringProperty("type", "FeatureCollection");
          generator.writeArrayPropertyStart("features");
          return null;
        });
  }

  /** {@link #unwrapIo} 에 넘기는 생성기 작업. */
  @FunctionalInterface
  private interface GeneratorWork<T> {
    T run();
  }

  /**
   * 생성기 작업을 실행하고, 출력 스트림 I/O 오류는 예전처럼 {@link IOException} 으로 올린다.
   *
   * <p>Jackson 3(WD-793)은 스트림 I/O 오류를 비검사 {@link JacksonIOException} 으로 감싼다. 이 writer 는 {@link
   * ExportWriter} 계약대로 IOException 을 던져 왔고, 동기 내보내기({@code StreamingResponseBody})는 클라이언트 연결 끊김 등을
   * IOException 으로 받으므로 원래 예외로 풀어 같은 경로를 탄다.
   */
  private static <T> T unwrapIo(GeneratorWork<T> work) throws IOException {
    try {
      return work.run();
    } catch (JacksonIOException e) {
      // 원인 없는 JacksonIOException 은 없어야 하지만, 있으면 NPE 대신 그 예외를 IOException 으로 감싸 올린다.
      IOException cause = e.getCause();
      throw cause != null ? cause : new IOException(e.getMessage(), e);
    }
  }

  @Override
  public void writeHeader(List<String> displayNames) {
    this.columnNames = new ArrayList<>(displayNames);
  }

  @Override
  public void writeRow(Object[] values) throws IOException {
    unwrapIo(
        () -> {
          writeFeature(values);
          return null;
        });
  }

  /** 행 하나를 GeoJSON Feature 객체로 쓴다. */
  private void writeFeature(Object[] values) {
    generator.writeStartObject();
    generator.writeStringProperty("type", "Feature");

    // geometry
    int geomIdx = columnNames.indexOf(geometryColumnName);
    String geometry = geomIdx >= 0 && values[geomIdx] != null ? values[geomIdx].toString() : null;
    if (geometry != null && !geometry.isEmpty()) {
      generator.writeName("geometry");
      generator.writeRawValue(geometry);
    } else {
      generator.writeNullProperty("geometry");
    }

    // properties
    generator.writeObjectPropertyStart("properties");
    for (int i = 0; i < columnNames.size(); i++) {
      if (i == geomIdx) continue;
      generator.writeName(columnNames.get(i));
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
  private void writePropertyValue(Object value) {
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
    unwrapIo(
        () -> {
          generator.writeEndArray();
          generator.writeEndObject();
          generator.close();
          return null;
        });
  }
}
