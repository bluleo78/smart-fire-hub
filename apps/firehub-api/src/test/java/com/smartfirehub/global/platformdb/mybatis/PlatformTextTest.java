package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PlatformText(문장에 넘기기 전 문자열 정리, WD-710 C6) 단위 테스트. */
class PlatformTextTest {

  @Test
  @DisplayName("searchPattern — null·빈·공백만은 null, 그 밖에는 %·_·\\ 를 이스케이프한 부분 일치 패턴")
  void searchPattern() {
    assertThat(PlatformText.searchPattern(null)).isNull();
    assertThat(PlatformText.searchPattern("")).isNull();
    assertThat(PlatformText.searchPattern("  ")).isNull();
    assertThat(PlatformText.searchPattern("매출")).isEqualTo("%매출%");
    assertThat(PlatformText.searchPattern("50%_a")).isEqualTo("%50\\%\\_a%");
  }

  @Test
  @DisplayName("blankToNull — null·빈·공백만은 null, 그 밖에는 값 그대로(앞뒤 공백도 자르지 않는다)")
  void blankToNull() {
    assertThat(PlatformText.blankToNull(null)).isNull();
    assertThat(PlatformText.blankToNull("")).isNull();
    assertThat(PlatformText.blankToNull(" \t")).isNull();
    assertThat(PlatformText.blankToNull(" a ")).isEqualTo(" a ");
  }
}
