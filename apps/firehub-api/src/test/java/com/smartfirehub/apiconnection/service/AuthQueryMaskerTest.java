package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthQueryMasker} 단위 테스트(WD-177). 호출 URL 은 예전과 같고 표시 URL 만 인증 쿼리 키를 {@code ****XXXX} 로
 * 가리는지, URL 을 만들 수 없을 때 예외 문구·원인 사슬에 키 원문이 없는지, 자유 문장에서 원문·인코딩형·디코딩형을 모두 지우는지 확인한다.
 */
class AuthQueryMaskerTest {

  /** maskedAuthConfig(EncryptionService.maskValue)와 같은 형식이어야 화면마다 가린 모양이 같다. */
  @Test
  void maskSecret_sameFormatAsMaskedAuthConfig() {
    assertThat(AuthQueryMasker.maskSecret("abcdef9876")).isEqualTo("****9876");
    assertThat(AuthQueryMasker.maskSecret("abc")).isEqualTo("****");
    assertThat(AuthQueryMasker.maskSecret(null)).isEqualTo("****");
  }

  /** 호출 URL 에는 키 원문, 표시 URL 에는 가린 값 — 다른 쿼리 파라미터·순서는 두 URL 이 같다. */
  @Test
  void build_masksOnlyAuthParamInDisplayUrl() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pageNo", "1");
    params.put("serviceKey", "RAWKEY000011119876");

    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("http://h.example/api", params, "serviceKey", "RAWKEY000011119876");

    assertThat(urls.callUrl())
        .isEqualTo("http://h.example/api?pageNo=1&serviceKey=RAWKEY000011119876");
    assertThat(urls.displayUrl()).isEqualTo("http://h.example/api?pageNo=1&serviceKey=****9876");
  }

  /** 이미 인코딩된 키(공공데이터포털 Encoding 키)는 호출에 그대로 쓰이고, 표시 URL 엔 원문도 디코딩형도 없다. */
  @Test
  void build_preEncodedKey_callUnchanged_displayHasNoForm() {
    String encodedKey = "abc%2Bdef%2F%3D%3D";
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.buildForAuth(
            "http://h.example/api",
            "API_KEY",
            Map.of("placement", "query", "paramName", "serviceKey", "apiKey", encodedKey));

    assertThat(urls.callUrl()).isEqualTo("http://h.example/api?serviceKey=" + encodedKey);
    assertThat(urls.displayUrl())
        .doesNotContain(encodedKey)
        .doesNotContain("abc+def/==")
        .startsWith("http://h.example/api?serviceKey=****");
  }

  /** 쿼리 인증이 아니면 URL 을 손대지 않는다(경로의 한글·공백을 WebClient 가 인코딩하던 예전 동작 유지). */
  @Test
  void buildForAuth_noQueryAuth_returnsUrlUntouched() {
    String url = "http://h.example/경로 공백";
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.buildForAuth(url, "BEARER", Map.of("token", "t"));
    assertThat(urls.callUrl()).isEqualTo(url);
    assertThat(urls.displayUrl()).isEqualTo(url);
  }

  /**
   * 값에 쿼리에 그대로 쓸 수 없는 문자(=·한글·공백)가 있으면 Spring 은 키 원문을 담은 메시지로 실패한다 — 고정 문구로 바꾸고 cause 도 잇지 않아 근본
   * 원인을 따라가는 분류기(ApiCallErrorMessages.describe)도 원문을 꺼낼 수 없어야 한다.
   */
  @Test
  void build_invalidCharKey_throwsWithoutSecretOrCause() {
    String key = "ab+c/d==키값 Xy9";
    Map<String, String> params = new LinkedHashMap<>();
    params.put("serviceKey", key);

    assertThatThrownBy(
            () -> AuthQueryMasker.build("http://h.example/api", params, "serviceKey", key))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause()
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContain(key)
                    .doesNotContain(URLEncoder.encode(key, StandardCharsets.UTF_8)));
  }

  /** 자유 문장 속 키를 원문·form 인코딩형·%20 인코딩형·(이미 인코딩된 키의) 디코딩형 모두 가린다. */
  @Test
  void scrubSecret_removesRawEncodedAndDecodedForms() {
    String key = "ab+c/d==키값 Xy9";
    String formEncoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
    String text =
        "raw=" + key + " form=" + formEncoded + " pct=" + formEncoded.replace("+", "%20") + " tail";

    String out = AuthQueryMasker.scrubSecret(text, key);

    assertThat(out)
        .doesNotContain(key)
        .doesNotContain(formEncoded)
        .doesNotContain(formEncoded.replace("+", "%20"))
        .contains("****")
        .endsWith(" tail");

    String preEncoded = "abc%2Bdef%2F%3D%3D";
    assertThat(AuthQueryMasker.scrubSecret("x abc+def/== y", preEncoded))
        .isEqualTo("x " + AuthQueryMasker.maskSecret(preEncoded) + " y");
  }
}
