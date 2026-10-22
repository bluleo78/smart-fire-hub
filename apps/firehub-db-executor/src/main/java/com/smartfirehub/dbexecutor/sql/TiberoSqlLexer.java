package com.smartfirehub.dbexecutor.sql;

/**
 * Tibero(Oracle) SQL 어휘 분석기 — 따옴표·주석을 인식해 "코드 영역"만 골라 보는 순수 정적 도구.
 *
 * <p>PG 규칙({@code SqlLexicalMask})과 다르다: 주석은 중첩되지 않고, {@code $$} 와 {@code E'..'} 는 특별하지 않으며, 대신
 * {@code q'[..]'} 대체 따옴표가 있다. 정규식 대신 문자 단위 상태 기계 한 번 훑기로 처리한다(따옴표 안의 {@code ;} 오판 방지).
 *
 * <p>firehub-db-executor 가 이 클래스를 패키지명만 바꿔 복사해 같은 JSON 고정값으로 검증한다. 그래서 JDK 외 의존성을 두지 않는다.
 */
public final class TiberoSqlLexer {

  /** 문자 분류: 코드. */
  private static final byte CODE = 0;

  /** 문자 분류: 주석(구분자 포함). */
  private static final byte COMMENT = 1;

  /** 문자 분류: 문자열 리터럴(따옴표·q-구분자 포함). */
  private static final byte STRING = 2;

  /** 문자 분류: 큰따옴표 식별자(따옴표 포함). */
  private static final byte QIDENT = 3;

  private TiberoSqlLexer() {}

  /**
   * 식별자를 이루는 문자인지 — 가드·검증기({@code SqlValidator})의 단어 경계 판정과 공유한다(규칙을 한 곳에 둔다).
   *
   * <p>Tibero 는 따옴표 없는 비ASCII 식별자({@code 한글컬럼})를 허용하므로(실서버 확인: db-executor {@code
   * TiberoLiveSqlTest.live_lexicalFacts}) 비ASCII 글자·숫자도 식별자 문자다. ASCII 만 보면 {@code 스키마.FN(1)} 같은
   * 비ASCII 스키마 한정 호출이 다른 스키마 함수 검사를 빠져나간다. 보충 평면 글자(UTF-16 대리 쌍)는 {@code char} 하나로 글자 여부를 알 수 없으므로
   * 대리 문자도 식별자 문자로 본다 — 다른 스키마 검사에서는 더 많이 거부하는 쪽이다.
   */
  public static boolean isIdentChar(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == '$'
        || c == '#'
        || (c >= 128 && (Character.isLetterOrDigit(c) || Character.isSurrogate(c)));
  }

