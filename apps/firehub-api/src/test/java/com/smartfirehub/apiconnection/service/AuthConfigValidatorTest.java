package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.pipeline.service.executor.ApiCallAuth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AuthConfigValidator} 의 Bearer 토큰 정규화 단위 테스트(WD-623).
 *
 * <p>토큰 칸에 접두어만 넣은 값(「Bearer 」)은 예전에 strip 뒤 「Bearer」 가 남아 접두어 패턴(뒤에 공백 필수)에 걸리지 않고 그대로 저장됐다 — 실제
 * 호출은 「Authorization: Bearer Bearer」 로 나갔다. 접두어만 있으면 빈 토큰이 돼야 비밀 필드 검사가 거절할 수 있다.
 */
class AuthConfigValidatorTest {

  /**
   * 접두어만 있는 토큰(대소문자·앞뒤 공백·겹친 접두어 무관)은 빈 토큰으로 정리되고, 그래서 저장 검사는 「Bearer 토큰을 입력하세요」 로 거절한다 — 정리와 빈 값
   * 판정이 같은 헬퍼를 쓴다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "Bearer",
        "bearer",
        "BEARER",
        "Bearer   ",
        "  bearer\t",
        "Bearer Bearer ",
        "bearer BEARER"
      })
  void prefixOnlyToken_normalizesToEmpty(String token) {
    assertThat(AuthConfigValidator.normalizeBearerToken(token)).isEmpty();
    assertThatThrownBy(
            () -> AuthConfigValidator.validateAndNormalize("BEARER", Map.of("token", token)))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
  }

  /** 접두어 + 실제 토큰은 접두어만 떼고, 「bearer」 로 시작하는 단어 자체인 토큰은 건드리지 않는다(회귀). */
  @ParameterizedTest
  @CsvSource({
    "'Bearer abc123', abc123",
    "'Bearer Bearer abc', abc",
    "'Bearer  bearer   abc', abc",
    "'Bearer bearerXYZ', bearerXYZ",
    "'bearer   abc123  ', abc123",
    "'  abc123 ', abc123",
    "bearerXYZ, bearerXYZ",
    "Bearer-token, Bearer-token"
  })
  void tokenWithPrefixOrBearerLikeWord_normalizedAsBefore(String token, String expected) {
    Map<String, String> normalized =
        AuthConfigValidator.validateAndNormalize("BEARER", Map.of("token", token));

    assertThat(normalized).containsEntry("token", expected);
  }

  /** API_KEY 의 키 값은 정리 대상이 아니다 — 「Bearer」 문자열이라도 그대로 둔다. */
  @Test
  void apiKeyValue_notTouched() {
    Map<String, String> normalized =
        AuthConfigValidator.validateAndNormalize(
            "API_KEY", Map.of("headerName", "X-Key", "apiKey", "Bearer"));

    assertThat(normalized).containsEntry("apiKey", "Bearer");
  }

