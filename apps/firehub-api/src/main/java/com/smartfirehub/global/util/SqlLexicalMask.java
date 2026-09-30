package com.smartfirehub.global.util;

/**
 * PostgreSQL 어휘(주석·리터럴)를 인지하는 SQL 스캐너(순수 함수). 사용자 SQL 을 괄호로 감싸거나 뒤에 무언가를
 * 붙이기 전에 "진짜 코드"와 "주석·리터럴 속 글자"를 구분하는 데 쓴다.
 *
 * <p><b>왜 필요한가(#746).</b> 파이프라인 SQL 스텝은 저장 시점 검증(SqlValidator)이 후행 세미콜론과 끝
 * 주석을 허용하므로, 러너가 사용자 SELECT 를 {@code (...)} 로 감쌀 때(컬럼 probe·MERGE 서브쿼리) 끝의
 * 세미콜론을 지워야 한다. 예전 헬퍼는 작은따옴표와 {@code --} 만 추적해서 {@code ; /* note *}{@code /},
 * {@code $$--y;$$ ...;}, {@code $$it's$$ ...;} 에서 세미콜론을 못 지워 실행이 구문 오류로 실패했다.
 *
 * <p><b>Python 짝과 같은 결과를 낸다.</b> executor 의 {@code query_executor._mask_sql}/{@code _normalize_sql}
 * (#745)과 같은 토큰 규칙이다. 두 구현은 공용 픽스처 {@code src/test/resources/fixtures/sql-lexical-vectors.json}
 * 으로 같은 값을 검증한다 — 규칙을 바꿀 때는 두 언어를 함께 고치고 픽스처를 갱신할 것.
 *
 * <p>인지하는 어휘:
 *
 * <ul>
 *   <li>{@code --} 줄 주석 — {@code \n} 또는 {@code \r} 에서 끝난다(PostgreSQL scan.l 의 non_newline).
 *   <li>{@code /* ... *}{@code /} 블록 주석 — PostgreSQL 처럼 중첩을 센다.
 *   <li>{@code '...'}({@code ''} 이스케이프), {@code E'...'}(백슬래시 이스케이프 추가), {@code "..."}
 *       식별자({@code ""} 이스케이프).
 *   <li>{@code $tag$ ... $tag$} 달러 인용(태그는 비었거나 글자·밑줄로 시작).
 *   <li>식별자는 한 덩어리로 건너뛴다 — {@code a$$b} 의 {@code $} 는 식별자 글자라 달러 인용이 아니고,
 *       {@code E'} 는 식별자가 정확히 {@code E} 일 때만 이스케이프 문자열이다.
 * </ul>
 *
 * <p>닫히지 않은 리터럴·주석은 끝까지 그 상태로 본다(DB 가 어차피 구문 오류로 거부한다). 공백은
 * PostgreSQL 스캐너의 공백 집합({@code " \t\n\r\f\u000B"})만 쓴다 — {@link String#strip()}·{@link
 * Character#isWhitespace} 는 Python {@code str.strip()} 과 집합이 달라 두 언어 결과가 갈린다.
 *
 * <p>다음 소비처가 재사용할 수 있게 {@link #mask} 를 공개한다(예: 애드혹 경로의 주석 제거 #747, 최상위
 * LIMIT 판정 #749/#750) — 마스크 위에서 위치를 찾고 원문을 그 위치로 자르면 된다.
 */
public final class SqlLexicalMask {

  /** PostgreSQL 스캐너가 공백으로 보는 문자(scan.l 의 space). */
  private static final String PG_WHITESPACE = " \t\n\r\f\u000B";

  private SqlLexicalMask() {}

