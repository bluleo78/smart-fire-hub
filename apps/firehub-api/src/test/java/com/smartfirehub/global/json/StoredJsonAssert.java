package com.smartfirehub.global.json;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * DB 에 실제로 저장된 JSON 문자열을 기대 JSON 과 비교하는 시험 도우미(WD-793 Jackson 골든).
 *
 * <p>PG jsonb 는 키 순서·공백을 정규화해 돌려주므로 문자열 그대로는 비교할 수 없다. 그래서 둘 다 트리로 읽어 비교한다 — 숫자 표기(정수/실수/큰 수)·한글·
 * 이스케이프·null·중첩 형태는 트리 비교로 그대로 잡힌다(예: {@code 1} 과 {@code 1.0} 은 다른 노드). 시험 쪽에서 한 번 읽는 용도라 직접 만든 매퍼를
 * 쓴다.
 */
public final class StoredJsonAssert {

  private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

  private StoredJsonAssert() {}

  /** 저장된 원문 {@code stored} 가 {@code expectedJson} 과 같은 JSON 값인지 확인한다. */
  public static void assertStoredJson(String stored, String expectedJson) {
    assertThat(stored).as("저장된 JSON 원문").isNotNull();
    try {
      JsonNode actual = MAPPER.readTree(stored);
      JsonNode expected = MAPPER.readTree(expectedJson);
      assertThat(actual).as("저장된 JSON: " + stored).isEqualTo(expected);
    } catch (tools.jackson.core.JacksonException e) {
      throw new AssertionError("JSON 해석 실패: " + stored, e);
    }
  }
}
