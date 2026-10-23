package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link QueryApiKeyEncoding} 이 공용 사례 표({@code fixtures/query-api-key-encoding-vectors.json})와 같은
 * 판정·결과를 내는지 본다(WD-182). 스프링 없이 도는 순수 단위 테스트다.
 *
 * <p>executor 의 {@code tests/test_query_key_encoding_vectors.py} 가 같은 파일로 Python 규칙을 검증한다 — 두 언어가
 * 같은 파일을 읽어야 연결 테스트·미리보기·Java 실행기와 Python 실행기가 대상 서버에 같은 바이트를 보낸다는 것이 보장된다.
 */
class QueryApiKeyEncodingTest {

  private static JsonNode vectors() throws IOException {
    try (InputStream in =
        QueryApiKeyEncodingTest.class.getResourceAsStream(
            "/fixtures/query-api-key-encoding-vectors.json")) {
      return new ObjectMapper().readTree(in);
    }
  }

  static Stream<Arguments> cases() throws IOException {
    List<Arguments> out = new ArrayList<>();
    for (JsonNode c : vectors().get("cases")) {
      out.add(
          Arguments.of(
              c.get("name").asText(),
              c.get("input").asText(),
              c.get("preEncoded").asBoolean(),
              c.get("encoded").asText()));
    }
    return out.stream();
  }

  /** 표가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 사례가 실제로 읽혔고 두 판정이 모두 있는지 확인한다. */
  @Test
  void fixture_hasBothJudgements() throws IOException {
    JsonNode cs = vectors().get("cases");
    assertThat(cs.size()).isGreaterThanOrEqualTo(20);
    boolean anyPre = false;
    boolean anyRaw = false;
    for (JsonNode c : cs) {
      if (c.get("preEncoded").asBoolean()) anyPre = true;
      else anyRaw = true;
    }
    assertThat(anyPre).isTrue();
    assertThat(anyRaw).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void encode_matchesSharedVector(String name, String input, boolean preEncoded, String encoded) {
    assertThat(QueryApiKeyEncoding.isPreEncoded(input)).as("판정: " + name).isEqualTo(preEncoded);
    assertThat(QueryApiKeyEncoding.encode(input)).as("인코딩: " + name).isEqualTo(encoded);
  }

  /** 한 번 인코딩한 결과를 다시 넣어도 바뀌지 않는다 — 인코딩 키와 디코딩 키가 같은 값으로 모이는 근거(이중 인코딩 없음). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void encode_isIdempotent(String name, String input, boolean preEncoded, String encoded) {
    assertThat(QueryApiKeyEncoding.encode(encoded)).as(name).isEqualTo(encoded);
  }

  /** 결과는 비예약 문자와 %XX 로만 이뤄진다 — UriComponentsBuilder.build(true) 가 거부할 글자가 남지 않는다. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void encode_outputOnlyUnreservedAndEscapes(
      String name, String input, boolean preEncoded, String encoded) {
    assertThat(QueryApiKeyEncoding.encode(input))
        .as(name)
        .matches("([A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})*");
  }

  @Test
  void nullStaysNull() {
    assertThat(QueryApiKeyEncoding.encode(null)).isNull();
    assertThat(QueryApiKeyEncoding.isPreEncoded(null)).isFalse();
  }

  /** 빌더 관통: 인코딩 키와 디코딩 키가 같은 호출 URL 이 되고, 표시 URL 은 둘 다 가린 값이다. */
  @Test
  void authQueryMasker_encodedAndDecodedKeysProduceSameCallUrl() {
    AuthQueryMasker.AuthQueryUrls enc =
        AuthQueryMasker.buildForAuth(
            "http://h.example/api",
            "API_KEY",
            Map.of("placement", "query", "paramName", "serviceKey", "apiKey", "abc%2Bd%3D%3D"));
    AuthQueryMasker.AuthQueryUrls dec =
        AuthQueryMasker.buildForAuth(
            "http://h.example/api",
            "API_KEY",
            Map.of("placement", "query", "paramName", "serviceKey", "apiKey", "abc+d=="));

    assertThat(enc.callUrl()).isEqualTo("http://h.example/api?serviceKey=abc%2Bd%3D%3D");
    assertThat(dec.callUrl()).isEqualTo(enc.callUrl());
    assertThat(enc.callUri().getRawQuery()).isEqualTo("serviceKey=abc%2Bd%3D%3D");
    assertThat(dec.callUri().getRawQuery()).isEqualTo("serviceKey=abc%2Bd%3D%3D");
  }
}
