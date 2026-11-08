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
}
