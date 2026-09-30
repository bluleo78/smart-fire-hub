package com.smartfirehub.global.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    scan(sql, out);
    return new String(out);
  }

  /**
   * 주석·리터럴·따옴표 식별자·달러 인용 <b>밖</b>에 있는 첫 위치 파라미터({@code $1}, {@code $12} …)의 시작
   * 인덱스를 돌려준다. 없으면 -1(#753).
   *
   * <p><b>왜 필요한가.</b> 애드혹 SQL 은 바인드 값 없이 실행하므로 {@code $n} 은 채울 값이 없다. pgjdbc 는 확장
   * 프로토콜로 보내는데, 서버는 Parse 단계에서 {@code $1} 을 선언되지 않은 파라미터로 추론해 두고 Bind(값 0개)
   * 에서 {@code 08P01}(프로토콜 위반)로 거부한다. Hikari 는 SQLSTATE 08 계열을 "깨진 커넥션"으로 보고 폐기해
   * 진행 중이던 트랜잭션까지 잃는다 — 그래서 실행 전에 이 함수로 걸러 원인 메시지로 거부한다.
   *
   * <p>판정은 {@link #mask} 와 같은 스캔 상태로 한다(마스크 결과 문자열이 아니라). 식별자 속 {@code $}({@code a$1})
   * 는 식별자 글자라 파라미터가 아니고, 따옴표 식별자·달러 인용 바로 뒤의 {@code $1}({@code "x"$1},
   * {@code $$a$$$1}) 과 숫자 바로 뒤의 {@code $2}({@code 1$2}) 는 PostgreSQL 스캐너에서도 파라미터다.
   */
  public static int findPositionalParameter(String sql) {
    return scan(sql, sql.toCharArray());
  }

  /**
   * {@link #mask} 의 본체. {@code out}(원문 복사본)에 주석·리터럴을 가리고, 스캔 중 만난 첫 위치 파라미터의
   * 인덱스(없으면 -1)를 돌려준다. 두 공개 함수가 같은 토큰 규칙을 쓰도록 한 곳에 둔다.
   */
  private static int scan(String sql, char[] out) {
    int firstParam = -1;
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
          // 태그 모양이 아닌 $ 뒤에 숫자가 오면 위치 파라미터다(PostgreSQL scan.l 의 param: \${decinteger}).
          if (firstParam < 0 && i + 1 < n && isAsciiDigit(sql.charAt(i + 1))) {
            firstParam = i;
          }
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
    return firstParam;
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

  /**
   * 최상위(괄호 깊이 0)에 사용자 행 제한({@code LIMIT n} 또는 {@code FETCH {FIRST|NEXT} … ROW(S) {ONLY|WITH
   * TIES}})이 있으면 true(#749).
   *
   * <p>주석·리터럴 속, 서브쿼리·CTE 속 절은 결과 행 수를 제한하지 않으므로 보지 않는다(#750 — 예전 정규식
   * {@code (?s).*\\bLIMIT\\s+\\d+.*} 은 문자열 {@code 'limit 5'}·CTE 속 LIMIT 에 속아 maxRows 를 빼먹었다). 사용자가
   * 최상위에 직접 쓴 제한은 존중한다. {@code LIMIT ALL}/{@code LIMIT NULL} 은 "제한 없음"이라 false 다.
   *
   * <p><b>Python {@code query_executor._has_limit} 와 짝이다</b> — 공용 픽스처 {@code rowLimit} 으로 같은
   * 판정을 검증한다.
   */
  public static boolean hasTopLevelRowLimit(String sql) {
    return topLevelRowLimit(sql).kind == RowLimitKind.LIMITED;
  }

  /**
   * 끝 주석·세미콜론이 걷힌 SELECT 에 {@code maxRows} 행 제한을 적용한 SQL 을 돌려준다(#749).
   *
   * <ul>
   *   <li>최상위 사용자 제한({@code LIMIT n}·{@code FETCH FIRST n})이 있으면 그대로 둔다(존중 규칙).
   *   <li>최상위 {@code LIMIT ALL}/{@code LIMIT NULL} 이면 그 값 토큰만 {@code maxRows} 로 바꾼다 — 뒤에 LIMIT 을
   *       또 붙이면 구문 오류이고, "제한 없음"은 LIMIT 을 안 쓴 것과 같으므로 maxRows 를 적용한다.
   *   <li>없으면 {@code \nLIMIT maxRows} 를 덧붙인다(개행: 혹시 끝에 줄 주석이 남아도 묻히지 않게).
   * </ul>
   *
   * <p><b>Python {@code query_executor._apply_row_limit} 와 짝이다.</b>
   */
  public static String applyRowLimit(String sql, int maxRows) {
    RowLimit state = topLevelRowLimit(sql);
    return switch (state.kind) {
      case LIMITED -> sql;
      case UNLIMITED -> sql.substring(0, state.start) + maxRows + sql.substring(state.end);
      case NONE -> sql + "\nLIMIT " + maxRows;
    };
  }

  /** 최상위 행 제한 종류. */
  private enum RowLimitKind {
    /** 사용자가 LIMIT n / FETCH FIRST 로 직접 제한. */
    LIMITED,
    /** LIMIT ALL / LIMIT NULL — start/end 는 ALL/NULL 토큰 위치. */
    UNLIMITED,
    /** 최상위 행 제한 없음. */
    NONE
  }

  private record RowLimit(RowLimitKind kind, int start, int end) {}

  /** 마스크 위 토큰: 대문자 텍스트, 원문 기준 [start, end), 괄호 깊이. */
  private record Token(String text, int start, int end, int depth) {}

  /**
   * 최상위 행 제한 상태. LIMIT 은 PostgreSQL 예약어라 최상위에 나오면 항상 LIMIT 절이다. FETCH 는 비예약어라
   * 절 모양 전체({@link #isFetchClause})를 확인한다.
   */
  private static RowLimit topLevelRowLimit(String sql) {
    List<Token> tokens = tokenize(mask(sql));
    for (int k = 0; k < tokens.size(); k++) {
      Token t = tokens.get(k);
      if (t.depth != 0) {
        continue;
      }
      if (t.text.equals("LIMIT")) {
        if (k + 1 < tokens.size()
            && (tokens.get(k + 1).text.equals("ALL") || tokens.get(k + 1).text.equals("NULL"))) {
          return new RowLimit(RowLimitKind.UNLIMITED, tokens.get(k + 1).start, tokens.get(k + 1).end);
        }
        return new RowLimit(RowLimitKind.LIMITED, t.start, t.end);
      }
      if (t.text.equals("FETCH") && isFetchClause(tokens, k)) {
        return new RowLimit(RowLimitKind.LIMITED, t.start, t.end);
      }
    }
    return new RowLimit(RowLimitKind.NONE, -1, -1);
  }

  /**
   * 마스크를 토큰으로 나눈다. 식별자/키워드는 PostgreSQL 규칙(ident_start + ident_cont*, {@code $} 포함)으로 한
   * 덩어리로 자른다 — 정규식 {@code \\b} 는 {@code a$limit}·{@code 한limit} 을 쪼개 가짜 LIMIT 을 만들고 Java 와
   * Python 의 {@code \\b} 정의도 다르다. {@code (} 는 여는 쪽 깊이, {@code )} 는 닫힌 뒤 깊이로 기록한다.
   * <b>Python {@code _tokenize_masked} 와 짝이다.</b>
   */
  private static List<Token> tokenize(String masked) {
    List<Token> tokens = new ArrayList<>();
    int n = masked.length();
    int depth = 0;
    int i = 0;
    while (i < n) {
      char c = masked.charAt(i);
      if (isPgWhitespace(c)) {
        i++;
      } else if (isIdentStart(c)
          || isAsciiDigit(c)
          || (c == '$' && i + 1 < n && isAsciiDigit(masked.charAt(i + 1)))) {
        // 식별자·키워드·숫자(1.5e3 포함)·위치 파라미터($1)를 한 토큰으로.
        int j = i + 1;
        while (j < n && (isIdentCont(masked.charAt(j)) || masked.charAt(j) == '.')) {
          j++;
        }
        // 대문자화는 ASCII 토큰만 — PostgreSQL 은 키워드 판정 때 ASCII 만 접는다. 유니코드 대문자화는
        // lımıt(점 없는 ı) 을 LIMIT 으로 만들어 가짜 제한에 속는다.
        String word = masked.substring(i, j);
        tokens.add(new Token(isAscii(word) ? word.toUpperCase(Locale.ROOT) : word, i, j, depth));
        i = j;
      } else if (c == '(') {
        tokens.add(new Token("(", i, i + 1, depth));
        depth++;
        i++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
        tokens.add(new Token(")", i, i + 1, depth));
        i++;
      } else {
        tokens.add(new Token(String.valueOf(c), i, i + 1, depth));
        i++;
      }
    }
    return tokens;
  }

  private static boolean isAscii(String s) {
    for (int k = 0; k < s.length(); k++) {
      if (s.charAt(k) >= 0x80) {
        return false;
      }
    }
    return true;
  }

  /** 식별자·키워드·숫자·{@code $n} 토큰인지(구두점 토큰이 아닌지). */
  private static boolean isWordToken(String text) {
    char c = text.charAt(0);
    return isIdentStart(c) || isAsciiDigit(c) || c == '$';
  }

  /**
   * {@code tokens[k]} 의 FETCH 가 {@code FETCH {FIRST|NEXT} [count] {ROW|ROWS} {ONLY|WITH TIES}} 절인지.
   * FETCH/FIRST/NEXT/ROWS 는 비예약어라 컬럼명·별칭이 될 수 있다({@code SELECT fetch first FROM t}) — 절 모양
   * 전체를 보지 않으면 가짜 FETCH 에 속아 maxRows 가 빠진다. count 는 단일 토큰(앞 부호 허용) 또는 괄호 묶음만
   * 인정한다(PostgreSQL select_fetch_first_value 문법). <b>Python {@code _is_fetch_clause} 와 짝이다.</b>
   */
  private static boolean isFetchClause(List<Token> tokens, int k) {
    int n = tokens.size();
    int j = k + 1;
    if (j >= n || !(tokens.get(j).text.equals("FIRST") || tokens.get(j).text.equals("NEXT"))) {
      return false;
    }
    j++;
    if (j < n && !isRowKeyword(tokens.get(j).text)) {
      if (tokens.get(j).text.equals("+") || tokens.get(j).text.equals("-")) {
        j++;
      }
      if (j < n && tokens.get(j).text.equals("(")) {
        int openDepth = tokens.get(j).depth;
        j++;
        while (j < n && !(tokens.get(j).text.equals(")") && tokens.get(j).depth == openDepth)) {
          j++;
        }
        j++;
      } else if (j < n && isWordToken(tokens.get(j).text)) {
        j++;
      } else {
        return false;
      }
    }
    if (j >= n || !isRowKeyword(tokens.get(j).text)) {
      return false;
    }
    j++;
    if (j < n && tokens.get(j).text.equals("ONLY")) {
      return true;
    }
    return j + 1 < n && tokens.get(j).text.equals("WITH") && tokens.get(j + 1).text.equals("TIES");
  }

  private static boolean isRowKeyword(String text) {
    return text.equals("ROW") || text.equals("ROWS");
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
