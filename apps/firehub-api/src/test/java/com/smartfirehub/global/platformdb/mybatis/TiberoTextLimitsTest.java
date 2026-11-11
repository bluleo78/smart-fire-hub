package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TiberoTextLimits.truncateErrorMessage 를 DB 없이 고정한다(WD-707 리뷰에서 시작, WD-712 에서 공용 도우미로 옮김). Tibero
 * 오류 메시지 열은 VARCHAR2(65532) 바이트 한도라, 자르지 않으면 긴 메시지의 UPDATE 가 실패해 실패·백오프가 기록되지 않는다.
 */
class TiberoTextLimitsTest {

  private static final int LIMIT = TiberoTextLimits.MAX_ERROR_CHARS;

  @Test
  @DisplayName("한도 이하·null 메시지는 그대로(같은 인스턴스) 둔다")
  void shortOrNull_isUnchanged() {
    assertThat(TiberoTextLimits.truncateErrorMessage(null)).isNull();
    assertThat(TiberoTextLimits.truncateErrorMessage("boom")).isEqualTo("boom");
    String exact = "가".repeat(LIMIT);
    assertThat(TiberoTextLimits.truncateErrorMessage(exact)).isSameAs(exact);
  }

  @Test
  @DisplayName("긴 한글 메시지는 앞부분 16000 자로 잘리고 UTF-8 로 65532 바이트를 넘지 않는다")
  void longKorean_isTruncatedUnderByteLimit() {
    String huge = "가".repeat(25_000);
    String cut = TiberoTextLimits.truncateErrorMessage(huge);
    assertThat(cut).hasSize(LIMIT);
    assertThat(huge).startsWith(cut);
    assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_532);
  }

  @Test
  @DisplayName("자른 끝이 상위 서로게이트면 한 글자 덜 잘라 보충 문자 짝을 가르지 않는다")
  void surrogatePair_atBoundary_isNotSplit() {
    // 한도 바로 앞까지 채우고 그 자리에 이모지(char 두 개)를 걸쳐 둔다 — 한도 위치 char 가 상위 서로게이트다.
    String huge = "a".repeat(LIMIT - 1) + "😀" + "b".repeat(10);
    assertThat(Character.isHighSurrogate(huge.charAt(LIMIT - 1))).isTrue();

    String cut = TiberoTextLimits.truncateErrorMessage(huge);

    assertThat(cut).hasSize(LIMIT - 1);
    assertThat(cut).isEqualTo("a".repeat(LIMIT - 1));
    assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
  }

  @Test
  @DisplayName("보충 문자 짝이 한도 안에 온전히 들어가면 그대로 둔 채 한도까지 자른다")
  void surrogatePair_insideLimit_isKept() {
    // 이모지가 한도 위치 바로 앞에서 끝난다(하위 서로게이트가 마지막 char) — 덜 자를 이유가 없다.
    String huge = "a".repeat(LIMIT - 2) + "😀" + "b".repeat(10);

    String cut = TiberoTextLimits.truncateErrorMessage(huge);

    assertThat(cut).hasSize(LIMIT);
    assertThat(cut).endsWith("😀");
  }

  // ---- truncate(String, int) — 임의 한도 ----

  @Test
  @DisplayName("truncate: 임의 한도에서 null·한도 이하는 그대로, 넘으면 앞부분 한도 글자로 자른다")
  void truncate_customLimit() {
    assertThat(TiberoTextLimits.truncate(null, 4000)).isNull();
    String exact = "가".repeat(4000);
    assertThat(TiberoTextLimits.truncate(exact, 4000)).isSameAs(exact);
    assertThat(TiberoTextLimits.truncate("가".repeat(4001), 4000)).isEqualTo(exact);
  }

  @Test
  @DisplayName("truncate: 한도 위치에 상위 서로게이트가 걸리면 한 글자 덜 잘라 짝을 가르지 않는다")
  void truncate_surrogateAtBoundary_isNotSplit() {
    // 4000자 한도 — 3999 번째 char 까지 'a', 4000 번째 char 가 이모지의 상위 서로게이트다.
    String value = "a".repeat(3999) + "😀" + "tail";
    assertThat(Character.isHighSurrogate(value.charAt(3999))).isTrue();

    String cut = TiberoTextLimits.truncate(value, 4000);

    assertThat(cut).isEqualTo("a".repeat(3999));
  }

  @Test
  @DisplayName("truncate: 보충 문자 짝이 한도 안에서 끝나면 그대로 둔다")
  void truncate_surrogateInsideLimit_isKept() {
    String value = "a".repeat(3998) + "😀" + "tail";

    String cut = TiberoTextLimits.truncate(value, 4000);

    assertThat(cut).hasSize(4000).endsWith("😀");
  }

  @Test
  @DisplayName("truncate: 한도가 1 미만이면 IllegalArgumentException")
  void truncate_rejectsNonPositiveLimit() {
    assertThatThrownBy(() -> TiberoTextLimits.truncate("x", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
