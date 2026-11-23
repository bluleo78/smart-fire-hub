package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        AuthQueryMasker.build(
            "http://h.example/api", Map.of("serviceKey", encodedKey), "serviceKey", encodedKey);

    assertThat(urls.callUrl()).isEqualTo("http://h.example/api?serviceKey=" + encodedKey);
    assertThat(urls.displayUrl())
        .doesNotContain(encodedKey)
        .doesNotContain("abc+def/==")
        .startsWith("http://h.example/api?serviceKey=****");
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

  // ── WD-657: 응답 본문 비밀 가림(미리보기·연결 테스트 공용) ─────────────────────────────

  /** 비밀이 없는 응답은 원문 형태(들여쓰기·줄바꿈) 그대로이고, 최소 길이(4자) 미만 비밀은 가리지 않는다(평범한 글자를 지우지 않게). */
  @Test
  void maskBody_keepsBodyWhenNothingToMask() {
    String pretty = "{\n  \"a\" : \"x\"\n}";
    assertThat(AuthQueryMasker.maskBody(pretty, "NotThere657")).isEqualTo(pretty);
    assertThat(AuthQueryMasker.maskBody("{\"a\":\"abc\"}", "abc")).isEqualTo("{\"a\":\"abc\"}");
    assertThat(AuthQueryMasker.maskBody(pretty, null)).isEqualTo(pretty);
    // JSON 이 아니면 문자열 가림
    assertThat(AuthQueryMasker.maskBody("<p>Key6571</p>", "Key6571")).isEqualTo("<p>****6571</p>");
  }

  /** 비밀을 일부로 품은 숫자는 숫자 그대로 둔다 — 값 전체가 비밀과 같을 때만 가린다(미리보기 행이 실제 실행과 같은 값·타입이게). */
  @Test
  void maskBody_numberContainingSecretStaysNumber() {
    String secret = "98765432";
    assertThat(AuthQueryMasker.maskBody("{\"n\":1987654321,\"m\":98765432}", secret))
        .isEqualTo("{\"n\":1987654321,\"m\":\"****5432\"}");
  }

  /** 키를 가린 결과가 같은 객체의 다른 키와 겹치면 덮어쓰지 않고 뒤의 것에 꼬리를 붙여 둘 다 남긴다. */
  @Test
  void maskBody_maskedKeyCollisionKeepsBoth() {
    String masked = AuthQueryMasker.maskBody("{\"****abcd\":1,\"KeyXabcd\":2}", "KeyXabcd");
    assertThat(masked).isEqualTo("{\"****abcd\":1,\"****abcd (2)\":2}");
  }

  // ── WD-676: 역슬래시만 있는 본문은 비밀이 실제로 있을 때만 트리를 만든다 ─────────────────────

  /** 스트리밍 판정 — 이스케이프된 비밀(「\/」·유니코드·키)과 정규형이 같은 숫자는 있음, 역슬래시만·깨진 JSON 은 없음. */
  @Test
  void jsonValuesContainSecret_detectsEscapedFormsOnly() {
    assertThat(containsSecret("{\"t\":\"ab\\/cd6761\"}", "ab/cd6761")).as("\\/ 이스케이프 값").isTrue();
    assertThat(containsSecret("{\"t\":\"\\u0041Key6761\"}", "AKey6761"))
        .as("유니코드 이스케이프 값")
        .isTrue();
    assertThat(containsSecret("{\"\\u0041Key6761\":1}", "AKey6761")).as("이스케이프된 키").isTrue();
    assertThat(containsSecret("{\"p\":\"a\\/b\",\"n\":12}", "Zzz96761"))
        .as("역슬래시만 있고 비밀 없음")
        .isFalse();
    assertThat(containsSecret("{\"n\":1e3,\"p\":\"a\\/b\"}", "1E+3"))
        .as("숫자 1e3 과 비밀 1E+3 — BigDecimal 정규형 일치")
        .isTrue();
    assertThat(containsSecret("not json \\ AKey6761", "AKey6761")).as("JSON 아님").isFalse();
  }

  /** 역슬래시만 있고 비밀이 없으면 트리를 만들지 않고 원문을 그대로(같은 객체) 돌려준다 — 줄바꿈·들여쓰기도 그대로다. */
  @Test
  void maskBody_backslashOnlyWithoutSecret_returnsSameString() {
    String pretty = "{\n  \"path\" : \"a\\/b\",\n  \"s\" : \"\\u00e9\"\n}";
    assertThat(AuthQueryMasker.maskBody(pretty, "Zzz96761")).isSameAs(pretty);
  }

  /** 이스케이프된 비밀은 여전히 가린다(트리 경로). */
  @Test
  void maskBody_escapedSecret_stillMasked() {
    String masked = AuthQueryMasker.maskBody("{\"t\":\"x \\u0041Key6761 y\"}", "AKey6761");
    assertThat(masked).isEqualTo("{\"t\":\"x ****6761 y\"}");
  }

  /**
   * 교차 검증 — 같은 입력 표에서 스트리밍 판정이 「있음」인 것과 트리 가림이 실제로 무언가를 바꾸는 것이 정확히 같아야 한다. 어긋나면(판정은 없음인데 트리는 가림)
   * 이스케이프된 비밀이 새어 나간다. 표는 문자열 가림에 바로 걸리지 않는 본문만 담는다 — 그런 본문은 판정 없이 곧장 트리 경로로 가기 때문이다.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"t\":\"ab\\/cd6761\"}|ab/cd6761",
        "{\"t\":\"\\u0041Key6761\"}|AKey6761",
        "{\"\\u0041Key6761\":1}|AKey6761",
        "{\"a\":[{\"b\":\"\\u0041Key6761\"}]}|AKey6761",
        "{\"p\":\"a\\/b\",\"n\":12}|Zzz96761",
        "{\"n\":1e3,\"p\":\"a\\/b\"}|1E+3",
        "{\"n\":1000,\"p\":\"a\\/b\"}|1E+3",
        "{\"n\":12.5,\"p\":\"a\\/b\"}|12.50",
        "[\"\\u0041Key6761\"]|AKey6761",
      })
  void jsonValuesContainSecret_agreesWithTreeMasking(String body, String secret) {
    boolean treeChanges = !AuthQueryMasker.maskBodyViaTree(body, secret).equals(body);
    assertThat(containsSecret(body, secret)).as("%s / %s", body, secret).isEqualTo(treeChanges);
  }

  /**
   * 추가 헤더 값(WD-753)도 API 키 규칙으로 가린다 — JSON 본문(이스케이프 포함)·자유 문장·화면 원문 모두. 같음 비교·toString 에도 들어가되
   * toString 에는 값 없이 개수만 싣는다.
   */
  @Test
  void sentSecrets_extraSecrets_maskJsonBody() {
    AuthQueryMasker.SentSecrets secrets =
        new AuthQueryMasker.SentSecrets(
            "main-key-0001", null, null, java.util.List.of("extra-secret-5678"));

    assertThat(secrets.maskBody("{\"h\":\"extra-secret-5678\"}")).isEqualTo("{\"h\":\"****5678\"}");
    assertThat(secrets.scrubText("echo extra-secret-5678 / main-key-0001"))
        .isEqualTo("echo ****5678 / ****0001");
    assertThat(secrets.maskRawText("raw extra-secret-5678")).isEqualTo("raw ****5678");
    assertThat(secrets.extraSecrets()).containsExactly("extra-secret-5678");
    assertThat(secrets.toString()).doesNotContain("extra-secret-5678").contains("extra=1");
    // 3인자 생성자는 추가 비밀 없음과 같다
    assertThat(new AuthQueryMasker.SentSecrets("main-key-0001", null, null))
        .isEqualTo(
            new AuthQueryMasker.SentSecrets("main-key-0001", null, null, java.util.List.of()))
        .isNotEqualTo(secrets);
  }

  /** 기준 URL 쿼리 값 속 = 는 날것 그대로 잇는다 — httpx 와 같은 바이트(WD-991). */
  @Test
  void build_baseQueryValueWithEquals_keptRaw() {
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build(
            "https://api.example.com/x?serviceKey=abc+d==", Map.of("page", "1"), null, null);

    assertThat(urls.callUrl()).isEqualTo("https://api.example.com/x?serviceKey=abc+d==&page=1");
    assertThat(urls.callUri().getRawQuery()).isEqualTo("serviceKey=abc+d==&page=1");
  }

  /** 기준 쿼리에 = 가 있어도 인증 키는 공용 규칙으로 인코딩해 끝에 붙고 표시 URL 에는 원문이 없다(WD-991). */
  @Test
  void build_baseQueryEquals_withAuthKey_maskedDisplay() {
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build(
            "https://api.example.com/x?serviceKey=abc+d==",
            Map.of("serviceKey2", "k+y=="),
            "serviceKey2",
            "k+y==");

    assertThat(urls.callUrl()).endsWith("&serviceKey2=k%2By%3D%3D");
    assertThat(urls.displayUrl()).doesNotContain("k+y==");
  }

  /** 기준 URL 이 ? 나 & 로 끝나면 구분자를 겹쳐 쓰지 않는다(WD-991). */
  @Test
  void build_trailingQuestionOrAmp_noDoubleSeparator() {
    assertThat(AuthQueryMasker.build("https://a.com/x?", Map.of("page", "1"), null, null).callUrl())
        .isEqualTo("https://a.com/x?page=1");
    assertThat(
            AuthQueryMasker.build("https://a.com/x?a=1&", Map.of("page", "1"), null, null)
                .callUrl())
        .isEqualTo("https://a.com/x?a=1&page=1");
  }

  /** 프래그먼트는 서버로 가지 않으므로 떼고, 파라미터는 # 앞(쿼리)에 붙는다(WD-991). */
  @Test
  void build_fragment_paramsBeforeOrDropped() {
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("https://a.com/x?a=1#f", Map.of("page", "1"), null, null);

    assertThat(urls.callUrl()).doesNotContain("#f");
    assertThat(urls.callUri().getRawQuery()).isEqualTo("a=1&page=1");
  }

  /** 정말 만들 수 없는 기준 URL 은 원문 없는 고정 문구로 거절한다(WD-991). */
  @Test
  void build_unbuildable_throwsFixedMessage() {
    assertThatThrownBy(
            () ->
                AuthQueryMasker.build(
                    "https://a.com/x?secretish=a b", Map.of("page", "1"), null, null))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause()
        .satisfies(
            e -> assertThat(e.getMessage()).doesNotContain("secretish").doesNotContain("a b"));
  }

  /** 스트리밍 판정을 비밀 문자열로 부른다 — 테스트에서 쓰는 비밀은 모두 최소 길이 이상이라 Scrubber 가 있다. */
  private static boolean containsSecret(String body, String secret) {
    return AuthQueryMasker.jsonValuesContainSecret(body, AuthQueryMasker.Scrubber.of(secret));
  }

  /** 호출 URL 의 경로·쿼리에 인코딩 안 된 비ASCII 가 있으면 거절한다(WD-991 리뷰) — 호출자는 RequestUrlEncoding 으로 미리 인코딩한다. */
  @Test
  void build_rawKoreanInQuery_throws() {
    assertThatThrownBy(() -> AuthQueryMasker.build("https://a.com/x?q=서울", Map.of(), null, null))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause();
  }

  /** 경로의 날것 한글도 거절한다. */
  @Test
  void build_rawKoreanInPath_throws() {
    assertThatThrownBy(() -> AuthQueryMasker.build("https://a.com/서울?q=1", Map.of(), null, null))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause();
  }

  /** 쿼리의 날것 대괄호는 거절한다(예전 build(true) 와 같다). */
  @Test
  void build_rawBracketsInQuery_throws() {
    assertThatThrownBy(() -> AuthQueryMasker.build("https://a.com/x?a[0]=1", Map.of(), null, null))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause();
  }

  /** authority 의 대괄호(IPv6 호스트)는 거절하지 않는다. */
  @Test
  void build_ipv6Host_withRawEqualsInQuery_builds() {
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("https://[::1]:8080/x?a=b==", Map.of(), null, null);
    assertThat(urls.callUrl()).isEqualTo("https://[::1]:8080/x?a=b==");
  }

  /** 기준 쿼리의 날것 '=' 는 표시 URL 에도 그대로 남는다. */
  @Test
  void build_rawEqualsInBase_displayUrlKeepsIt() {
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build(
            "https://a.com/x?serviceKey=abc+d==", Map.of("page", "1"), null, null);
    assertThat(urls.displayUrl()).isEqualTo("https://a.com/x?serviceKey=abc+d==&page=1");
  }

  /** 표시 URL 도 호출 URL 처럼 프래그먼트를 뗀다. */
  @Test
  void build_fragment_strippedFromDisplayUrl() {
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("https://a.com/x?a=1#f", Map.of("page", "1"), null, null);
    assertThat(urls.displayUrl()).doesNotContain("#f").isEqualTo(urls.callUrl());
  }

  /**
   * 경로가 {@code &} 로 끝나도 쿼리가 없으면 {@code ?} 로 잇는다(WD-991 리뷰) — 예전엔 {@code &} 를 쿼리 구분자로 착각해 인증 키가 경로에
   * 붙었다({@code /x&page=1&serviceKey=…}). Python {@code _build_request_url} 도 {@code /x&?…} 다.
   */
  @Test
  void build_pathEndingWithAmp_noQuery_usesQuestionMark() {
    Map<String, String> params = new java.util.LinkedHashMap<>();
    params.put("page", "1");
    params.put("serviceKey", "secretKey991");
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("https://a.com/x&", params, "serviceKey", "secretKey991");
    assertThat(urls.callUrl()).isEqualTo("https://a.com/x&?page=1&serviceKey=secretKey991");
    assertThat(urls.displayUrl()).isEqualTo("https://a.com/x&?page=1&serviceKey=****y991");
  }

  /** 표시 URL 은 보낸 URL 과 같은 문자열에서 키만 가린다(WD-991 리뷰) — 같은 이름 파라미터를 묶거나 빈 {@code &&} 를 버리지 않는다. */
  @Test
  void build_displayUrl_sameShapeAsCallUrl() {
    Map<String, String> params = new java.util.LinkedHashMap<>();
    params.put("page", "2");
    params.put("serviceKey", "secretKey991");
    AuthQueryMasker.AuthQueryUrls urls =
        AuthQueryMasker.build("https://a.com/x?page=1&&a=1", params, "serviceKey", "secretKey991");
    assertThat(urls.callUrl())
        .isEqualTo("https://a.com/x?page=1&&a=1&page=2&serviceKey=secretKey991");
    assertThat(urls.displayUrl())
        .isEqualTo("https://a.com/x?page=1&&a=1&page=2&serviceKey=****y991");
  }

  /** 인코딩 안 된 비ASCII 호스트는 같은 고정 문구로 거절한다(WD-991 리뷰 — 예전 build(true) 와 같다). */
  @Test
  void build_rawKoreanHost_throws() {
    assertThatThrownBy(
            () -> AuthQueryMasker.build("https://소방.kr/x", Map.of("page", "1"), null, null))
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause();
  }

  /**
   * 빌더의 검사를 거치지 않은 URL(쿼리 인증이 아닌 헬스체크 등)도 callUri() 가 원문 없는 고정 문구로 거절한다(WD-991 리뷰) — 예전엔 URI.create
   * 의 IllegalArgumentException 이 원문 URL 을 싣고 나갔다.
   */
  @Test
  void callUri_unparseable_throwsFixedMessage() {
    AuthQueryMasker.AuthQueryUrls urls =
        new AuthQueryMasker.AuthQueryUrls("https://a.com/x?q=sec\u0001ret", "shown");
    assertThatThrownBy(urls::callUri)
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sec"));
  }

  /** 빌더를 거치지 않은 URL(쿼리 인증이 아닌 헬스체크)도 callUri() 가 비ASCII 호스트를 같은 고정 문구로 거절한다(WD-991 리뷰). */
  @Test
  void callUri_rawKoreanHost_throwsFixedMessage() {
    AuthQueryMasker.AuthQueryUrls urls =
        new AuthQueryMasker.AuthQueryUrls("https://소방.kr/health", "shown");
    assertThatThrownBy(urls::callUri)
        .isInstanceOf(AuthQueryMasker.InvalidRequestUrlException.class)
        .hasNoCause();
  }
}
