package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.service.DataTableQueryService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 애드혹 SQL 결과 직렬화 회귀 테스트(#756).
 *
 * <p>결과에 jsonb/json/xml/interval/확장 타입 배열이 있으면 jOOQ 가 돌려준 값({@code org.jooq.JSONB} 등)이 행 Map 에 그대로
 * 담겨 Jackson 직렬화가 500 으로 실패하거나(jsonb·json·xml·point[]), 200 인데 따옴표 없는 토큰이 섞인 깨진 JSON 이
 * 됐다(interval). 두 애드혹 경로(애드혹 분석·데이터셋 SQL 탭)의 응답을 <b>Spring 이 쓰는 ObjectMapper 로 직렬화 → 다시
 * 파싱</b>해, 값이 PostgreSQL 이 내보내는 텍스트(오라클 = 같은 식의 {@code ::text})와 같은 JSON 문자열인지 확인한다.
 * "직렬화가 예외 없이 끝난다"만 보면 interval 처럼 깨진 JSON 을 내는 경우를 놓치므로 반드시 다시 파싱한다.
 */
@Transactional
class AdhocResultSerializationTest extends IntegrationTestBase {

  @Autowired private AnalyticsQueryExecutionService analyticsService;
  @Autowired private DataTableQueryService dataTableQueryService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DSLContext dsl;

  /** PG 가 이 식을 텍스트로 내보낸 값 — 기대값 오라클. */
  private String pgText(String expr) {
    return dsl.fetchOne("SELECT (" + expr + ")::text").get(0, String.class);
  }

  /** 애드혹 분석 경로로 {@code SELECT <expr> AS v} 를 실행하고 응답 JSON 의 v 값 노드를 돌려준다. */
  private JsonNode analyticsValue(String expr) throws Exception {
    AnalyticsQueryResponse res = analyticsService.execute("SELECT " + expr + " AS v", 10, true);
    assertThat(res.error()).as("analytics error for %s", expr).isNull();
    JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(res));
    return root.get("rows").get(0).get("v");
  }

  /** 데이터셋 SQL 탭 경로로 같은 식을 실행하고 응답 JSON 의 v 값 노드를 돌려준다. */
  private JsonNode datasetValue(String expr) throws Exception {
    SqlQueryResponse res = dataTableQueryService.executeQuery("SELECT " + expr + " AS v", 10);
    assertThat(res.error()).as("dataset error for %s", expr).isNull();
    JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(res));
    return root.get("rows").get(0).get("v");
  }

  /** 두 경로 모두에서 값이 PG 텍스트와 같은 JSON 문자열인지 확인한다. */
  private void assertTextOnBothPaths(String expr) throws Exception {
    String expected = pgText(expr);
    for (Function<String, JsonNode> path :
        java.util.List.<Function<String, JsonNode>>of(
            e -> call(() -> analyticsValue(e)), e -> call(() -> datasetValue(e)))) {
      JsonNode v = path.apply(expr);
      assertThat(v.isTextual()).as("%s 는 문자열이어야 한다: %s", expr, v).isTrue();
      assertThat(v.asText()).as(expr).isEqualTo(expected);
    }
  }

  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private static <T> T call(ThrowingSupplier<T> s) {
    try {
      return s.get();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void jsonb_json_xml_areReturnedAsPgText() throws Exception {
    assertTextOnBothPaths("'{\"a\": 1, \"b\": [1, 2]}'::jsonb");
    assertTextOnBothPaths("'[1,2]'::jsonb");
    assertTextOnBothPaths("'\"s\"'::jsonb");
    assertTextOnBothPaths("'{\"a\":1}'::json");
    assertTextOnBothPaths("'<a>x</a>'::xml");
    assertTextOnBothPaths("'{\"a\":1}'::jsonb -> 'a'");
  }

  @Test
  void interval_isReturnedAsPgTextNotBrokenJsonToken() throws Exception {
    assertTextOnBothPaths("interval '1 year 2 mons 3 days 04:05:06.5'");
    assertTextOnBothPaths("interval '1 day 02:00:00'");
    assertTextOnBothPaths("interval '-1 day'");
    assertTextOnBothPaths("interval '-01:30:00'");
    assertTextOnBothPaths("interval '0 seconds'");
    assertTextOnBothPaths("interval '2 years'");
    assertTextOnBothPaths("interval '00:00:00.000123'");
    assertTextOnBothPaths("interval '-1 year +2 days'");
  }

  @Test
  void arraysOfJsonbAndExtensionTypes_areSerializable() throws Exception {
    // jsonb[] → 원소별 JSON 텍스트 배열(null 원소 보존)
    JsonNode a = analyticsValue("ARRAY[NULL::jsonb, '{\"k\":1}'::jsonb]");
    assertThat(a.isArray()).isTrue();
    assertThat(a.get(0).isNull()).isTrue();
    assertThat(a.get(1).asText()).isEqualTo(pgText("'{\"k\":1}'::jsonb"));
    JsonNode d = datasetValue("ARRAY[NULL::jsonb, '{\"k\":1}'::jsonb]");
    assertThat(d.get(1).asText()).isEqualTo(pgText("'{\"k\":1}'::jsonb"));

    // interval[] → 원소별 PG 텍스트
    JsonNode iv = analyticsValue("ARRAY[interval '1 day']");
    assertThat(iv.get(0).asText()).isEqualTo("1 day");

    // point[]·inet[] (jOOQ 가 pgjdbc 배열을 그대로 둔 경우) → PG 배열 리터럴 텍스트
    assertTextOnBothPaths("ARRAY['(1,2)'::point]");
    assertTextOnBothPaths("ARRAY['1.1.1.1'::inet]");
  }

  @Test
  void alreadySerializableValues_keepTheirShape() throws Exception {
    // 회귀 가드 — 정수 배열은 여전히 JSON 배열, geometry 는 여전히 GeoJSON 문자열, 숫자는 숫자
    JsonNode ints = analyticsValue("ARRAY[1,2]");
    assertThat(ints.isArray()).isTrue();
    assertThat(ints.get(1).asInt()).isEqualTo(2);
    JsonNode geom = analyticsValue("ST_MakePoint(1,2)");
    assertThat(geom.isTextual()).isTrue();
    assertThat(objectMapper.readTree(geom.asText()).get("type").asText()).isEqualTo("Point");
    assertThat(datasetValue("42").asInt()).isEqualTo(42);
    assertThat(analyticsValue("'abc'::text").asText()).isEqualTo("abc");
  }
}