  /** 따옴표·주석 밖 끝의 {@code ;} 하나와 그 앞뒤 공백을 제거한다. 끝이 코드 {@code ;} 가 아니면 원문 그대로. */
  public static String stripTrailingSemicolon(String sql) {
    byte[] kinds = classify(sql);
    int end = sql.length();
    while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
      end--;
    }
    if (end == 0 || sql.charAt(end - 1) != ';' || kinds[end - 1] != CODE) {
      return sql;
    }
    end--;
    while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
      end--;
    }
    return sql.substring(0, end);
  }

  /** 따옴표·주석 밖의 {@code ;} 개수. */
  public static int countTopLevelSemicolons(String sql) {
    byte[] kinds = classify(sql);
    int count = 0;
    for (int i = 0; i < sql.length(); i++) {
      if (kinds[i] == CODE && sql.charAt(i) == ';') {
        count++;
      }
    }
    return count;
  }

  /** 주석·공백·여는 괄호를 건너뛴 첫 단어(대문자). 단어가 없거나 단어가 아닌 문자가 오면 빈 문자열. */
  public static String firstKeyword(String sql) {
    byte[] kinds = classify(sql);
    int i = 0;
    while (i < sql.length()
        && (kinds[i] == COMMENT
            || Character.isWhitespace(sql.charAt(i))
            || (kinds[i] == CODE && sql.charAt(i) == '('))) {
      i++;
    }
    int start = i;
    while (i < sql.length() && kinds[i] == CODE && isIdentChar(sql.charAt(i))) {
      i++;
    }
    return sql.substring(start, i).toUpperCase(java.util.Locale.ROOT);
  }

  /** 문자열 리터럴·주석을 같은 길이의 공백으로 바꾼다. 큰따옴표 식별자는 유지한다(위험 이름이 {@code "DBMS_X"} 로 숨는 것을 잡기 위해). */
  public static String maskLiteralsAndComments(String sql) {
    byte[] kinds = classify(sql);
    StringBuilder out = new StringBuilder(sql.length());
    for (int i = 0; i < sql.length(); i++) {
      out.append(kinds[i] == COMMENT || kinds[i] == STRING ? ' ' : sql.charAt(i));
    }
    return out.toString();
  }

  /** {@code q'..'}·{@code nq'..'} 대체 따옴표 리터럴이 코드 영역에 있는지. */
  public static boolean hasAlternativeQuoting(String sql) {
    return scan(sql, null);
  }

  /** 각 문자의 분류 배열을 만든다. 닫히지 않은 리터럴·주석이면 IllegalArgumentException. */
  private static byte[] classify(String sql) {
    byte[] kinds = new byte[sql.length()];
    scan(sql, kinds);
    return kinds;
  }

  /**
   * 한 번 훑는 상태 기계. {@code kinds} 가 null 이 아니면 문자별 분류를 채우고, 대체 따옴표를 만났는지를 돌려준다.
   *
   * @throws IllegalArgumentException 닫히지 않은 리터럴·식별자·주석
   */
  private static boolean scan(String sql, byte[] kinds) {
    int n = sql.length();
    boolean alt = false;
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        // 줄 주석: 줄바꿈 직전까지(줄바꿈 자체는 코드로 둔다)
        int j = i;
        while (j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
          j++;
        }
        mark(kinds, i, j, COMMENT);
        i = j;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        // 블록 주석: 중첩 없음 — 처음 만나는 */ 에서 닫힌다
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          throw new IllegalArgumentException("닫히지 않은 주석");
        }
        mark(kinds, i, close + 2, COMMENT);
        i = close + 2;
      } else if (c == '\'') {
        int j = quoteEnd(sql, i, '\'');
        mark(kinds, i, j, STRING);
        i = j;
      } else if (c == '"') {
        int j = quoteEnd(sql, i, '"');
        mark(kinds, i, j, QIDENT);
        i = j;
      } else if (isAltQuoteStart(sql, i)) {
        // q'<구분자>...<구분자>' / nq'...' — 시작 위치 i 는 q 또는 n
        int q = (c == 'n' || c == 'N') ? i + 1 : i;
        int delimPos = q + 2;
        if (delimPos >= n) {
          throw new IllegalArgumentException("닫히지 않은 대체 따옴표 리터럴");
        }
        char open = sql.charAt(delimPos);
        char close = closingDelimiter(open);
        int j = delimPos + 1;
        boolean closed = false;
        while (j + 1 < n) {
          if (sql.charAt(j) == close && sql.charAt(j + 1) == '\'') {
            closed = true;
            break;
          }
          j++;
        }
        if (!closed) {
          throw new IllegalArgumentException("닫히지 않은 대체 따옴표 리터럴");
        }
        mark(kinds, i, j + 2, STRING);
        alt = true;
        i = j + 2;
      } else if (isIdentChar(c)) {
        // 단어는 통째로 건너뛴다 — aq'x' 의 q 가 대체 따옴표로 오인되지 않게 하는 단어 경계 처리
        while (i < n && isIdentChar(sql.charAt(i))) {
          i++;
        }
      } else {
        i++;
      }
    }
    return alt;
  }

  /** i 위치가 q'/nq' 대체 따옴표의 시작인지 — 앞 문자가 식별자 문자가 아니고 q 또는 nq 바로 뒤에 ' 와 구분자가 있어야 한다. */
  private static boolean isAltQuoteStart(String sql, int i) {
    int n = sql.length();
    if (i > 0 && isIdentChar(sql.charAt(i - 1))) {
      return false;
    }
    char c = sql.charAt(i);
    int q = i;
    if (c == 'n' || c == 'N') {
      if (i + 1 >= n) {
        return false;
      }
      q = i + 1;
    }
    char qc = sql.charAt(q);
    return (qc == 'q' || qc == 'Q') && q + 1 < n && sql.charAt(q + 1) == '\'';
  }

  /** 여는 구분자에 대응하는 닫는 구분자 — 괄호류는 짝, 그 외는 같은 문자. */
  private static char closingDelimiter(char open) {
    return switch (open) {
      case '[' -> ']';
      case '{' -> '}';
      case '(' -> ')';
      case '<' -> '>';
      default -> open;
    };
  }

  /** quote 로 시작하는 따옴표 구간의 끝(다음 위치). 같은 따옴표 두 개는 이스케이프. */
  private static int quoteEnd(String sql, int start, char quote) {
    int n = sql.length();
    int j = start + 1;
    while (j < n) {
      if (sql.charAt(j) == quote) {
        if (j + 1 < n && sql.charAt(j + 1) == quote) {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    throw new IllegalArgumentException("닫히지 않은 따옴표");
  }

  private static void mark(byte[] kinds, int from, int to, byte kind) {
    if (kinds == null) {
      return;
    }
    for (int k = from; k < to; k++) {
      kinds[k] = kind;
    }
  }
}
