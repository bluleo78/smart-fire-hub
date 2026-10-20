package com.smartfirehub.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** InternalTokens 단위 테스트: 접두어·공백·일치 여부. */
class InternalTokensTest {

  @Test
  void matchesCorrectToken() {
    assertThat(InternalTokens.matches("Internal secret", "secret")).isTrue();
  }

  @Test
  void rejectsWrongToken() {
    assertThat(InternalTokens.matches("Internal other", "secret")).isFalse();
  }

  @Test
  void rejectsMissingOrWrongPrefix() {
    assertThat(InternalTokens.matches(null, "secret")).isFalse();
    assertThat(InternalTokens.matches("Bearer secret", "secret")).isFalse();
    assertThat(InternalTokens.matches("secret", "secret")).isFalse();
  }

  @Test
  void rejectsBlankExpectedToken() {
    assertThat(InternalTokens.matches("Internal ", "")).isFalse();
    assertThat(InternalTokens.matches("Internal  ", " ")).isFalse();
    assertThat(InternalTokens.matches("Internal x", null)).isFalse();
  }

  @Test
  void handlesNonAsciiToken() {
    assertThat(InternalTokens.matches("Internal 토큰", "토큰")).isTrue();
  }
}
