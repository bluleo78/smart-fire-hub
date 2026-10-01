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

  // ---- #757 다차원 배열 ----

  /** 두 경로 모두에서 값이 기대 JSON(중첩 배열)과 같은지 확인한다 — executor(psycopg2) 경로와 같은 형태. */
  private void assertJsonOnBothPaths(String expr, String expectedJson) throws Exception {
    JsonNode expected = objectMapper.readTree(expectedJson);
    assertThat(analyticsValue(expr)).as("analytics %s", expr).isEqualTo(expected);
    assertThat(datasetValue(expr)).as("dataset %s", expr).isEqualTo(expected);
  }

  @Test
  void multiDimensionalArrays_areNestedListsNotNull() throws Exception {
    // 수정 전: int/numeric/bool 다차원은 null(조용한 손실), text 는 안쪽 배열이 PG 리터럴 문자열로 뭉개졌다
    assertJsonOnBothPaths("ARRAY[[1,2],[3,4]]", "[[1,2],[3,4]]");
    assertJsonOnBothPaths("ARRAY[['a','b'],['c','d']]", "[[\"a\",\"b\"],[\"c\",\"d\"]]");
    assertJsonOnBothPaths("ARRAY[[NULL,2]]", "[[null,2]]");
    assertJsonOnBothPaths("ARRAY[[[1,2]],[[3,4]]]", "[[[1,2]],[[3,4]]]");
    assertJsonOnBothPaths("ARRAY[[true,false]]", "[[true,false]]");
    // numeric 원소는 같은 경로의 1차원 numeric 원소와 같은 형태(JSON 숫자)
    assertJsonOnBothPaths("ARRAY[[1.5,NULL],[2,3]]::numeric[]", "[[1.5,null],[2,3]]");
    // 하한이 1 이 아닌 배열 — executor 처럼 하한은 버리고 값은 보존
    assertJsonOnBothPaths("'[0:1][0:1]={{1,2},{3,4}}'::int[]", "[[1,2],[3,4]]");
    // 원소 형태는 같은 경로의 1차원 배열 원소와 같다(날짜·시각·uuid·bytea·jsonb·interval 등)
    for (String elem :
        java.util.List.of(
            "'2024-01-02'::date",
            "'2024-01-02 03:04:05+09'::timestamptz",
            "'2024-01-02 03:04:05'::timestamp",
            "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid",
            "'\\x0102'::bytea",
            "'{\"k\":1}'::jsonb",
            "interval '1 year 2 days'",
            "1.25::numeric")) {
      JsonNode oneDim = analyticsValue("ARRAY[" + elem + "]").get(0);
      JsonNode expected = objectMapper.createArrayNode().add(objectMapper.createArrayNode().add(oneDim));
      assertThat(analyticsValue("ARRAY[[" + elem + "]]")).as("analytics 2D %s", elem).isEqualTo(expected);
      assertThat(datasetValue("ARRAY[[" + elem + "]]")).as("dataset 2D %s", elem).isEqualTo(expected);
    }
    assertJsonOnBothPaths("ARRAY[['{\"k\":1}'::jsonb]]", "[[\"{\\\"k\\\": 1}\"]]");
    assertJsonOnBothPaths("ARRAY[[interval '1 day']]", "[[\"1 day\"]]");
    // 여러 행·여러 컬럼이 섞여도 각 셀이 제자리에 들어간다
    var res =
        analyticsService.execute(
            "SELECT g AS n, ARRAY[[g, g+1]] AS m, ARRAY[g] AS a FROM generate_series(1,3) g", 10, true);
    JsonNode rows = objectMapper.readTree(objectMapper.writeValueAsString(res)).get("rows");
    for (int i = 0; i < 3; i++) {
      int g = i + 1;
      assertThat(rows.get(i).get("n").asInt()).isEqualTo(g);
      assertThat(rows.get(i).get("m")).isEqualTo(objectMapper.readTree("[[" + g + "," + (g + 1) + "]]"));
      assertThat(rows.get(i).get("a")).isEqualTo(objectMapper.readTree("[" + g + "]"));
    }
  }

  @Test
  void oneDimensionalArrays_keepTheirShapeAlongsideMultiDim() throws Exception {
    // 회귀 가드 — 1차원·빈·하한 있는 1차원 배열은 예전 그대로
    assertJsonOnBothPaths("ARRAY[1,2]", "[1,2]");
    assertJsonOnBothPaths("'{}'::int[]", "[]");
    assertJsonOnBothPaths("'[0:1]={1,2}'::int[]", "[1,2]");
    assertJsonOnBothPaths("ARRAY['a',NULL]", "[\"a\",null]");
    // 1차원 bytea[] 는 pgjdbc 가 byte[][] 로 준다 — 다차원으로 오판하지 않고 예전처럼 base64 원소 배열
    assertJsonOnBothPaths("ARRAY['\\x0102'::bytea, NULL]", "[\"AQI=\",null]");
    assertJsonOnBothPaths("'{}'::bytea[]", "[]");
  }

  @Test
  void multiDimensionalArray_unconvertibleElement_fallsBackToPgLiteralNotNull() {
    // 원소 변환이 실패하면(jOOQ Convert 가 null/예외) 셀은 null 이 아니라 PG 배열 리터럴 텍스트다 — 손실 금지
    Object v =
        AdhocMultiDimArrays.toNested(
            new Object[][] {{"not-a-number"}}, "{{not-a-number}}", Integer[].class);
    assertThat(v).isEqualTo("{{not-a-number}}");
    // 변환 가능한 값은 중첩 리스트
    assertThat(AdhocMultiDimArrays.toNested(new Integer[][] {{1, null}}, "{{1,NULL}}", Integer[].class))
        .isEqualTo(java.util.List.of(java.util.Arrays.asList(1, null)));
  }
}
