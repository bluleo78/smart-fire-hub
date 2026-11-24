package com.smartfirehub.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
