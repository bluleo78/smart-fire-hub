package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

/**
 * 통제 불가 텍스트 열(오류 메시지·사유 등)의 「한도를 넘는 값도 저장되고 앞부분으로 남는다」 계약 시험 도우미(WD-728).
 *
 * <p>왜: Tibero 기준선은 PG {@code text} 를 {@code VARCHAR2(65532)} 바이트 열로 옮겨, 한도를 넘는 값은 INSERT/UPDATE 가
 * 실패하고 트랜잭션이 롤백된다. MyBatis 구현은 {@link TiberoTextLimits} 로 잘라 저장을 성공시키고, jOOQ(PG) 구현은 원문 전체를 저장한다.
 * 그래서 공용 단언은 「원문의 접두어」이고, 잘린 경우(Tibero 는 원문이 한도를 넘으므로 늘 잘린다)에만 바이트 한도를 본다.
 */
public final class LongTextAssertions {

  private LongTextAssertions() {}

  /**
   * 정확히 한도+1 바이트(65533 바이트)인 한글 값 — 3바이트 한글 21844자(65532 바이트)에 ASCII 한 글자를 붙였다. 일반 문자열이며 JSON 이
   * 아니다.
   */
  public static String overVarchar2Limit() {
    return "가".repeat(TiberoTextLimits.VARCHAR2_TEXT_MAX_BYTES / 3) + "a";
  }

  /**
   * 한글을 섞은 약 70KB SQL 본문(UTF-8 70000 바이트 안팎, 65532 바이트 한도 초과) — 잘리면 안 되는 SQL 본문 열(WD-740, Tibero
   * CLOB)의 왕복 시험용이다. {@code tail} 이 끝에 붙어 검색 시험이 한도 너머를 찾게 한다. 일반 문자열이며 JSON 이 아니다.
   */
  public static String koreanSql70Kb(String tail) {
    String sql = "SELECT '한글 값' AS v FROM t WHERE c = 'x' -- " + "가나다 ".repeat(7000) + tail;
    assertThat(sql.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(TiberoTextLimits.VARCHAR2_TEXT_MAX_BYTES);
    return sql;
  }

  /**
   * 저장된 값이 원문의 (충분히 긴) 접두어이고, 원문보다 짧다면 UTF-8 로 {@code VARCHAR2(65532)} 바이트 안이며 깨진 글자(U+FFFD)가 없는지
   * 단언한다.
   */
  public static void assertStoredPrefix(String stored, String original) {
    assertThat(stored).isNotNull();
    assertThat(original).startsWith(stored);
    assertThat(stored.length()).isGreaterThanOrEqualTo(TiberoTextLimits.MAX_ERROR_CHARS);
    assertThat(stored).doesNotContain("�");
    if (stored.length() < original.length()) {
      assertThat(stored.getBytes(StandardCharsets.UTF_8).length)
          .isLessThanOrEqualTo(TiberoTextLimits.VARCHAR2_TEXT_MAX_BYTES);
    }
  }
}
