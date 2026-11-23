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

  @Test
  void rawItems_objectArray_topLevelFields() {
    List<Map<String, Object>> rows =
        parser.parseRawItems("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]", "$");

    assertThat(rows).containsExactly(row("id", 1, "name", "a"), row("id", 2, "name", "b"));
    // 키 순서 보존
    assertThat(new ArrayList<>(rows.get(0).keySet())).containsExactly("id", "name");
  }

  @Test
  void rawItems_nestedValues_keptAsMapAndList() {
    List<Map<String, Object>> rows =
        parser.parseRawItems(
            "[{\"id\":1,\"tags\":[\"x\",\"y\"],\"meta\":{\"k\":\"v\"},\"n\":null}]", "$");

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
    List<Map<String, Object>> rows = parser.parseRawItems("{\"id\":1}", "$");

    assertThat(rows).containsExactly(row("id", 1));
  }

  @Test
  void rawItems_scalarArray_valueColumn() {
    List<Map<String, Object>> rows = parser.parseRawItems("[1,\"a\"]", "$");

    assertThat(rows).containsExactly(row("value", 1), row("value", "a"));
  }

  @Test
  void rawItems_dataPath_selectsNested() {
    List<Map<String, Object>> rows =
        parser.parseRawItems("{\"data\":{\"items\":[{\"a\":1}]}}", "$.data.items");

    assertThat(rows).containsExactly(row("a", 1));
  }

  @Test
  void rawItems_differentKeys_keptPerRow() {
    List<Map<String, Object>> rows = parser.parseRawItems("[{\"a\":1},{\"b\":2}]", "$");

    assertThat(rows).containsExactly(row("a", 1), row("b", 2));
  }

  @Test
  void rawItems_nullAtPath_emptyList() {
    assertThat(parser.parseRawItems("{\"data\":null}", "$.data")).isEmpty();
  }
}
