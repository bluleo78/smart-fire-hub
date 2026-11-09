package com.smartfirehub.dataimport.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ParseOptions 구분자 검증 단위 테스트(WD-602).
 *
 * <p>opencsv 는 구분자가 기본 따옴표(") 또는 기본 이스케이프(\)와 같거나 NUL 이면 UnsupportedOperationException 을 던져 500 이
 * 났다. 줄바꿈(\n, \r)은 행 경계와 겹쳐 파싱이 깨진다. 이런 첫 글자는 옵션을 만들 때 400 한국어로 거부한다.
 */
class ParseOptionsTest {

  private static final String EXPECTED_MESSAGE = "구분자는 따옴표(\")·역슬래시(\\)·줄바꿈이 아닌 한 글자여야 합니다.";

  /** opencsv 가 거부하거나 행 경계와 겹치는 첫 글자는 IllegalArgumentException(→400) 이어야 한다. */
  @ParameterizedTest
  @ValueSource(strings = {"\"", "\\", "\n", "\r", "\u0000", "\"x", "\\;"})
  void invalidDelimiter_throwsKoreanIllegalArgument(String delimiter) {
    assertThatThrownBy(() -> new ParseOptions(delimiter, "AUTO", true, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(EXPECTED_MESSAGE);
  }

  /** 흔한 구분자(쉼표·세미콜론·탭·파이프)와 사용자 지정 한 글자는 그대로 받아들인다. */
  @ParameterizedTest
  @ValueSource(strings = {",", ";", "\t", "|", "#"})
  void validDelimiter_isKept(String delimiter) {
    assertThat(new ParseOptions(delimiter, "AUTO", true, 0).delimiter()).isEqualTo(delimiter);
  }

  /** 빈 값·null 은 기존대로 쉼표 기본값으로 바뀐다(검증 대상 아님). */
  @Test
  void emptyOrNullDelimiter_defaultsToComma() {
    assertThat(new ParseOptions("", "AUTO", true, 0).delimiter()).isEqualTo(",");
    assertThat(new ParseOptions(null, "AUTO", true, 0).delimiter()).isEqualTo(",");
  }
}
