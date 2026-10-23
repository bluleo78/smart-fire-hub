package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * API_CALL 스텝의 쿼리 파라미터(정적·페이지네이션·인증 키)가 붙은 호출 URL 이 공용 사례 표({@code
 * fixtures/query-param-encoding-vectors.json})와 같은지 본다(WD-200). 스프링 없이 도는 순수 단위 테스트다.
 *
 * <p>미리보기({@code ApiCallPreviewService})와 Java 실행기({@code ApiCallExecutor})는 모두 {@link
 * AuthQueryMasker#build} 로 호출 URL 을 만들고, executor 의 {@code
 * tests/test_query_key_encoding_vectors.py} 가 같은 표로 Python {@code _build_request_url} 을 검증한다 — 두
 * 언어가 같은 파일을 읽어야 미리보기와 실제 실행이 대상 서버에 같은 쿼리 문자열을 보낸다는 것이 보장된다.
 */
class QueryParamEncodingVectorsTest {

  private static JsonNode cases() throws IOException {
    try (InputStream in =
        QueryParamEncodingVectorsTest.class.getResourceAsStream(
            "/fixtures/query-param-encoding-vectors.json")) {
      return new ObjectMapper().readTree(in).get("cases");
    }
  }

  static Stream<Arguments> vectorCases() throws IOException {
    List<Arguments> out = new ArrayList<>();
    for (JsonNode c : cases()) {
      out.add(Arguments.of(c.get("name").asText(), c));
    }
    return out.stream();
  }

  /** 표가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 사례가 실제로 읽혔고 인증 키가 있는 사례·없는 사례가 모두 있는지 확인한다. */
  @Test
  void fixture_isLoaded() throws IOException {
    JsonNode cs = cases();
    assertThat(cs.size()).isGreaterThanOrEqualTo(15);
    boolean anyAuth = false;
    boolean anyNoAuth = false;
    for (JsonNode c : cs) {
      if (c.get("auth").isNull()) anyNoAuth = true;
      else anyAuth = true;
    }
    assertThat(anyAuth).isTrue();
    assertThat(anyNoAuth).isTrue();
  }

  /**
   * 호출자(미리보기·Java 실행기)와 같은 방식으로 파라미터 맵을 만든다 — 정적·페이지네이션을 순서대로 넣고 인증 키를 {@code put} 으로 덮어 넣는다. 그 결과의
   * 호출 URL 이 표의 기대값(Python 과 같은 값)이어야 한다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("vectorCases")
  void build_callUrl_matchesSharedVector(String name, JsonNode c) {
    Map<String, String> params = new LinkedHashMap<>();
    for (JsonNode pair : c.get("params")) {
      params.put(pair.get(0).asText(), pair.get(1).asText());
    }
    String authName = null;
    String authKey = null;
    if (!c.get("auth").isNull()) {
      authName = c.get("auth").get(0).asText();
      authKey = c.get("auth").get(1).asText();
      params.put(authName, authKey);
    }

    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build(c.get("url").asText(), params, authName, authKey);

    assertThat(urls.callUrl()).isEqualTo(c.get("expected").asText());
    // 호출 URI 로 해석해도 다시 인코딩되지 않는다(WebClient 에 넘기는 형태).
    assertThat(urls.callUri().toASCIIString()).isEqualTo(c.get("expected").asText());
  }

  /**
   * null 값은 빈 값({@code name=})으로 보낸다 — Spring 기본은 {@code name}(= 없음)이라 Python({@code name=})과 바이트가
   * 달랐다.
   */
  @Test
  void build_nullValue_sentAsEmpty() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("q", null);
    assertThat(AuthQueryMasker.build("http://h.example/api", params, null, null).callUrl())
        .isEqualTo("http://h.example/api?q=");
  }
}
