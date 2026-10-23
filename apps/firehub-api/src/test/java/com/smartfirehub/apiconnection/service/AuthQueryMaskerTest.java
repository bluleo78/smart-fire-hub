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

  /** 이미 인코딩된 키(공공데이터포털 Encoding 키)는 호출에 그대로 쓰이고(다시 인코딩하지 않음, WD-182), 표시 URL 엔 원문도 디코딩형도 없다. */
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
   * 디코딩 키(=·한글·공백·+·/ 가 섞인 키)도 URL 을 만든다(WD-182) — 예전에는 build(true) 가 실패해 「URL 인코딩된 값을 넣으세요」로 끝났다.
   * 호출 URL 엔 공용 규칙으로 한 번 인코딩한 값이, 표시 URL 엔 가린 값만 있다.
   */
  @Test
  void build_decodedSpecialCharKey_encodedOnceInCallUrl_maskedInDisplay() {
    String key = "ab+c/d==키값 Xy9";
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pageNo", "1");
    params.put("serviceKey", key);

    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("http://h.example/api", params, "serviceKey", key);

    assertThat(urls.callUrl())
        .isEqualTo(
            "http://h.example/api?pageNo=1&serviceKey=ab%2Bc%2Fd%3D%3D%ED%82%A4%EA%B0%92%20Xy9");
    assertThat(urls.displayUrl()).isEqualTo("http://h.example/api?pageNo=1&serviceKey=**** Xy9");
  }

  /**
   * 키가 아닌 정적 쿼리 값의 {@code = 공백 한글} 도 이제 공용 규칙으로 한 번 인코딩해 URL 을 만든다(WD-200) — 예전에는 「URL 인코딩해 넣으세요」로
   * 거부했는데, 운영 실행(Python 실행기)은 같은 값을 인코딩해 보내 미리보기만 실패했다. 표시 URL 에도 보내는 인코딩 형태 그대로 보인다.
   */
  @Test
  void build_unencodedStaticParam_encodedOnce_sameInDisplay() {
    String key = "ab+c/d==키값 Xy9";
    Map<String, String> params = new LinkedHashMap<>();
    params.put("q", "a=b 한글");
    params.put("serviceKey", key);

    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("http://h.example/api", params, "serviceKey", key);

    assertThat(urls.callUrl())
        .isEqualTo(
            "http://h.example/api?q=a%3Db%20%ED%95%9C%EA%B8%80"
                + "&serviceKey=ab%2Bc%2Fd%3D%3D%ED%82%A4%EA%B0%92%20Xy9");
    assertThat(urls.displayUrl())
        .isEqualTo("http://h.example/api?q=a%3Db%20%ED%95%9C%EA%B8%80&serviceKey=**** Xy9");
  }

  /**
   * 기준 URL(주소에 직접 쓴 쿼리)에 그대로 쓸 수 없는 문자(공백·한글)가 있으면 여전히 URL 을 만들 수 없다 — Spring 은 원문을 담은 메시지로 실패하므로
   * 고정 문구로 바꾸고 cause 도 잇지 않아, 근본 원인을 따라가는 분류기(ApiCallErrorMessages.describe)도 원문(같이 붙은 키 포함)을 꺼낼 수
   * 없어야 한다. 문구는 쿼리 파라미터 값을 인코딩하라고 하지 않는다(WD-200 — 이제 자동 인코딩된다).
   */
  @Test
  void build_invalidBaseUrlQuery_throwsWithoutSecretOrCause() {
    String key = "ab+c/d==키값 Xy9";
    Map<String, String> params = new LinkedHashMap<>();
    params.put("serviceKey", key);

    assertThatThrownBy(
            () -> AuthQueryMasker.build("http://h.example/api?q=a b 한글", params, "serviceKey", key))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause()
        .hasMessageStartingWith("요청 URL 을 만들 수 없습니다")
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContain(key)
                    .doesNotContain(QueryApiKeyEncoding.encode(key))
                    .doesNotContain("a b 한글")
                    .doesNotContain("쿼리 파라미터 값은 URL 인코딩해 넣으세요")
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

  /**
   * 실제로 보낸 형태(공용 규칙 인코딩)도 지운다(WD-182). 섞인 키·{@code *} 가 든 키는 보낸 형태가 원문·form 인코딩형·디코딩형 어느 것과도 달라,
   * 리다이렉트 Location·응답 본문이 보낸 쿼리를 되울리면 예전 형태 목록으로는 놓친다.
   */
  @Test
  void scrubSecret_removesSentFormOfMixedAndStarKeys() {
    String mixed = "abc%2Bd==Zz99";
    String sentMixed = QueryApiKeyEncoding.encode(mixed);
    assertThat(sentMixed).isEqualTo("abc%2Bd%3D%3DZz99");
    assertThat(AuthQueryMasker.scrubSecret("Location: /h?serviceKey=" + sentMixed, mixed))
        .isEqualTo("Location: /h?serviceKey=" + AuthQueryMasker.maskSecret(mixed));

    String star = "ab*cd~ef9876";
    String sentStar = QueryApiKeyEncoding.encode(star);
    assertThat(sentStar).isEqualTo("ab%2Acd~ef9876");
    assertThat(AuthQueryMasker.scrubSecret("echo " + sentStar, star))
        .isEqualTo("echo " + AuthQueryMasker.maskSecret(star));
  }
}
