package com.smartfirehub.global.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.AiClassifyConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link JsonConversions} 가 Jackson 2 의 예외 계약을 지키는지 확인한다(WD-793).
 *
 * <p>Jackson 2 의 convertValue·valueToTree 는 변환 실패를 IllegalArgumentException(메시지 = 원래 예외 메시지, 원인 =
 * 원래 예외)으로 감쌌다. 이 감싸기가 빠지면 잘못된 사용자 설정이 400 이 아니라 500 이 된다.
 */
class JsonConversionsTest {

  private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();

  /** 전제: Jackson 3 은 감싸지 않고 JacksonException 을 그대로 던진다 — 도우미가 왜 필요한지 고정한다. */
  @Test
  void jackson3ConvertValue_throwsJacksonExceptionDirectly() {
    assertThatThrownBy(
            () -> mapper.convertValue(Map.of("batchSize", "abc"), AiClassifyConfig.class))
        .isInstanceOf(JacksonException.class)
        .isNotInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void convertValue_class_failureIsIllegalArgumentWithCauseAndMessage() {
    assertThatThrownBy(
            () ->
                JsonConversions.convertValue(
                    mapper, Map.of("batchSize", "abc"), AiClassifyConfig.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(JacksonException.class)
        .satisfies(e -> assertThat(e.getMessage()).isEqualTo(e.getCause().getMessage()));
  }

  @Test
  void convertValue_typeReference_failureIsIllegalArgument() {
    assertThatThrownBy(
            () ->
                JsonConversions.convertValue(
                    mapper, Map.of("a", "x"), new TypeReference<Map<String, Integer>>() {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(JacksonException.class);
  }

  @Test
  void valueToTree_failureIsIllegalArgument() {
    // 직렬화할 수 없는 값(속성 없는 빈 — FAIL_ON_EMPTY_BEANS)으로 실패를 만든다.
    assertThatThrownBy(() -> JsonConversions.valueToTree(mapper, new Object()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasCauseInstanceOf(JacksonException.class);
  }

  @Test
  void success_returnsSameAsMapper() {
    AiClassifyConfig config =
        JsonConversions.convertValue(
            mapper,
            Map.of("prompt", "p", "batchSize", 5, "outputColumns", List.of()),
            AiClassifyConfig.class);
    assertThat(config.prompt()).isEqualTo("p");
    assertThat(config.batchSize()).isEqualTo(5);

    Map<String, Object> asMap =
        JsonConversions.convertValue(mapper, config, new TypeReference<Map<String, Object>>() {});
    assertThat(asMap).containsEntry("batchSize", 5);

    JsonNode tree = JsonConversions.valueToTree(mapper, Map.of("k", 1));
    assertThat(tree.get("k").asInt()).isEqualTo(1);
  }
}
