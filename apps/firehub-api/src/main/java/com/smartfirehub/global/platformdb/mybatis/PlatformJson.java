package com.smartfirehub.global.platformdb.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.global.exception.SerializationException;
import java.util.List;
import java.util.Map;

/**
 * MyBatis 구현이 JSON 열(PG jsonb / Tibero JSON)을 문자열로 주고받을 때 쓰는 변환 도우미(WD-708).
 *
 * <p>규칙은 하나다 — 입력이 null 이면 null 을 돌려주고, 깨진 JSON·직렬화 실패는 {@link SerializationException} 으로 던진다. 빈
 * 값·실패를 빈 맵·{@code {}}·null 로 바꿀지는 jOOQ 판마다 달라서 <b>호출하는 쪽이 명시적으로</b> 정한다(이 클래스는 그 선택을 숨기지 않는다).
 */
public final class PlatformJson {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
      new TypeReference<>() {};

  private PlatformJson() {}

  /**
   * JSON 텍스트 → 맵. null 이면 null.
   *
   * @param label 실패 메시지에 넣을 열·필드 이름
   * @throws SerializationException 깨진 JSON 이거나 객체가 아닐 때 — 메시지 「Failed to parse {label} JSON」
   */
  public static Map<String, Object> toMap(ObjectMapper objectMapper, String json, String label) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to parse " + label + " JSON", e);
    }
  }

  /**
   * JSON 텍스트 → 객체 목록(최상위가 배열). null 이면 null.
   *
   * @throws SerializationException 깨진 JSON 이거나 배열이 아닐 때 — 메시지 「Failed to parse {label} JSON」
   */
  public static List<Map<String, Object>> toMapList(
      ObjectMapper objectMapper, String json, String label) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, LIST_TYPE);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to parse " + label + " JSON", e);
    }
  }

  /**
   * JSON 텍스트 → 객체 노드(고쳐 쓸 수 있는 트리). null 이거나 최상위가 객체가 아니면 null.
   *
   * @throws SerializationException 깨진 JSON 일 때 — 메시지 「Failed to parse {label} JSON」
   */
  public static ObjectNode toObjectNode(ObjectMapper objectMapper, String json, String label) {
    if (json == null) {
      return null;
    }
    JsonNode node;
    try {
      node = objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to parse " + label + " JSON", e);
    }
    return node instanceof ObjectNode object ? object : null;
  }

  /**
   * 값(맵·트리 노드 등) → JSON 텍스트. null 이면 null.
   *
   * @throws SerializationException 직렬화 실패 — 메시지 「Failed to serialize {label}」
   */
  public static String write(ObjectMapper objectMapper, Object value, String label) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to serialize " + label, e);
    }
  }
}
