package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternUtilsTest {

  @Test
  void escape_noSpecialChars_returnsUnchanged() {
    assertThat(LikePatternUtils.escape("hello")).isEqualTo("hello");
  }

  @Test
  void escape_percentSign_isEscaped() {
    assertThat(LikePatternUtils.escape("100%")).isEqualTo("100\\%");
  }

  @Test
  void escape_underscore_isEscaped() {
    assertThat(LikePatternUtils.escape("user_name")).isEqualTo("user\\_name");
  }

  @Test
  void escape_backslash_isEscaped() {
    assertThat(LikePatternUtils.escape("path\\to")).isEqualTo("path\\\\to");
  }

  @Test
  void escape_allSpecialChars_areEscaped() {
    assertThat(LikePatternUtils.escape("%_\\")).isEqualTo("\\%\\_\\\\");
  }

  @Test
  void escape_null_returnsNull() {
    assertThat(LikePatternUtils.escape(null)).isNull();
  }

  @Test
  void escape_empty_returnsEmpty() {
    assertThat(LikePatternUtils.escape("")).isEmpty();
  }

  @Test
  void containsPattern_wrapsWithPercent() {
    assertThat(LikePatternUtils.containsPattern("test")).isEqualTo("%test%");
  }

  @Test
  void containsPattern_escapesBeforeWrapping() {
    assertThat(LikePatternUtils.containsPattern("100%_done")).isEqualTo("%100\\%\\_done%");
  }
}
