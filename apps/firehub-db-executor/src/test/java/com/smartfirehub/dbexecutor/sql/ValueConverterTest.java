package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 결과 값 변환 규칙을 api 와 공유하는 고정값({@code tibero-executor-vectors.json} 의 {@code columnNames}·{@code
 * valueConversions})으로 고정한다. JSON 텍스트까지 비교하므로 응답 직렬화 설정(BigDecimal 평문 출력)도 함께 검증한다.
 */
class ValueConverterTest {

  private static final JsonNode FIXTURE = load();

  /** 응답과 같은 직렬화: BigDecimal 을 지수 표기 없이 쓴다(application.yml 의 write-bigdecimal-as-plain). */
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(
              Path.of("../firehub-api/src/test/resources/fixtures/tibero-executor-vectors.json")
                  .toFile());
    } catch (Exception e) {
      throw new IllegalStateException("Tibero executor 벡터 고정값을 읽지 못했다", e);
    }
  }

  @Test
  void columnNames_matchSharedVectors() {
    JsonNode cases = FIXTURE.get("columnNames");
    assertThat(cases.size()).isPositive();
    for (JsonNode c : cases) {
      assertThat(ValueConverter.columnName(c.get("in").asText()))
          .as(c.get("in").asText())
          .isEqualTo(c.get("out").asText());
    }
  }

  @Test
  void valueConversions_matchSharedVectors() throws Exception {
    JsonNode cases = FIXTURE.get("valueConversions");
    assertThat(cases.size()).isPositive();
    for (JsonNode c : cases) {
      String in = c.get("in").asText();
      Object converted =
          switch (c.get("kind").asText()) {
            case "number" ->
                ValueConverter.number(
                    new BigDecimal(in), c.get("precision").asInt(), c.get("scale").asInt());
            case "timestamp" -> ValueConverter.timestamp(LocalDateTime.parse(in));
            default -> throw new IllegalArgumentException(c.toString());
          };
      assertThat(JSON.writeValueAsString(converted))
          .as(c.toString())
          .isEqualTo(c.get("json").asText());
    }
  }

  @Test
  void integralNumbers_becomeLongWhenTheyFit() {
    assertThat(ValueConverter.number(new BigDecimal("42"), 19, 0)).isEqualTo(42L);
    assertThat(ValueConverter.number(new BigDecimal("100"), 0, -127)).isEqualTo(100L);
    assertThat(ValueConverter.number(new BigDecimal("123456789012345678901234567890"), 38, 0))
        .isInstanceOf(BigDecimal.class);
    assertThat(ValueConverter.number(null, 10, 2)).isNull();
  }

  /** JDBC 타입별로 알맞은 getter 를 쓰는지(NUMBER·DATE·TIMESTAMP·CLOB·문자열·NULL). */
  @Test
  void read_dispatchesByJdbcType() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getPrecision(1)).thenReturn(18);
    when(md.getScale(1)).thenReturn(6);
    when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("2.5"));
    when(md.getColumnType(2)).thenReturn(Types.TIMESTAMP);
    when(rs.getTimestamp(2)).thenReturn(Timestamp.valueOf("2026-01-02 03:04:05"));
    when(md.getColumnType(3)).thenReturn(Types.DATE);
    when(rs.getTimestamp(3)).thenReturn(Timestamp.valueOf("2026-01-02 00:00:00"));
    when(md.getColumnType(4)).thenReturn(Types.CLOB);
    Clob clob = mock(Clob.class);
    when(clob.length()).thenReturn(3L);
    when(clob.getSubString(1, 3)).thenReturn("abc");
    when(rs.getClob(4)).thenReturn(clob);
    when(md.getColumnType(5)).thenReturn(Types.VARCHAR);
    when(rs.getString(5)).thenReturn("x");
    when(md.getColumnType(6)).thenReturn(Types.TIMESTAMP);
    when(rs.getTimestamp(6)).thenReturn(null);

    assertThat(ValueConverter.read(rs, 1, md)).isEqualTo(new BigDecimal("2.500000"));
    assertThat(ValueConverter.read(rs, 2, md)).isEqualTo("2026-01-02T03:04:05");
    assertThat(ValueConverter.read(rs, 3, md)).isEqualTo("2026-01-02T00:00:00");
    assertThat(ValueConverter.read(rs, 4, md)).isEqualTo("abc");
    assertThat(ValueConverter.read(rs, 5, md)).isEqualTo("x");
    assertThat(ValueConverter.read(rs, 6, md)).isNull();
  }
}
