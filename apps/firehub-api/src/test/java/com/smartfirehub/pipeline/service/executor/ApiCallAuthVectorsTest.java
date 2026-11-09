package com.smartfirehub.pipeline.service.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import com.smartfirehub.apiconnection.service.BaseUrlUserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * API_CALL 인증 규칙이 공용 사례 표({@code fixtures/api-call-auth-vectors.json})와 같은지 본다(WD-657) — 리다이렉트 인증
 * 출처 판정, Bearer 접두 정리, 헤더 이름 공백 정리, 비밀 가림. executor 의 {@code tests/test_api_call_auth_vectors.py} 가
 * 같은 표로 Python 실행기를 검증하므로, 두 실행기가 같은 입력에서 같은 헤더·쿼리를 보내고 같은 값을 가린다는 것이 보장된다. 스프링 없이 도는 순수 단위 테스트다.
 */
class ApiCallAuthVectorsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 표의 한 갈래(sameAuthOrigin·bearerToken·headerName·scrub)를 읽는다. */
  private static JsonNode section(String name) throws IOException {
    try (InputStream in =
        ApiCallAuthVectorsTest.class.getResourceAsStream("/fixtures/api-call-auth-vectors.json")) {
      return MAPPER.readTree(in).get(name);
    }
  }

  private static Stream<Arguments> cases(String name) throws IOException {
    List<Arguments> out = new ArrayList<>();
    for (JsonNode c : section(name)) {
      out.add(Arguments.of(c.get("name").asText(), c));
    }
    return out.stream();
  }

  static Stream<Arguments> sameAuthOrigin() throws IOException {
    return cases("sameAuthOrigin");
  }

  static Stream<Arguments> bearerToken() throws IOException {
    return cases("bearerToken");
  }

  static Stream<Arguments> headerName() throws IOException {
    return cases("headerName");
  }

  static Stream<Arguments> scrub() throws IOException {
    return cases("scrub");
  }

  static Stream<Arguments> redirectLocation() throws IOException {
    return cases("redirectLocation");
  }

  static Stream<Arguments> redirectMethod() throws IOException {
    return cases("redirectMethod");
  }

  static Stream<Arguments> basicAuth() throws IOException {
    return cases("basicAuth");
  }

  static Stream<Arguments> maskUserInfo() throws IOException {
    return cases("maskUserInfo");
  }

  static Stream<Arguments> redirectFollow() throws IOException {
    return cases("redirectFollow");
  }

  /** 표가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 갈래마다 사례가 실제로 읽혔는지 본다. */
  @Test
  void fixture_isLoaded() throws IOException {
    for (String name :
        List.of(
            "sameAuthOrigin",
            "bearerToken",
            "headerName",
            "scrub",
            "redirectLocation",
            "redirectMethod",
            "basicAuth",
            "maskUserInfo",
            "basicScrub",
            "redirectFollow")) {
      assertThat(section(name).size()).as(name).isGreaterThanOrEqualTo(4);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sameAuthOrigin")
  void sameAuthOrigin_matchesVector(String name, JsonNode c) {
    assertThat(
            ApiCallAuth.isSameAuthOrigin(
                URI.create(c.get("origin").asText()), URI.create(c.get("target").asText())))
        .isEqualTo(c.get("expected").asBoolean());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bearerToken")
  void bearerToken_matchesVector(String name, JsonNode c) {
    ApiCallAuth.ResolvedAuth auth =
        ApiCallAuth.resolve(Map.of("authType", "BEARER", "token", c.get("raw").asText()));
    assertThat(auth.secret()).isEqualTo(c.get("expected").asText());
  }

  /** 헤더 이름 기본값만 두 언어의 대소문자가 달라(X-API-Key·x-api-key) 대소문자를 무시하고 비교한다 — HTTP 헤더 이름은 대소문자를 가리지 않는다. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("headerName")
  void headerName_matchesVector(String name, JsonNode c) {
    Map<String, String> auth = MAPPER.convertValue(c.get("auth"), new TypeReference<>() {});
    assertThat(ApiCallAuth.resolve(auth).headerName().toLowerCase(Locale.ROOT))
        .isEqualTo(c.get("expected").asText().toLowerCase(Locale.ROOT));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scrub")
  void scrub_matchesVector(String name, JsonNode c) {
    assertThat(AuthQueryMasker.scrubSecret(c.get("text").asText(), c.get("secret").asText()))
        .isEqualTo(c.get("expected").asText());
  }

  /**
   * 리다이렉트 Location 해석(WD-676) — Java 는 RFC 3986 해석기({@link RedirectLocations}), Python 은 {@code
   * urljoin} 이 같은 표에서 같은 절대 주소를 내야 한다. JDK {@code URI.resolve} 는 path 가 빈 base·쿼리만 있는 참조에서 결과가 달랐다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("redirectLocation")
  void redirectLocation_matchesVector(String name, JsonNode c) {
    assertThat(
            RedirectLocations.resolve(
                    URI.create(c.get("base").asText()), c.get("location").asText())
                .toString())
        .isEqualTo(c.get("expected").asText());
  }

  /** 리다이렉트 홉 메서드·본문 판정(WD-686) — 규칙은 {@link RedirectLocations#keepsMethodAndBody} 참고. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("redirectMethod")
  void redirectMethod_matchesVector(String name, JsonNode c) {
    boolean keeps =
        RedirectLocations.keepsMethodAndBody(
            c.get("status").asInt(), c.get("sameOrigin").asBoolean());
    assertThat(keeps).isEqualTo(c.get("keepsBody").asBoolean());
  }

  /**
   * BASIC 인증 헤더(WD-681) — 두 실행기가 같은 입력에서 같은 「Authorization: Basic …」 을 보내고, 그 Base64 덩어리와 비밀번호를 모두
   * 가릴 비밀로 본다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("basicAuth")
  void basicAuth_matchesVector(String name, JsonNode c) {
    Map<String, String> auth = MAPPER.convertValue(c.get("auth"), new TypeReference<>() {});
    ApiCallAuth.ResolvedAuth resolved = ApiCallAuth.resolve(auth);
    Map<String, String> headers = new java.util.LinkedHashMap<>();
    resolved.applyHeaders(headers::put);
    String expected = c.get("expectedHeader").asText();
    assertThat(headers).containsExactly(Map.entry("Authorization", expected));
    assertThat(resolved.sentSecrets())
        .isEqualTo(
            new AuthQueryMasker.SentSecrets(
                null, expected.substring("Basic ".length()), auth.get("password")));
  }

  static Stream<Arguments> basicScrub() throws IOException {
    return cases("basicScrub");
  }

  /**
   * BASIC 비밀 가림(WD-681 리뷰) — Basic 덩어리는 늘 고정 ****, 비밀번호는 SMTP 규칙(지우고 남으면 문장을 통째로 가림). Python {@code
   * SentSecrets.scrub} 과 같은 표다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("basicScrub")
  void basicScrub_matchesVector(String name, JsonNode c) {
    ApiCallAuth.ResolvedAuth resolved =
        ApiCallAuth.resolve(
            Map.of(
                "authType", "BASIC",
                "username", c.get("username").asText(),
                "password", c.get("password").asText()));
    assertThat(resolved.sentSecrets().scrubText(c.get("text").asText()))
        .isEqualTo(c.get("expected").asText());
  }

  /** 실행 기록·응답에 남기는 URL 의 사용자 정보 가림(WD-681) — Python 실행기 요약 줄과 같은 모양이다. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("maskUserInfo")
  void maskUserInfo_matchesVector(String name, JsonNode c) {
    assertThat(BaseUrlUserInfo.mask(c.get("url").asText())).isEqualTo(c.get("expected").asText());
  }

  /**
   * 리다이렉트로 따라가는 상태인지(WD-693) — 301·302·303·307·308 만 따라가고 300·304·305 등 그 밖의 3xx 는 일반 응답이다. 예전에는
   * Java 가 모든 3xx 를 따라가고 Python 은 다섯 상태만 따라가 같은 응답에서 결과가 달랐다. 규칙은 {@link
   * RedirectLocations#isFollowed} 참고.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("redirectFollow")
  void redirectFollow_matchesVector(String name, JsonNode c) {
    assertThat(RedirectLocations.isFollowed(c.get("status").asInt()))
        .isEqualTo(c.get("follows").asBoolean());
  }
}
