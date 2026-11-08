package com.smartfirehub.global.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * 숫자를 double 로 바꾸지 않고 원문 자릿수 그대로 읽는 공용 JSON 매퍼(WD-657).
 *
 * <p>읽은 트리를 다시 직렬화해도 소수 자릿수가 바뀌지 않아야 하는 곳이 함께 쓴다 — GeoJSON 좌표({@code GeoJsonValues})와 응답 본문 비밀
 * 가림({@code AuthQueryMasker#maskBody}, 가린 본문으로 미리보기 행을 만든다). 예전에는 같은 설정의 매퍼를 곳마다 따로 만들었다. 설정을 바꾸지
 * 않고 읽기·쓰기만 하므로 공유해도 안전하다.
 */
public final class ExactJsonMapper {

  private ExactJsonMapper() {}

  /** 소수는 BigDecimal(자릿수 보존)로 읽는 매퍼. */
  public static final ObjectMapper INSTANCE =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
}
