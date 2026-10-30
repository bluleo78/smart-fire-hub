package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallException;
import com.smartfirehub.pipeline.service.executor.JsonResponseParser;
import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * Tibero 적재 값 변환·바인딩 고정 벡터({@code tibero-bind-vectors.json})가 api 의 현행 동작과 같은지 고정한다(WD-77 Task 3).
 *
 * <p>벡터는 현행 동작의 사진이다: 입력(appType, JSON) → {@link JsonResponseParser} 가 만드는 Java 값 → {@link
 * TiberoValues#bind} 가 {@link PreparedStatement} 에 거는 호출. db-executor 의 {@code
 * TiberoBindVectorsParityTest} 가 같은 파일을 읽어 db-executor 변환·바인딩이 같은 호출을 내는지 본다. 한쪽만 바뀌면 둘 중 하나가 깨진다.
 */
class TiberoValuesBindVectorsTest {

  private static final ObjectMapper OM = new ObjectMapper();
  private static final JsonNode FIXTURE = load();

  private static JsonNode load() {
    try (InputStream in =
        TiberoValuesBindVectorsTest.class.getResourceAsStream(
            "/fixtures/tibero-bind-vectors.json")) {
      return OM.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("Tibero 바인딩 벡터 픽스처를 읽지 못했다", e);
    }
  }

  /**
   * 벡터 하나를 API 수집 응답처럼 JsonResponseParser 에 넣어 변환된 행 목록을 돌려준다(변환 실패면 ApiCallException — WD-431).
   */
  private static List<Map<String, Object>> parsed(JsonNode vector) throws Exception {
    String appType = vector.get("appType").isNull() ? null : vector.get("appType").asText();
    // 벡터의 json 값을 {"d":[{"v": <json>}]} 로 감싸 API 수집 응답처럼 넣는다(JSON null 도 그대로 전달).
    ObjectNode item = OM.createObjectNode();
    item.set("v", vector.get("json"));
    ObjectNode root = OM.createObjectNode();
    root.putArray("d").add(item);
    return new JsonResponseParser()
        .parseAndMap(
            OM.writeValueAsString(root),
            "$.d",
            List.of(new ApiCallConfig.FieldMapping("v", "c", appType, null, null, null)),
            null,
            0);
  }

  /** 벡터 하나를 api 경로(JsonResponseParser → TiberoValues.bind)에 태워 PreparedStatement 호출을 기록한다. */
  private static Invocation bound(JsonNode vector) throws Exception {
    List<Map<String, Object>> rows = parsed(vector);
    assertThat(rows).as(vector.get("name").asText() + " 변환 결과 행").hasSize(1);
    PreparedStatement ps = mock(PreparedStatement.class);
    TiberoValues.bind(ps, 1, rows.get(0).get("c"));
    return Mockito.mockingDetails(ps).getInvocations().iterator().next();
  }

  @Test
  void bindings_현행_api_동작과_일치() throws Exception {
    JsonNode cases = FIXTURE.get("bindings");
    assertThat(cases.size()).isGreaterThanOrEqualTo(10);
    for (JsonNode c : cases) {
      Invocation inv = bound(c);
      JsonNode expected = c.get("expected");
      String label = c.get("name").asText();
      assertThat(inv.getMethod().getName())
          .as(label + " 메서드")
          .isEqualTo(expected.get("method").asText());
      Object arg = inv.getArgument(1);
      assertThat(String.valueOf(arg)).as(label + " 인자").isEqualTo(expected.get("arg").asText());
      if (expected.has("argClass")) {
        assertThat(arg.getClass().getName())
            .as(label + " 인자 클래스")
            .isEqualTo(expected.get("argClass").asText());
      }
    }
  }

  /**
   * db-executor 가 거부하는 입력: api 로컬 모드는 빈 문자열이 아니면 행을 건너뛰지 않고 스텝을 실패시킨다(WD-431 — 예전엔 조용히 건너뛰었다).
   * 숫자·불리언·날짜 타입의 빈 문자열은 NULL 로 둔다(데이터 임포트와 같은 규칙 — db-executor 에는 Python 실행기가 None 으로 보낸다).
   */
  @Test
  void expectFailure_apiFailsTheStep_orNullsBlank() throws Exception {
    JsonNode cases = FIXTURE.get("expectFailure");
    assertThat(cases.size()).isGreaterThanOrEqualTo(4);
    for (JsonNode c : cases) {
      String label = c.get("name").asText();
      JsonNode json = c.get("json");
      if (json.isTextual() && json.asText().isBlank()) {
        List<Map<String, Object>> rows = parsed(c);
        assertThat(rows).as(label + " 은 행이 남는다").hasSize(1);
        assertThat(rows.get(0).get("c")).as(label + " 은 NULL").isNull();
      } else {
        assertThatThrownBy(() -> parsed(c))
            .as(label + " 은 스텝 실패")
            .isInstanceOf(ApiCallException.class)
            .hasMessageContaining("응답 데이터의 1번째 행 'v' 필드 값")
            .hasMessageContaining("변환할 수 없습니다");
      }
    }
  }

  @Test
  void columnNames_physical_대문자() {
    for (JsonNode c : FIXTURE.get("columnNames")) {
      assertThat(TiberoNames.physical(c.get("in").asText())).isEqualTo(c.get("out").asText());
    }
  }
}
