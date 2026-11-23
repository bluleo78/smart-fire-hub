package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link JsonResponseParser#parseRawItems} 단위 시험 — 매핑 없이 응답 항목을 원본 행으로 만드는 규칙(WD-947). */
class JsonResponseParserRawItemsTest {

  private final JsonResponseParser parser = new JsonResponseParser();

  /** 기대 행을 키 순서 그대로 만든다(null 값 허용). */
  private static Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  /** 객체 항목 목록만 꺼낸다(RawItems.objects). */
  private List<Map<String, Object>> objects(String json, String dataPath) {
    return parser.parseRawItems(json, dataPath).objects();
  }

  @Test
  void rawItems_objectArray_topLevelFields() {
    List<Map<String, Object>> rows =
        objects("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]", "$");

    assertThat(rows).containsExactly(row("id", 1, "name", "a"), row("id", 2, "name", "b"));
    // 키 순서 보존
    assertThat(new ArrayList<>(rows.get(0).keySet())).containsExactly("id", "name");
  }

  @Test
  void rawItems_nestedValues_keptAsMapAndList() {
    List<Map<String, Object>> rows =
        objects("[{\"id\":1,\"tags\":[\"x\",\"y\"],\"meta\":{\"k\":\"v\"},\"n\":null}]", "$");

    assertThat(rows).hasSize(1);
    Map<String, Object> r = rows.get(0);
    assertThat(r.get("tags")).isEqualTo(List.of("x", "y"));
    assertThat(r.get("meta")).isEqualTo(Map.of("k", "v"));
    // null 은 키가 남아 있어야 한다
    assertThat(r).containsKey("n");
    assertThat(r.get("n")).isNull();
  }

  @Test
  void rawItems_singleObject_wrapped() {
    List<Map<String, Object>> rows = objects("{\"id\":1}", "$");

    assertThat(rows).containsExactly(row("id", 1));
  }

  /** WD-947 리뷰: 객체가 아닌 항목은 objects 에 없고(value 행 없음) total 에는 센다. */
  @Test
  void rawItems_scalarItems_notInObjectsButCountedInTotal() {
    JsonResponseParser.RawItems items = parser.parseRawItems("[1,\"a\",{\"k\":1}]", "$");

    assertThat(items.objects()).containsExactly(row("k", 1));
    assertThat(items.total()).isEqualTo(3);

    JsonResponseParser.RawItems scalars = parser.parseRawItems("[1,\"a\"]", "$");
    assertThat(scalars.objects()).isEmpty();
    assertThat(scalars.total()).isEqualTo(2);
  }

  @Test
  void rawItems_dataPath_selectsNested() {
    List<Map<String, Object>> rows = objects("{\"data\":{\"items\":[{\"a\":1}]}}", "$.data.items");

    assertThat(rows).containsExactly(row("a", 1));
  }

  @Test
  void rawItems_differentKeys_keptPerRow() {
    List<Map<String, Object>> rows = objects("[{\"a\":1},{\"b\":2}]", "$");

    assertThat(rows).containsExactly(row("a", 1), row("b", 2));
  }

  @Test
  void rawItems_nullAtPath_emptyList() {
    assertThat(objects("{\"data\":null}", "$.data")).isEmpty();
  }

  /** WD-947 3차: 매핑 설정 검사 — 잘못된 날짜 형식은 설정 오류(ApiCallException), 올바르면 통과한다. */
  @Test
  void validateMappings_invalidDateFormat_throws() {
    var bad = new ApiCallConfig.FieldMapping("", "d", "DATE", "yyyy-QQQQQ-zzzz-bad{", null, null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.validateMappings(List.of(bad)))
        .isInstanceOf(ApiCallException.class);
    var ok = new ApiCallConfig.FieldMapping("", "t", "TEXT", null, null, null);
    parser.validateMappings(List.of(ok));
  }
}
