package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 공용 계약 벡터의 슬롯 스트리밍 모양({@code endpoints.query.streaming}, WD-78)을 db-executor {@link
 * SlotQueryRunner} 에 대조한다. Python 실행기 {@code tests/test_query_stream.py} 도 같은 예시를 읽는다.
 */
class SlotStreamContractVectorsTest {

  private static final JsonNode STREAMING = load();
  private static final ObjectMapper OM =
      new ObjectMapper().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(
              Path.of(
                      "../firehub-api/src/test/resources/fixtures/executor-db-contract-vectors.json")
                  .toFile())
          .get("endpoints")
          .get("query")
          .get("streaming");
    } catch (Exception e) {
      throw new IllegalStateException("실행기 DB 계약 벡터를 읽지 못했다", e);
    }
  }

  private static List<String> keys(JsonNode node) {
    List<String> out = new ArrayList<>();
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      out.add(it.next());
    }
    return out;
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  /** 벡터의 resultRows·failAfterRows 대로 행을 주는 결과 집합(정수 컬럼 ID). */
  private static ResultSet rows(int n, int failAfter) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnLabel(1)).thenReturn("ID");
    when(md.getColumnType(1)).thenReturn(Types.NUMERIC);
    when(md.getPrecision(1)).thenReturn(19);
    when(md.getScale(1)).thenReturn(0);
    AtomicInteger current = new AtomicInteger();
    when(rs.next())
        .thenAnswer(
            inv -> {
              if (failAfter > 0 && current.get() == failAfter) {
                throw new SQLException("TBR-17004: 테이블이 없습니다", "42000", -17004);
              }
              return current.incrementAndGet() <= n;
            });
    when(rs.getBigDecimal(1)).thenAnswer(inv -> BigDecimal.valueOf(current.get()));
    return rs;
  }

  @Test
  void examples_matchLineShapes() throws Exception {
    assertThat(STREAMING.get("contentType").asText()).isEqualTo("application/x-ndjson");
    int checked = 0;
    for (JsonNode ex : STREAMING.get("examples")) {
      if (ex.path("requestLevel").asBoolean(false)) {
        // 요청 형식 단계 거부는 컨트롤러 몫이라 ExecutorContractVectorsTest 가 창구로 검사한다.
        continue;
      }
      String label = ex.get("name").asText();
      FakeJdbc db = new FakeJdbc();
      db.resultSet = rows(ex.get("resultRows").asInt(), ex.path("failAfterRows").asInt(0));
      SlotQueryRunner runner =
          new SlotQueryRunner(db.slotPools, mock(StoreDescriptorClient.class), OM);
      JsonNode req = ex.get("request");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (Deadline deadline = new Deadline(30)) {
        runner.stream(
            SlotQueryRunnerTest.SLOT_DESCRIPTOR,
            req.get("readSlot").asInt(),
            req.get("query").asText(),
            deadline,
            out);
      }
      List<JsonNode> lines = new ArrayList<>();
      for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
        lines.add(OM.readTree(l));
      }
      JsonNode last = lines.get(lines.size() - 1);
      boolean success = ex.get("expectSuccess").asBoolean();
      assertThat(last.get("success").asBoolean()).as(label).isEqualTo(success);
      assertThat(keys(last))
          .as(label + " 마지막 줄 키")
          .containsExactlyElementsOf(
              strings(STREAMING.get(success ? "successLastLineKeys" : "failureLastLineKeys")));
      if (ex.has("expectLines")) {
        assertThat(lines).as(label).hasSize(ex.get("expectLines").asInt());
      } else {
        int rowLines = ex.get("expectRowLines").asInt();
        assertThat(lines).as(label).hasSize(1 + rowLines + 1);
        assertThat(keys(lines.get(0)))
            .containsExactlyElementsOf(strings(STREAMING.get("firstLineKeys")));
        for (int i = 1; i <= rowLines; i++) {
          assertThat(keys(lines.get(i)))
              .containsExactlyElementsOf(strings(STREAMING.get("rowLineKeys")));
        }
        if (success) {
          assertThat(last.get("row_count").asInt()).as(label).isEqualTo(rowLines);
        }
      }
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(4);
  }
}
