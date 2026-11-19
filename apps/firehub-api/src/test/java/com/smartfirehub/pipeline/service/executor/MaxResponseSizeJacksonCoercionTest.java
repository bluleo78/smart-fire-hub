package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.config.JacksonCompatConfig;
import com.smartfirehub.global.json.JsonConversions;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * WD-810 전에 저장된 maxResponseSizeMb 원시 값이 Jackson 변환에서 어떻게 정수가 되는지 고정한다 — 러너·저장 검증의 "예전처럼 돈다" 기준이다.
 * 앱이 쓰는 매퍼({@link JacksonCompatConfig#appJsonMapperBuilder()} — Jackson 3 에 Jackson 2 기본값을 맞춘 것, WD-793)와 맨 매퍼 둘 다
 * 확인한다. 변환은 러너·저장 검증과 같은 {@link JsonConversions} 로 한다.
 */
class MaxResponseSizeJacksonCoercionTest {

  private static Integer convert(ObjectMapper om, Object raw) {
    Map<String, Object> map = new HashMap<>();
    map.put("url", "http://x");
    map.put("maxResponseSizeMb", raw);
    return JsonConversions.convertValue(om, map, ApiCallConfig.class).maxResponseSizeMb();
  }

  /** 변환 결과(Integer 문자열) — 실패하면 "ERR:" 뒤에 Jackson 오류 경로의 끝 필드 이름(러너가 한국어 오류로 바꾸는 근거). */
  private static String outcome(ObjectMapper om, Object raw) {
    try {
      return String.valueOf(convert(om, raw));
    } catch (IllegalArgumentException e) {
      for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
        if (c instanceof JacksonException je && !je.getPath().isEmpty()) {
          return "ERR:" + je.getPath().get(je.getPath().size() - 1).getPropertyName();
        }
      }
      return "ERR:?";
    }
  }

  @Test
  void jacksonCoercion_table() {
    for (ObjectMapper om :
        new ObjectMapper[] {JacksonCompatConfig.appJsonMapperBuilder().build(), JsonMapper.builder().build()}) {
      // 유한한 소수는 소수부를 0 쪽으로 버려 Integer 가 된다(-0.5 는 0 이라 실행기 한도 검사가 거절한다).
      assertThat(outcome(om, 10.5)).isEqualTo("10");
      assertThat(outcome(om, 0.5)).isEqualTo("0");
      assertThat(outcome(om, 2047.9)).isEqualTo("2047");
      assertThat(outcome(om, -0.5)).isEqualTo("0");
      assertThat(outcome(om, "50")).isEqualTo("50");
      // 빈 문자열·공백만 있는 문자열은 null 이 되어 기본 10MB 로 돈다.
      assertThat(outcome(om, "")).isEqualTo("null");
      assertThat(outcome(om, "  ")).isEqualTo("null");
      // 변환 못 하는 값(소수 문자열·int 초과)은 maxResponseSizeMb 필드 오류 — 러너가 이 필드명으로 한국어 오류로 바꾼다.
      assertThat(outcome(om, "10.5")).isEqualTo("ERR:maxResponseSizeMb");
      assertThat(outcome(om, 3_000_000_000L)).isEqualTo("ERR:maxResponseSizeMb");
      assertThat(outcome(om, "abc")).isEqualTo("ERR:maxResponseSizeMb");
    }
  }
}
