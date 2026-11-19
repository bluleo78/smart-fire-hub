package com.smartfirehub.global.json;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code convertValue}·{@code valueToTree} 를 Jackson 2 때와 같은 예외 계약으로 부르는 도우미(WD-793).
 *
 * <p>왜 필요한가: Jackson 2 는 변환 실패를 {@link IllegalArgumentException}(메시지 = 원래 예외 메시지, 원인 = 원래 예외)으로 감싸
 * 던졌다. Jackson 3 은 같은 실패를 {@link JacksonException}(예: {@code InvalidFormatException})으로 그대로 던진다.
 * 사용자가 보낸 설정 맵(파이프라인 aiConfig·pythonConfig 등)을 변환하다 실패하면 예전에는 {@code GlobalExceptionHandler} 의
 * IllegalArgumentException 처리(400)를 탔는데, 그대로 두면 일반 예외(500)로 바뀐다. 호출부의 오류 처리 동작을 바꾸지 않으려고 예전 감싸기를
 * 여기서 재현한다.
 */
public final class JsonConversions {

  private JsonConversions() {}

  /**
   * {@link ObjectMapper#convertValue(Object, Class)} — 실패는 Jackson 2 처럼 IllegalArgumentException.
   */
  public static <T> T convertValue(ObjectMapper mapper, Object fromValue, Class<T> toValueType) {
    try {
      return mapper.convertValue(fromValue, toValueType);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /**
   * {@link ObjectMapper#convertValue(Object, TypeReference)} — 실패는 Jackson 2 처럼
   * IllegalArgumentException.
   */
  public static <T> T convertValue(
      ObjectMapper mapper, Object fromValue, TypeReference<T> toValueTypeRef) {
    try {
      return mapper.convertValue(fromValue, toValueTypeRef);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /** {@link ObjectMapper#valueToTree(Object)} — 실패는 Jackson 2 처럼 IllegalArgumentException. */
  public static <T extends JsonNode> T valueToTree(ObjectMapper mapper, Object fromValue) {
    try {
      return mapper.valueToTree(fromValue);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }
}