  /** 비밀 필드가 비면(정리한 뒤 기준) 헤더 이름·줄바꿈 등 다른 사유보다 「… 입력하세요」 가 먼저다(WD-623). config 가 null 이어도 같다. */
  @Test
  void validateAndNormalize_missingSecretComesFirst() {
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "API_KEY", Map.of("placement", "header", "headerName", "X API Key")))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("API 키를 입력하세요 (authConfig.apiKey)");
    assertThatThrownBy(() -> AuthConfigValidator.validateAndNormalize("BEARER", null))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("Bearer 토큰을 입력하세요 (authConfig.token)");
  }

  /**
   * 저장된 값 검사({@link AuthConfigValidator#describeProblem})도 빈 비밀(없음·공백·접두어만)을 사유로 돌려준다 — 예전 버전에서 빈
   * 비밀로 저장된 연결을 연결 테스트·주기 점검이 빈 인증으로 호출하지 않게 한다(WD-623).
   */
  @Test
  void describeProblem_reportsMissingSecretForStoredConfig() {
    Map<String, String> emptyToken = new HashMap<>();
    emptyToken.put("token", "");
    assertThat(AuthConfigValidator.describeProblem("BEARER", emptyToken))
        .isEqualTo("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThat(AuthConfigValidator.describeProblem("BEARER", Map.of("token", "Bearer  ")))
        .isEqualTo("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThat(AuthConfigValidator.describeProblem("BEARER", null))
        .isEqualTo("Bearer 토큰을 입력하세요 (authConfig.token)");
    assertThat(
            AuthConfigValidator.describeProblem(
                "API_KEY", Map.of("placement", "query", "paramName", "k", "apiKey", "  ")))
        .isEqualTo("API 키를 입력하세요 (authConfig.apiKey)");
    // 정상 값·비밀 필드가 정해지지 않은 방식은 문제 없음
    assertThat(AuthConfigValidator.describeProblem("BEARER", Map.of("token", "abc"))).isNull();
    assertThat(
            AuthConfigValidator.describeProblem(
                "API_KEY", Map.of("headerName", "X-Key", "apiKey", "k")))
        .isNull();
    assertThat(AuthConfigValidator.describeProblem("NONE", Map.of())).isNull();
  }

  // ── WD-639: 가린 값 판정·비교용 정리·API_CALL 빈 비밀 ─────────────────────────

  /** 가린 값 판정은 앞뒤 공백과(토큰 필드면) 「Bearer」 접두어를 뗀 값으로 한다 — 실제 값·「bearer****」 같은 토큰은 가린 값이 아니다. */
  @Test
  void isMaskedSecret_ignoresPaddingAndBearerPrefixOnlyForToken() {
    assertThat(AuthConfigValidator.isMaskedSecret("token", "Bearer ****abcd")).isTrue();
    assertThat(AuthConfigValidator.isMaskedSecret("token", " bearer bearer ****abcd ")).isTrue();
    assertThat(AuthConfigValidator.isMaskedSecret("apiKey", "\t****abcd ")).isTrue();
    // 접두어 떼기는 토큰 필드에만 — API 키 「Bearer ****…」 는 키 자체로 본다
    assertThat(AuthConfigValidator.isMaskedSecret("apiKey", "Bearer ****abcd")).isFalse();
    assertThat(AuthConfigValidator.isMaskedSecret("token", "bearer****abcd")).isFalse();
    assertThat(AuthConfigValidator.isMaskedSecret("token", "real-token")).isFalse();
    assertThat(AuthConfigValidator.isMaskedSecret("token", null)).isFalse();
    assertThat(AuthConfigValidator.isSecretMasked(null, Map.of("token", "****abcd"))).isFalse();
  }

  /** 비교용 사본은 authType 키를 빼고, 토큰을 정리하고, API_KEY 헤더 기본값을 채운다 — 뜻이 같으면 같다. */
  @Test
  void comparableConfig_sameMeaningIsEqual() {
    assertThat(
            AuthConfigValidator.comparableConfig(
                "API_KEY", Map.of("authType", "API_KEY", "apiKey", "k")))
        .isEqualTo(
            AuthConfigValidator.comparableConfig(
                "API_KEY",
                Map.of("placement", "header", "headerName", "X-API-Key", "apiKey", "k")));
    assertThat(AuthConfigValidator.comparableConfig("BEARER", Map.of("token", " Bearer t ")))
        .isEqualTo(AuthConfigValidator.comparableConfig("BEARER", Map.of("token", "t")));
    // 쿼리 방식에는 헤더 기본값을 채우지 않는다 · 값이 다르면 다르다
    assertThat(
            AuthConfigValidator.comparableConfig(
                "API_KEY", Map.of("placement", "query", "paramName", "k", "apiKey", "v")))
        .doesNotContainKey("headerName");
    assertThat(AuthConfigValidator.comparableConfig("BEARER", Map.of("token", "a")))
        .isNotEqualTo(AuthConfigValidator.comparableConfig("BEARER", Map.of("token", "b")));
  }

  // ── WD-681: BASIC(아이디·비밀번호) ──────────────────────────────────────────────

  /** BASIC 은 아이디·비밀번호 둘 다 있어야 하고, 아이디부터 본다. 값은 정리하지 않고 그대로 둔다. */
  @Test
  void basic_requiresUsernameThenPassword() {
    assertThatThrownBy(() -> AuthConfigValidator.validateAndNormalize("BASIC", Map.of()))
        .isExactlyInstanceOf(ApiConnectionException.class)
        .hasMessage("아이디를 입력하세요 (authConfig.username)");
    assertThatThrownBy(
            () -> AuthConfigValidator.validateAndNormalize("BASIC", Map.of("username", "  ")))
        .hasMessage("아이디를 입력하세요 (authConfig.username)");
    assertThatThrownBy(
            () -> AuthConfigValidator.validateAndNormalize("BASIC", Map.of("username", "alice")))
        .hasMessage("비밀번호를 입력하세요 (authConfig.password)");
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "BASIC", Map.of("username", "alice", "password", "")))
        .hasMessage("비밀번호를 입력하세요 (authConfig.password)");
    assertThatThrownBy(() -> AuthConfigValidator.validateAndNormalize("BASIC", null))
        .hasMessage("아이디를 입력하세요 (authConfig.username)");

    Map<String, String> ok =
        AuthConfigValidator.validateAndNormalize(
            "BASIC", Map.of("username", "alice", "password", " p@ss "));
    assertThat(ok).containsEntry("username", "alice").containsEntry("password", " p@ss ");
  }

  /** 아이디의 콜론은 Basic 인증에서 비밀번호로 읽히고, 줄바꿈은 헤더를 깬다 — 저장 전에 거절한다. */
  @Test
  void basic_rejectsColonInUsernameAndLineBreaks() {
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "BASIC", Map.of("username", "a:b", "password", "pw")))
        .hasMessage("아이디에 콜론(:)을 넣을 수 없습니다 — Basic 인증 아이디에는 콜론을 쓸 수 없습니다");
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "BASIC", Map.of("username", "a\nb", "password", "pw")))
        .hasMessage("아이디에 줄바꿈을 넣을 수 없습니다 — 한 줄로 입력하세요");
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "BASIC", Map.of("username", "alice", "password", "p\rw")))
        .hasMessage("비밀번호에 줄바꿈을 넣을 수 없습니다 — 한 줄로 입력하세요");
  }

  /** 비밀 필드 표에 BASIC → password — 가린 값 판정·빈 비밀 판정이 같은 표를 쓴다. */
  @Test
  void basic_secretFieldIsPassword() {
    assertThat(AuthConfigValidator.REQUIRED_SECRET_BY_AUTH_TYPE).containsEntry("BASIC", "password");
    assertThat(AuthConfigValidator.isSecretMasked("BASIC", Map.of("password", "****abcd")))
        .isTrue();
    assertThat(AuthConfigValidator.isSecretMissing("BASIC", Map.of("username", "a"))).isTrue();
  }

  /** 저장된 값 검사(연결 테스트)도 보내는 값으로 같은 문구를 쓴다. */
  @Test
  void basic_describeSentValueProblem_usesSameMessages() {
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "BASIC", Map.of("password", "pw")),
                null))
        .isEqualTo("아이디를 입력하세요 (authConfig.username)");
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "BASIC", Map.of("username", "alice")),
                null))
        .isEqualTo("비밀번호를 입력하세요 (authConfig.password)");
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "BASIC", Map.of("username", "alice", "password", "pw")),
                null))
        .isNull();
  }

  // -------------------------------------------------------------------------
  // WD-753: 추가 인증 헤더(extraHeader:<이름>) 모으기·검증
  // -------------------------------------------------------------------------

  /** 접두어 키만 모으고 이름의 앞뒤 공백을 뗀다 — 접두어 없는 키(apiKey)는 추가 헤더가 아니다. */
  @Test
  void extraHeaders_collectsPrefixedKeysStrippingName() {
    Map<String, String> config = new HashMap<>();
    config.put("apiKey", "k");
    config.put("extraHeader: X-Id ", "v1");
    config.put("extraHeader:X-B", "v2");

    Map<String, String> extra = AuthConfigValidator.extraHeaders(config);

    assertThat(extra).hasSize(2).containsEntry("X-B", "v2").containsEntry("X-Id", "v1");
    assertThat(AuthConfigValidator.extraHeaders(null)).isEmpty();
  }

  /** 이름이 빈 접두어 키도 빈 이름으로 모아 검증이 거절하게 둔다(조용히 버리면 저장한 값이 사라진다). */
  @Test
  void extraHeadersProblem_emptyName() {
    Map<String, String> extra = AuthConfigValidator.extraHeaders(Map.of("extraHeader:", "v1234"));
    assertThat(AuthConfigValidator.extraHeadersProblem(extra, null)).isEqualTo("추가 헤더 이름을 입력하세요");
  }

  /** 이름 규칙은 인증 헤더 이름과 같다(HTTP 토큰·예약 헤더) — 문구 앞에 「추가 헤더 」 를 붙인다. */
  @Test
  void extraHeadersProblem_invalidAndReservedName() {
    assertThat(AuthConfigValidator.extraHeadersProblem(Map.of("X Id", "secret-val-1"), null))
        .startsWith("추가 헤더 헤더 이름이 올바르지 않습니다")
        .doesNotContain("secret-val-1");
    assertThat(AuthConfigValidator.extraHeadersProblem(Map.of("Host", "secret-val-2"), null))
        .startsWith("추가 헤더 헤더 이름으로 Host 은(는) 쓸 수 없습니다")
        .doesNotContain("secret-val-2");
  }

  /** 추가 헤더끼리 대소문자만 다른 이름은 같은 헤더라 거절한다 — 같은 헤더가 두 번 나간다. */
  @Test
  void extraHeadersProblem_duplicateNameIgnoringCase() {
    Map<String, String> config = new HashMap<>();
    config.put("extraHeader:X-Id", "dup-value-1");
    config.put("extraHeader:x-id", "dup-value-2");
    String problem =
        AuthConfigValidator.extraHeadersProblem(AuthConfigValidator.extraHeaders(config), null);
    assertThat(problem)
        .startsWith("추가 헤더 이름이 겹칩니다: ")
        .doesNotContain("dup-value-1")
        .doesNotContain("dup-value-2");
  }

  /** 메인 인증 헤더와 같은 이름(대소문자 무시)은 거절한다 — BEARER·BASIC 은 Authorization, API_KEY 헤더는 정한 이름. */
  @Test
  void extraHeadersProblem_sameAsMainAuthHeader() {
    assertThat(
            AuthConfigValidator.describeProblem(
                "BEARER", Map.of("token", "tok12345", "extraHeader:authorization", "abc-9999")))
        .isEqualTo("추가 헤더 authorization 은(는) 인증 헤더와 이름이 같습니다 — 인증 칸에 이미 있는 헤더입니다");
    assertThat(
            AuthConfigValidator.describeProblem(
                "API_KEY",
                Map.of(
                    "placement",
                    "header",
                    "headerName",
                    "X-NCP-APIGW-API-KEY",
                    "apiKey",
                    "secretkey123",
                    "extraHeader:x-ncp-apigw-api-key",
                    "abc-9999")))
        .isEqualTo("추가 헤더 x-ncp-apigw-api-key 은(는) 인증 헤더와 이름이 같습니다 — 인증 칸에 이미 있는 헤더입니다")
        .doesNotContain("abc-9999");
  }

  /** 값이 비었거나(공백만) 줄바꿈이 있으면 거절한다 — 문구에 값은 싣지 않는다. */
  @Test
  void extraHeadersProblem_blankOrMultilineValue() {
    assertThat(AuthConfigValidator.extraHeadersProblem(Map.of("X-Id", " "), null))
        .isEqualTo("추가 헤더 X-Id 값을 입력하세요");
    assertThat(AuthConfigValidator.extraHeadersProblem(Map.of("X-Id", "a\nb"), null))
        .isEqualTo("추가 헤더 X-Id 값에 줄바꿈을 넣을 수 없습니다 — 한 줄로 입력하세요");
  }

  /** 11개 이상이면 개수부터 거절한다. */
  @Test
  void extraHeadersProblem_moreThanTen() {
    Map<String, String> extra = new java.util.LinkedHashMap<>();
    for (int i = 0; i < AuthConfigValidator.MAX_EXTRA_HEADERS + 1; i++) {
      extra.put("X-H" + i, "value-" + i);
    }
    assertThat(AuthConfigValidator.extraHeadersProblem(extra, null))
        .isEqualTo("추가 헤더는 최대 10개까지 넣을 수 있습니다");
    assertThat(AuthConfigValidator.EXTRA_HEADER_PREFIX).isEqualTo("extraHeader:");
  }

  /** 네이버 API HUB 모양 — API 키 헤더 + 키 ID 추가 헤더는 문제없다(저장 검사·보내는 값 검사 모두). */
  @Test
  void describeProblem_apiKeyWithExtraHeader_ok() {
    Map<String, String> config =
        Map.of(
            "placement",
            "header",
            "headerName",
            "X-NCP-APIGW-API-KEY",
            "apiKey",
            "secretkey123",
            "extraHeader:X-NCP-APIGW-API-KEY-ID",
            "abcd1234");
    assertThat(AuthConfigValidator.describeProblem("API_KEY", config)).isNull();
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "API_KEY", config),
                null))
        .isNull();
  }

  /** 보내는 값 검사(연결 테스트·상태 점검)도 추가 헤더를 본다 — 메인 인증이 문제없어도 추가 헤더 값이 비면 거절. */
  @Test
  void describeSentValueProblem_checksExtraHeaders() {
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "BEARER", Map.of("token", "tok12345", "extraHeader:X-Tenant", "")),
                null))
        .isEqualTo("추가 헤더 X-Tenant 값을 입력하세요");
  }

  /**
   * 원래 키가 앞뒤 공백만 다른 추가 헤더(WD-753 리뷰)는 모으면 한 이름으로 합쳐져 한쪽 값이 말없이 사라진다 — 저장 검사가 원래 키를 보고 겹침으로
   * 거절한다(대소문자 무시).
   */
  @Test
  void describeProblem_rawKeysCollideAfterStrip_rejectedAsDuplicate() {
    Map<String, String> spaced = new HashMap<>();
    spaced.put("token", "tok12345");
    spaced.put("extraHeader: X-A", "first-value-1");
    spaced.put("extraHeader:X-A", "second-value-2");
    assertThat(AuthConfigValidator.describeProblem("BEARER", spaced))
        .isEqualTo("추가 헤더 이름이 겹칩니다: X-A")
        .doesNotContain("first-value-1")
        .doesNotContain("second-value-2");

    Map<String, String> spacedAndCase = new HashMap<>();
    spacedAndCase.put("token", "tok12345");
    spacedAndCase.put("extraHeader:X-A ", "first-value-1");
    spacedAndCase.put("extraHeader:x-a", "second-value-2");
    assertThat(AuthConfigValidator.describeProblem("BEARER", spacedAndCase))
        .startsWith("추가 헤더 이름이 겹칩니다: ");
  }

  /** 추가 헤더 키는 이름과 상관없이 비밀 필드다 — 응답에서 API 키 규칙(****+끝 4자)으로 가린다. */
  @Test
  void isSensitiveKey_extraHeaderPrefix() {
    assertThat(ApiConnectionReader.isSensitiveKey("extraHeader:X-Tenant")).isTrue();
    assertThat(ApiConnectionReader.isSensitiveKey("headerName")).isFalse();
  }

  /**
   * 저장 정리는 추가 헤더 키를 앞뒤 공백을 뗀 이름으로 다시 쓴다(WD-753 리뷰) — 화면은 정리한 이름으로 가린 값을 되돌려 보내므로 저장 키가 같아야 저장값을 유지할
   * 수 있다. 값도 앞뒤 공백을 뗀다(WD-772).
   */
  @Test
  void validateAndNormalize_rewritesExtraHeaderKeyToStrippedName() {
    Map<String, String> normalized =
        AuthConfigValidator.validateAndNormalize(
            "BEARER", Map.of("token", "tok12345", "extraHeader: X-Id ", " keyid-4567"));

    assertThat(normalized)
        .containsEntry("extraHeader:X-Id", "keyid-4567")
        .doesNotContainKey("extraHeader: X-Id ")
        .containsEntry("token", "tok12345");
  }

  // -------------------------------------------------------------------------
  // WD-772: 추가 인증 헤더 값 앞뒤 공백·접두어 대소문자
  // -------------------------------------------------------------------------

  /**
   * 저장 정리는 추가 헤더 값의 앞뒤 공백을 뗀다(WD-772) — 그대로 저장하면 연결 확인(Java)은 통과하고 Python 실행기(httpx)만 「Illegal
   * header value」 로 실패했다. 값 가운데 공백은 값의 일부라 그대로 둔다.
   */
  @Test
  void validateAndNormalize_stripsExtraHeaderValueKeepingInnerSpaces() {
    Map<String, String> normalized =
        AuthConfigValidator.validateAndNormalize(
            "BEARER", Map.of("token", "tok12345", "extraHeader:X-Id", " \tkey id 4567 \t"));

    assertThat(normalized).containsEntry("extraHeader:X-Id", "key id 4567");
  }

  /** 공백만인 추가 헤더 값은 자른 뒤 빈 값이라 지금처럼 「값을 입력하세요」 로 거절한다(WD-772). */
  @Test
  void validateAndNormalize_whitespaceOnlyExtraHeaderValue_rejected() {
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "BEARER", Map.of("token", "tok12345", "extraHeader:X-Id", " \t ")))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessage("추가 헤더 X-Id 값을 입력하세요");
  }

  /**
   * 접두어는 대소문자와 상관없이 알아본다(WD-772) — 「ExtraHeader:X-Id」 는 예전에 저장은 되지만 보내지지도 가려지지도 않았다. 메인 키·접두어 비슷한
   * 키는 추가 헤더가 아니다.
   */
  @ParameterizedTest
  @CsvSource({
    "'extraHeader:X-Id', true",
    "'ExtraHeader:X-Id', true",
    "'extraheader:X-Id', true",
    "'EXTRAHEADER: X-Id', true",
    "'ExtraHeader:', true",
    "'apiKey', false",
    "'token', false",
    "'extraHeader', false",
    "'extraHeaders:X-Id', false",
    // 앞 공백이 붙은 키는 접두어로 시작하지 않는다 — 따옴표로 감싸 공백을 살린다.
    "' extraHeader:X-Id', false",
    "'headerExtra:X-Id', false"
  })
  void isExtraHeaderKey_ignoresPrefixCase(String key, boolean expected) {
    assertThat(AuthConfigValidator.isExtraHeaderKey(key)).isEqualTo(expected);
  }

  /** 키가 null 이면 추가 헤더가 아니다(CsvSource 로는 null 을 깔끔히 넣기 어려워 따로 둔다). */
  @Test
  void isExtraHeaderKey_null_false() {
    assertThat(AuthConfigValidator.isExtraHeaderKey(null)).isFalse();
  }

  /** 저장 정리는 대소문자가 다른 접두어를 정식 「extraHeader:」 로 바꾸고, 이름 앞뒤 공백도 뗀다(WD-772). */
  @Test
  void validateAndNormalize_rewritesPrefixCaseToCanonical() {
    Map<String, String> config = new HashMap<>();
    config.put("token", "tok12345");
    config.put("ExtraHeader:X-Id", "keyid-4567");
    config.put("EXTRAHEADER: X-A", "a-value-1");
    config.put("extraheader:X-B", "b-value-2");

    Map<String, String> normalized = AuthConfigValidator.validateAndNormalize("BEARER", config);

    assertThat(normalized)
        .containsEntry("extraHeader:X-Id", "keyid-4567")
        .containsEntry("extraHeader:X-A", "a-value-1")
        .containsEntry("extraHeader:X-B", "b-value-2")
        .doesNotContainKeys("ExtraHeader:X-Id", "EXTRAHEADER: X-A", "extraheader:X-B")
        .containsEntry("token", "tok12345");
    assertThat(AuthConfigValidator.normalizeExtraHeaderKey("ExtraHeader: X-A "))
        .isEqualTo("extraHeader:X-A");
  }

  /** 접두어 대소문자만 다른 두 키는 같은 헤더라 겹침으로 거절한다(WD-772) — 합치면 한쪽 값이 말없이 사라진다. */
  @Test
  void describeProblem_prefixCaseOnlyDiffers_rejectedAsDuplicate() {
    Map<String, String> config = new HashMap<>();
    config.put("token", "tok12345");
    config.put("ExtraHeader:X-A", "first-value-1");
    config.put("extraHeader:X-A", "second-value-2");

    assertThat(AuthConfigValidator.describeProblem("BEARER", config))
        .isEqualTo("추가 헤더 이름이 겹칩니다: X-A")
        .doesNotContain("first-value-1")
        .doesNotContain("second-value-2");
  }

  /** 대소문자가 다른 접두어만 있고 이름이 없으면 「추가 헤더 이름을 입력하세요」 다(WD-772) — 조용히 버리지 않는다. */
  @Test
  void describeProblem_mixedCasePrefixWithoutName_rejected() {
    assertThat(
            AuthConfigValidator.describeProblem(
                "BEARER", Map.of("token", "tok12345", "ExtraHeader:", "v1234")))
        .isEqualTo("추가 헤더 이름을 입력하세요");
  }

  /** 모으기(전송·검사 공용)는 대소문자가 다른 접두어도 모으고, 값의 앞뒤 공백을 뗀다(WD-772) — 정리 전에 저장된 값도 잘린 값으로 나간다. */
  @Test
  void extraHeaders_collectsMixedCasePrefixAndStripsValue() {
    Map<String, String> config = new HashMap<>();
    config.put("apiKey", "k");
    config.put("ExtraHeader: X-Id", " keyid 4567 ");
    config.put("extraheader:X-B", "v2\t");

    assertThat(AuthConfigValidator.extraHeaders(config))
        .hasSize(2)
        .containsEntry("X-Id", "keyid 4567")
        .containsEntry("X-B", "v2");
  }

  /** 대소문자가 다른 접두어 키도 이름과 상관없이 비밀 필드다(WD-772) — 예전에는 「X-Id」 처럼 이름에 key·token 이 없으면 평문으로 돌아갔다. */
  @Test
  void isSensitiveKey_mixedCaseExtraHeaderPrefix() {
    assertThat(ApiConnectionReader.isSensitiveKey("ExtraHeader:X-Id")).isTrue();
    assertThat(ApiConnectionReader.isSensitiveKey("extraheader:X-Id")).isTrue();
  }

  /** 변경 비교는 값 앞뒤 공백·접두어 대소문자만 다른 저장을 같은 설정으로 본다(WD-772) — 보내는 헤더가 같다. */
  @Test
  void comparableConfig_normalizesExtraHeaderValueAndPrefixCase() {
    assertThat(
            AuthConfigValidator.comparableConfig(
                "BEARER", Map.of("token", "t", "ExtraHeader:X-Id", " v1 ")))
        .isEqualTo(
            AuthConfigValidator.comparableConfig(
                "BEARER", Map.of("token", "t", "extraHeader:X-Id", "v1")));
  }

  /** 추가 헤더 값에 ASCII 밖 글자가 있을 때의 안내(WD-772 리뷰) — 값 원문은 싣지 않는다. */
  private static final String NON_ASCII_X_ID =
      "추가 헤더 X-Id 값에는 영문·숫자·기호(ASCII)만 쓸 수 있습니다 — 한글·특수 공백(NBSP 등)·제어 문자는 넣을 수 없습니다";

  /**
   * 추가 헤더 값에 출력 가능한 ASCII(0x20~0x7E)·탭 밖 글자가 있으면 거절한다(WD-772 리뷰) — Java {@code strip()} 은 NBSP 를
   * 공백으로 보지 않아 남기는데, 연결 확인(Java)은 통과하고 Python 실행기(httpx)는 비ASCII 헤더 값을 만들지 못해 실패했다.
   * 한글·NBSP(가운데·앞뒤)·제어 문자(DEL 포함)가 모두 같은 안내다.
   */
  @Test
  void extraHeadersProblem_nonAsciiOrControlValue_rejected() {
    for (String value : List.of("key id", " keyid ", "키아이디", "key\u0001id", "key\u007fid")) {
      assertThat(AuthConfigValidator.extraHeadersProblem(Map.of("X-Id", value), null))
          .as("value %s", value.codePoints().boxed().toList())
          .isEqualTo(NON_ASCII_X_ID)
          .doesNotContain(value);
    }
  }

  /**
   * 값 가운데 탭·공백·ASCII 기호는 HTTP 헤더 값으로 쓸 수 있어 통과한다(WD-772 리뷰 — RFC 9110 field-content 는 HTAB 을 허용).
   */
  @Test
  void extraHeadersProblem_asciiWithInnerTab_allowed() {
    assertThat(
            AuthConfigValidator.extraHeadersProblem(
                Map.of("X-Id", "key\tid 4567 ~!@#$%^&*()_+-={}[]|\\:;\"'<>,.?/`"), null))
        .isNull();
  }

  /** 줄바꿈은 지금처럼 줄바꿈 안내가 먼저다(WD-772 리뷰) — 더 구체적인 안내라 ASCII 검사보다 앞에 둔다. */
  @Test
  void extraHeadersProblem_lineBreakStillLineBreakMessage() {
    assertThat(AuthConfigValidator.extraHeadersProblem(Map.of("X-Id", "key\nid"), null))
        .isEqualTo("추가 헤더 X-Id 값에 줄바꿈을 넣을 수 없습니다 — 한 줄로 입력하세요");
  }

  /** 저장 검사는 앞뒤 공백을 정리한 뒤에도 남는 비ASCII 값을 400 으로 거절한다(WD-772 리뷰). */
  @Test
  void validateAndNormalize_nonAsciiExtraHeaderValue_rejected() {
    assertThatThrownBy(
            () ->
                AuthConfigValidator.validateAndNormalize(
                    "BEARER", Map.of("token", "tok12345", "extraHeader:X-Id", " keyid 4567 ")))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessage(NON_ASCII_X_ID);
  }

  /**
   * 이미 저장된 비ASCII 값은 보내는 값 검사(연결 테스트·상태 점검)와 API_CALL 호출 전 검사가 같은 안내로 잡는다(WD-772 리뷰) — 호출하지 않으므로
   * 「Java 는 성공, Python 은 실패」 가 생기지 않는다.
   */
  @Test
  void sentValueAndCallProblem_storedNonAsciiExtraHeaderValue_rejected() {
    Map<String, String> stored =
        Map.of("authType", "BEARER", "token", "tok12345", "extraHeader:X-Id", "keyid 4567");
    ApiCallAuth.ResolvedAuth auth = ApiCallAuth.resolve(stored);

    assertThat(AuthConfigValidator.describeSentValueProblem(auth, stored))
        .isEqualTo(NON_ASCII_X_ID);
    assertThat(ApiCallAuth.describeProblem(auth, stored, "WD772"))
        .isEqualTo("API 연결 「WD772」의 " + NON_ASCII_X_ID + " — API 연결 화면에서 이 연결의 추가 헤더를 고치세요.");
  }

  /** 접두어 거르기 공용 helper — 추가 헤더 키만 원래 키 그대로 키 정렬 순서로 담는다. null·null 키는 건너뛴다. */
  @Test
  void rawExtraHeaderEntries_keepsRawKeysSorted() {
    Map<String, String> config = new HashMap<>();
    config.put("apiKey", "k");
    config.put("extraHeader:X-B", "v2");
    config.put("extraHeader: X-A", "v1");
    config.put(null, "x");

    assertThat(AuthConfigValidator.rawExtraHeaderEntries(config))
        .containsExactly(Map.entry("extraHeader: X-A", "v1"), Map.entry("extraHeader:X-B", "v2"));
    assertThat(AuthConfigValidator.rawExtraHeaderEntries(null)).isEmpty();
    assertThat(AuthConfigValidator.normalizeExtraHeaderKey("extraHeader: X-A "))
        .isEqualTo("extraHeader:X-A");
    assertThat(AuthConfigValidator.normalizeExtraHeaderKey("apiKey")).isEqualTo("apiKey");
  }

  /** 변경 비교는 추가 헤더 키를 정리해 맞춘다 — 키 모양만 다르고 값이 같으면 같은 설정이다(감사 credentialsChanged 오판 방지). */
  @Test
  void comparableConfig_normalizesExtraHeaderKeys() {
    assertThat(
            AuthConfigValidator.comparableConfig(
                "BEARER", Map.of("token", "t", "extraHeader: X-Id", "v1")))
        .isEqualTo(
            AuthConfigValidator.comparableConfig(
                "BEARER", Map.of("token", "t", "extraHeader:X-Id", "v1")));
  }

  /**
   * 메인 인증 헤더 이름은 전송 규칙(ApiCallAuth.resolveWithAuthType)으로 고른다(WD-753 리뷰) — headerName 이 없고 key_name
   * 이 있으면 실제로 key_name 헤더로 나가므로, 그 이름과 같은 추가 헤더를 거절한다.
   */
  @Test
  void describeProblem_mainHeaderNameFollowsSendRule() {
    Map<String, String> config = new HashMap<>();
    config.put("placement", "header");
    config.put("key_name", "X-Legacy-Key");
    config.put("apiKey", "secretkey123");
    config.put("extraHeader:x-legacy-key", "abc-9999");

    assertThat(AuthConfigValidator.describeProblem("API_KEY", config))
        .isEqualTo("추가 헤더 x-legacy-key 은(는) 인증 헤더와 이름이 같습니다 — 인증 칸에 이미 있는 헤더입니다");
  }

  /** 저장된 연결 테스트·상태 점검도 원래 authConfig 의 공백만 다른 추가 헤더 키 겹침을 본다(WD-753 리뷰) — 푼 값에서는 이미 합쳐져 보이지 않는다. */
  @Test
  void describeSentValueProblem_rawConfigCollision() {
    Map<String, String> raw = new HashMap<>();
    raw.put("token", "tok12345");
    raw.put("extraHeader: X-A", "first-value-1");
    raw.put("extraHeader:X-A", "second-value-2");
    ApiCallAuth.ResolvedAuth auth = ApiCallAuth.resolveWithAuthType("BEARER", raw);

    assertThat(AuthConfigValidator.describeSentValueProblem(auth, null)).isNull();
    assertThat(AuthConfigValidator.describeSentValueProblem(auth, raw))
        .isEqualTo("추가 헤더 이름이 겹칩니다: X-A");
  }
}
