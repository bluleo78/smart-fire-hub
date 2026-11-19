package com.smartfirehub.global.util;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 숫자를 double 로 바꾸지 않고 원문 자릿수 그대로 읽는 공용 JSON 매퍼(WD-657).
 *
 * <p>읽은 트리를 다시 직렬화해도 소수 자릿수가 바뀌지 않아야 하는 곳이 함께 쓴다 — GeoJSON 좌표({@code GeoJsonValues})와 응답 본문 비밀
 * 가림({@code AuthQueryMasker#maskBody}, 가린 본문으로 미리보기 행을 만든다). 예전에는 같은 설정의 매퍼를 곳마다 따로 만들었다. 설정을 바꾸지
 * 않고 읽기·쓰기만 하므로 공유해도 안전하다.
 */
public final class ExactJsonMapper {

  private ExactJsonMapper() {}

  /**
   * 소수는 BigDecimal(자릿수 보존)로 읽는 매퍼.
   *
   * <p>Jackson 3(WD-793): 예전 Jackson 2 {@code new ObjectMapper()} 와 같은 기본값에서 출발한다( {@code
   * builderWithJackson2Defaults} — 속성 알파벳 정렬 끔 등). Jackson 2 의 {@code
   * JsonNodeFactory.withExactBigDecimals(true)}(트리의 BigDecimal 뒤 0 을 지우지 않음)는 Jackson 3 에서 {@code
   * STRIP_TRAILING_BIGDECIMAL_ZEROES} 끔으로 옮겨졌다 — Jackson 2 기본값 묶음은 이 기능을 켜므로 다시 끈다.
   */
  public static final ObjectMapper INSTANCE =
      JsonMapper.builderWithJackson2Defaults()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
          .build();
}
