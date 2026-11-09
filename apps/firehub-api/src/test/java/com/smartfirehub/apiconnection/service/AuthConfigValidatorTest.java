package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import java.util.HashMap;
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
                    "BASIC", Map.of("password", "pw"))))
        .isEqualTo("아이디를 입력하세요 (authConfig.username)");
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "BASIC", Map.of("username", "alice"))))
        .isEqualTo("비밀번호를 입력하세요 (authConfig.password)");
    assertThat(
            AuthConfigValidator.describeSentValueProblem(
                com.smartfirehub.pipeline.service.executor.ApiCallAuth.resolveWithAuthType(
                    "BASIC", Map.of("username", "alice", "password", "pw"))))
        .isNull();
  }
}
