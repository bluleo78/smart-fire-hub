package com.smartfirehub.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** WD-1085 — TextRules.strip 은 앞뒤 유니코드 공백만 걷고 안쪽 공백은 둔다. */
class TextRulesTest {

  /** ASCII 공백·탭·개행은 앞뒤에서만 걷힌다. */
  @Test
  void strip_removesEdgeAsciiWhitespace() {
    assertThat(TextRules.strip("  a b  ")).isEqualTo("a b");
    assertThat(TextRules.strip("\t\n a")).isEqualTo("a");
  }

  /** 전각 공백(U+3000)·NBSP 계열(U+00A0·U+202F)도 걷힌다 — String.strip() 은 NBSP 를 못 걷는다. */
  @Test
  void strip_removesFullwidthAndNbsp() {
    assertThat(TextRules.strip("　a　")).isEqualTo("a");
    assertThat(TextRules.strip(" a ")).isEqualTo("a");
  }

  /** null 은 null(수정의 「변경 없음」), 공백 없는 값은 그대로. */
  @Test
  void strip_nullAndPlain() {
    assertThat(TextRules.strip(null)).isNull();
    assertThat(TextRules.strip("a")).isEqualTo("a");
  }

  /**
   * WD-1105 — 보이는 글자가 하나도 없는 값(채움 문자·결합 문자·제어 문자·점자 빈칸·공백 혼합)은 HAS_TEXT 가 거절한다. 화면에는 빈 이름으로 보이는데
   * 저장되던 틈을 막기 위함이다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\u3164",
        "\u115F",
        "\u1160",
        "\uFFA0",
        "\u2800",
        "\u0301",
        "\uFE0F",
        "\u20DD",
        "\u0000",
        "\u0001",
        "\u3164\u00A0\u200B",
        "\u2800\u0301",
        "\u2065",
        "\uFFF0",
        "\uD800",
        " \n\t"
      })
  void hasText_rejectsInvisibleOnly(String value) {
    assertThat(Pattern.matches(TextRules.HAS_TEXT, value)).isFalse();
  }

  /** WD-1105 반-목표 — 한글 자모·이모지(변이 선택자 포함)·결합 문자가 붙은 글자·숨은 문자가 섞인 정상 값은 통과한다. */
  @ParameterizedTest
  @ValueSource(strings = {"\u314E", "\u2764\uFE0F", "e\u0301", "a\u3164", "차트\u200B", "매출", "a", "\uD83D\uDE00"})
  void hasText_acceptsVisible(String value) {
    assertThat(Pattern.matches(TextRules.HAS_TEXT, value)).isTrue();
  }

  /** WD-1105 — 선택 필드용 HAS_TEXT_OR_EMPTY 는 빈 문자열만 통과시키고 보이는 글자 없는 값은 거절한다. */
  @Test
  void hasTextOrEmpty_emptyOkInvisibleRejected() {
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "")).isTrue();
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "\u3164")).isFalse();
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "\u2800")).isFalse();
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "폴더")).isTrue();
  }
}
