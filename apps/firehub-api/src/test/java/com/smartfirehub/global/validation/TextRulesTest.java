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
    assertThat(TextRules.strip("\u3000a\u3000")).isEqualTo("a");
    assertThat(TextRules.strip("\u00A0a\u202F")).isEqualTo("a");
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
        " \n\t",
        // WD-1126: 배정 안 된 14평면 무시 코드 포인트(U+E0000·E0005·E0FFF)만인 값, 이미 Mn 이라 거절되던 변이 선택자 보충(U+E0100)
        "\uDB40\uDC00",
        "\uDB43\uDFFF",
        "\uDB40\uDC05",
        "\uDB40\uDD00"
      })
  void hasText_rejectsInvisibleOnly(String value) {
    assertThat(Pattern.matches(TextRules.HAS_TEXT, value)).isFalse();
  }

  /** WD-1105 반-목표 — 한글 자모·이모지(변이 선택자 포함)·결합 문자가 붙은 글자·숨은 문자가 섞인 정상 값은 통과한다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\u314E",
        "\u2764\uFE0F",
        "e\u0301",
        "a\u3164",
        "차트\u200B",
        "매출",
        "a",
        "\uD83D\uDE00",
        // WD-1126: 잉글랜드 깃발 태그 시퀀스(🏴 이 글자), 글자 + U+E0000
        "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
        "a\uDB40\uDC00",
        // WD-1126: 범위 위 끝 고정 — 블록 밖 U+E1000 은 거절 대상이 아니다(범위가 넓어지면 잡힌다)
        "\uDB44\uDC00"
      })
  void hasText_acceptsVisible(String value) {
    assertThat(Pattern.matches(TextRules.HAS_TEXT, value)).isTrue();
  }

  /** WD-1105 — 선택 필드용 HAS_TEXT_OR_EMPTY 는 빈 문자열만 통과시키고 보이는 글자 없는 값은 거절한다. */
  @Test
  void hasTextOrEmpty_emptyOkInvisibleRejected() {
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "")).isTrue();
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "\u3164")).isFalse();
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "\u2800")).isFalse();
    // WD-1126: 14평면 무시 코드 포인트만인 선택 필드 값도 거절
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "\uDB40\uDC00")).isFalse();
    assertThat(Pattern.matches(TextRules.HAS_TEXT_OR_EMPTY, "폴더")).isTrue();
  }

  // WD-1103: 코드에서 쓰는 판정이 HAS_TEXT 정규식과 같은지 — 갈라지면 저장소·서비스가 서로 다르게 판단한다
  @Test
  void hasText는_정규식과_같은_판정이다() {
    assertThat(TextRules.hasText("쿼리")).isTrue();
    assertThat(TextRules.hasText("a b")).isTrue();
    assertThat(TextRules.hasText(null)).isFalse();
    assertThat(TextRules.hasText("")).isFalse();
    assertThat(TextRules.hasText(" ")).isFalse();
    assertThat(TextRules.hasText("\u00A0")).isFalse();          // NBSP
    assertThat(TextRules.hasText("\u3000")).isFalse();          // 전각 공백 U+3000
    assertThat(TextRules.hasText("\u200B")).isFalse();          // 폭 없는 문자 U+200B
    assertThat(TextRules.hasText("\u200B \uFEFF")).isFalse();  // 공백·Cf 혼합
    assertThat(TextRules.hasText("a\u200Bb")).isTrue();         // 글자 사이의 Cf 는 글자가 남는다
  }

  /**
   * EDGE_SPACE_CHARS 는 Java (?U)\\s 와 같은 집합이고, 대응 문자열은 같은 길이의 ASCII 공백이다(WD-1129). 길이가 다르면 PG
   * TRANSLATE 가 남는 문자를 지워 버린다.
   */
  @Test
  void edgeSpaceChars_matchesUnicodeWhiteSpace() {
    for (int c = 0; c <= 0xFFFF; c++) {
      String ch = String.valueOf((char) c);
      assertThat(TextRules.EDGE_SPACE_CHARS.indexOf(c) >= 0)
          .as("U+%04X", c)
          .isEqualTo(Pattern.matches("(?U)\\s", ch));
    }
    assertThat(TextRules.EDGE_SPACE_AS_ASCII).hasSameSizeAs(TextRules.EDGE_SPACE_CHARS);
    assertThat(TextRules.EDGE_SPACE_AS_ASCII.chars()).allMatch(c -> c == ' ');
  }

  /** spacesToAscii 는 안팎의 모든 유니코드 공백을 U+0020 으로 바꾸고 null 은 null 이다(WD-1129). */
  @Test
  void spacesToAscii_mapsEveryUnicodeSpaceToAscii() {
    assertThat(TextRules.spacesToAscii("\ta\u00A0b\u3000c ")).isEqualTo(" a b c ");
    assertThat(TextRules.spacesToAscii("abc")).isEqualTo("abc");
    assertThat(TextRules.spacesToAscii(null)).isNull();
    for (int i = 0; i < TextRules.EDGE_SPACE_CHARS.length(); i++) {
      assertThat(TextRules.spacesToAscii(String.valueOf(TextRules.EDGE_SPACE_CHARS.charAt(i))))
          .isEqualTo(" ");
    }
  }

  /** textOrNull 은 보이는 글자가 없으면 null, 있으면 값 그대로다 — 저장소가 설명·폴더를 저장할 때 쓴다(WD-1161). */
  @Test
  void textOrNull_nullsValuesWithoutVisibleText() {
    assertThat(TextRules.textOrNull(null)).isNull();
    assertThat(TextRules.textOrNull("")).isNull();
    assertThat(TextRules.textOrNull(" \u00A0\u3000")).isNull(); // 공백·NBSP·전각 공백
    assertThat(TextRules.textOrNull("\u200B\u3164")).isNull(); // Cf·한글 채움 문자
    assertThat(TextRules.textOrNull(" 설명 ")).isEqualTo(" 설명 "); // 글자가 있으면 다듬지 않고 그대로
    assertThat(TextRules.textOrNull("a\u200B")).isEqualTo("a\u200B");
  }

  /** WD-1125 — NO_NUL 은 NUL 이 끝·단독·가운데 어디에 있든 거절하고, 탭·CRLF·한글·빈 문자열은 통과시킨다. */
  @Test
  void noNul_rejectsNulAnywhere_allowsOthers() {
    Pattern p = Pattern.compile(TextRules.NO_NUL);
    for (String bad : new String[] {"a\u0000", "\u0000", "a\u0000b"}) {
      assertThat(p.matcher(bad).matches()).as("거절: %s", bad.replace("\u0000", "<NUL>")).isFalse();
    }
    for (String ok : new String[] {"", "select\t1\r\nfrom t", "한글 이름"}) {
      assertThat(p.matcher(ok).matches()).as("통과: %s", ok).isTrue();
    }
  }
}