  /**
   * 주석을 공백으로, 리터럴 내용을 {@code x} 로 가린 <b>같은 길이</b> 문자열을 만든다.
   *
   * <p>문자열·따옴표 식별자는 따옴표를 남기고 내용만, 달러 인용은 태그까지 통째로 가린다. 줄 주석 끝의
   * 개행은 남긴다. 인덱스가 원문과 1:1 이므로 마스크에서 찾은 위치로 원문을 자를 수 있다.
   */
  public static String mask(String sql) {
    char[] out = sql.toCharArray();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
      if (c == '-' && next == '-') {
        // 줄 주석: 개행(\n 또는 \r) 직전까지 가린다(개행 자체는 남긴다).
        int j = i;
        while (j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
          j++;
        }
        fill(out, i, j, ' ');
        i = j;
      } else if (c == '/' && next == '*') {
        // 블록 주석: PostgreSQL 은 중첩을 허용하므로 깊이를 센다.
        int depth = 1;
        int j = i + 2;
        while (j < n && depth > 0) {
          if (sql.startsWith("/*", j)) {
            depth++;
            j += 2;
          } else if (sql.startsWith("*/", j)) {
            depth--;
            j += 2;
          } else {
            j++;
          }
        }
        fill(out, i, Math.min(j, n), ' ');
        i = j;
      } else if (c == '\'' || c == '"') {
        i = maskQuoted(sql, out, i, false);
      } else if (isIdentStart(c)) {
        // 식별자/키워드 한 덩어리. 정확히 E 이고 바로 ' 가 오면 백슬래시 이스케이프 문자열이다.
        int j = i + 1;
        while (j < n && isIdentCont(sql.charAt(j))) {
          j++;
        }
        if (j - i == 1 && (c == 'e' || c == 'E') && j < n && sql.charAt(j) == '\'') {
          i = maskQuoted(sql, out, j, true);
        } else {
          i = j;
        }
      } else if (c == '$') {
        // 식별자 밖의 $ 만 여기 온다. $1 같은 위치 파라미터는 태그 모양이 아니라 그냥 지나간다.
        int tagEnd = dollarTagEnd(sql, i);
        if (tagEnd < 0) {
          i++;
          continue;
        }
        String tag = sql.substring(i, tagEnd);
        int close = sql.indexOf(tag, tagEnd);
        int end = close < 0 ? n : close + tag.length();
        fill(out, i, end, 'x');
        i = end;
      } else {
        i++;
      }
    }
    return new String(out);
  }

  /**
   * 끝의 공백·주석·세미콜론을(여러 개여도) 걷어내고 앞 공백을 뗀 SQL 을 돌려준다. 주석만 있으면 빈 문자열.
   *
   * <p>사용자 SELECT 를 {@code (...)} 로 감싸거나 뒤에 절을 덧붙이기 전에 쓴다 — 세미콜론이 괄호 안에
   * 들어가거나 덧붙인 절이 끝 주석에 묻히는 것을 막는다. 끝 주석은 실행에 아무 의미가 없으므로 함께
   * 걷는다. 중간의 세미콜론(여러 문장)은 건드리지 않는다 — 다중 문장 거부는 저장 시점 검증의 몫이다.
   */
  public static String stripTrailingCommentsAndSemicolons(String sql) {
    String masked = mask(sql);
    int end = trailingTrimEnd(masked, masked.length());
    while (end > 0 && masked.charAt(end - 1) == ';') {
      end = trailingTrimEnd(masked, end - 1);
    }
    int start = 0;
    while (start < end && isPgWhitespace(sql.charAt(start))) {
      start++;
    }
    return sql.substring(start, end);
  }

  /** {@code s[0, end)} 끝의 PostgreSQL 공백을 뗀 뒤의 끝 위치. */
  private static int trailingTrimEnd(String s, int end) {
    while (end > 0 && isPgWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return end;
  }

  /** {@code sql[q]} 의 따옴표로 시작하는 리터럴 내용을 가리고 닫는 따옴표 다음 위치를 돌려준다. */
  private static int maskQuoted(String sql, char[] out, int q, boolean backslashEscapes) {
    int n = sql.length();
    char quote = sql.charAt(q);
    int j = q + 1;
    while (j < n) {
      char ch = sql.charAt(j);
      if (backslashEscapes && ch == '\\') {
        j += 2;
        continue;
      }
      if (ch == quote) {
        if (j + 1 < n && sql.charAt(j + 1) == quote) { // '' / "" 이스케이프
          j += 2;
          continue;
        }
        break;
      }
      j++;
    }
    fill(out, q + 1, Math.min(j, n), 'x');
    return j + 1;
  }

  /**
   * {@code sql[i]} 의 {@code $} 에서 시작하는 달러 인용 여는 태그({@code $$} 또는 {@code $tag$})의 끝 다음
   * 위치. 태그 모양이 아니면 -1. 태그 본문에는 {@code $} 가 올 수 없다(식별자와 다른 규칙).
   */
  private static int dollarTagEnd(String sql, int i) {
    int n = sql.length();
    int j = i + 1;
    if (j < n && isIdentStart(sql.charAt(j))) {
      j++;
      while (j < n && (isIdentStart(sql.charAt(j)) || isAsciiDigit(sql.charAt(j)))) {
        j++;
      }
    }
    return j < n && sql.charAt(j) == '$' ? j + 1 : -1;
  }

  /** PostgreSQL 식별자 첫 글자(ident_start): ASCII 글자·밑줄·0x80 이상 문자(서로게이트 포함). */
  private static boolean isIdentStart(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_' || ch >= 0x80;
  }

  /** PostgreSQL 식별자 이어지는 글자(ident_cont): ident_start + 숫자 + {@code $}. */
  private static boolean isIdentCont(char ch) {
    return isIdentStart(ch) || isAsciiDigit(ch) || ch == '$';
  }

  private static boolean isAsciiDigit(char ch) {
    return ch >= '0' && ch <= '9';
  }

  private static boolean isPgWhitespace(char ch) {
    return PG_WHITESPACE.indexOf(ch) >= 0;
  }

  private static void fill(char[] out, int from, int to, char ch) {
    for (int k = from; k < to; k++) {
      out[k] = ch;
    }
  }
}
