package com.smartfirehub.pipeline.service.validator;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;

/**
 * PostgreSQL 어휘 규칙과 JSqlParser 어휘 규칙이 갈리는 표기를 파싱 전에 거부하는 fail-closed 사전 검사.
 *
 * <p><b>왜 필요한가.</b> SQL 검증(스키마 화이트리스트)과 보안 등급 판정(참조 테이블 추출)은 JSqlParser 의 토큰 경계를 믿는다. 그런데 PG 와
 * JSqlParser 는 몇몇 표기에서 "어디까지가 주석/문자열인가"를 다르게 자른다 — 그 틈에 테이블 참조를 넣으면 JSqlParser 는 주석·리터럴로 보고 지나치고 PG
 * 는 실제로 읽는다(실측: 중첩 블록 주석 {@code /* /* *}{@code / ... }, E'\'' 백슬래시 이스케이프, 태그 달러 인용 {@code $a$ ...
 * $a$}, 문자열 끝의 {@code \'}). 이 검사는 PG 렉서 규칙을 따라 원문을 훑어 그런 표기가 하나라도 있으면 거부한다.
 *
 * <p><b>거부 대상</b>: 블록 주석 안의 중첩 {@code /*}, 백슬래시가 든 E/e 문자열, 일반 문자열에서 따옴표 바로 앞의 백슬래시(JSqlParser 가
 * {@code \'} 를 이스케이프로 읽을 수 있다), 태그 달러 인용({@code $tag$}), 닫히지 않은 문자열·식별자·주석·달러 인용, {@code //}
 * (JSqlParser 줄 주석), 백틱 인용, 오라클 대체 인용({@code q'[...]'} 등), 본문에 {@code $} 가 든 {@code $$...$$},
 * 숫자·{@code #}·{@code @}·{@code $} 바로 뒤의 {@code $$}.
 *
 * <p>이 목록은 JSqlParser 5.0 문법(JSqlParserCC.jjt)의 주석·문자열·인용·구분자 토큰 정의를 PG 렉서와 하나씩 대조해 만들었다. 나머지 차이는
 * JSqlParser 쪽에서 이미 거부된다: 줄바꿈이 든 큰따옴표 식별자와 {@code \f} 공백은 어휘 오류가 되고, {@code \n/\n}·{@code
 * \ngo\n}·{@code \n\n\n} 문장 구분자는 멀티 스테이트먼트나 파싱 실패가 된다.
 *
 * <p><b>허용(PG 와 JSqlParser 가 같게 자르는 것)</b>: {@code ''} 이중 따옴표, 단일 수준 블록 주석, {@code --} 줄 주석, 따옴표
 * 식별자({@code ""} 이중), 태그 없는 {@code $$...$$}.
 *
 * <p>전제: {@code standard_conforming_strings = on}(PG 9.1+ 기본값) — 일반 문자열의 백슬래시는 리터럴 문자다.
 */
public final class PgLexicalAmbiguityCheck {

  private PgLexicalAmbiguityCheck() {}

  /** 모호한 표기가 있으면 {@link UnsafeSqlException}(400). */
  public static void requireUnambiguous(String sql) {
    if (sql == null) {
      return; // 빈 입력 처리는 호출자(파서) 몫 — 여기서는 어휘 모호성만 본다.
    }
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char ch = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
      if (ch == '-' && next == '-') {
        // 줄 주석: 줄 끝(또는 입력 끝)까지. 줄 주석 안의 /* · ' 등은 의미가 없다.
        // PG 렉서는 줄 끝을 newline [\n\r] 로 정의한다 — \r 하나만으로도 줄 주석이 끝난다(JSqlParser 도 같다).
        // \n 만 찾으면 "--\r<코드>\n" 의 <코드>를 훑지 않아 그 안의 모호한 표기를 놓친다(실측 우회).
        i = lineCommentEnd(sql, i + 2);
      } else if (ch == '/' && next == '/') {
        // JSqlParser 는 "//" 를 줄 주석으로 본다(LINE_COMMENT: ("--" | "//") ...).
        // PG 에게 "//" 는 연산자 문자이고, "//*" 는 연산자 "/" + 블록 주석 시작이다(연산자 안의 "/*" 앞에서 자른다).
        // 실측: "SELECT 0 * 1 //* c */ 1 + (SELECT a FROM hidden LIMIT 1)" 를 PG 는 hidden 을 읽어 실행했고
        // JSqlParser 는 줄 끝까지 주석으로 버렸다. PG 에는 "//" 연산자가 없어 정상 SQL 이 쓸 일이 없으므로
        // 문자열·주석 밖의 "//" 는 전부 거부한다.
        throw new UnsafeSqlException("'//' 는 허용되지 않습니다. 주석은 -- 또는 /* */ 를 쓰세요.");
      } else if (ch == '/' && next == '*') {
        i = skipBlockComment(sql, i);
      } else if (ch == '`') {
        // JSqlParser 는 `...` 를 MySQL 식 따옴표 식별자로 묶어 안의 코드를 이름 하나로 본다. PG 에게 ` 는 연산자 문자일 뿐이다.
        // PG 에 ` 연산자가 없어 정상 SQL 에는 나오지 않는다. 경계가 갈리는 표기라서 거부한다.
        throw new UnsafeSqlException("백틱(`)은 허용되지 않습니다. 식별자는 큰따옴표로 감싸세요.");
      } else if (ch == '\'') {
        // JSqlParser 는 [접두어]q'[...]' · q'(...)' · q'{...}' · q''...'' 를 오라클 대체 인용 리터럴 하나로 읽는다
        // (본문은 가장 긴 일치라 입력 뒤쪽의 닫는 표기까지 간다). PG 에게 q 는 식별자(타입 이름)이고 ' 부터 일반
        // 문자열이라 리터럴 끝이 갈린다. 접두어가 붙은 "eq'[" 도 JSqlParser 는 리터럴로 읽으므로 q 가 식별자의
        // 일부인지와 무관하게 거부한다.
        if (i > 0
            && (sql.charAt(i - 1) == 'q' || sql.charAt(i - 1) == 'Q')
            && isQQuoteOpener(next)) {
          throw new UnsafeSqlException("q'...' 형태의 대체 인용 표기는 허용되지 않습니다.");
        }
        // E/e 접두어는 식별자의 일부가 아닐 때만 문자열 접두어다(PG 렉서 최장 일치 — "somee'x'" 는 식별자 + 일반 문자열).
        // 앞 글자가 숫자면(1e'..', x1e'..') 숫자 렉싱 규칙이 얽혀 판단이 갈릴 수 있으므로 보수적으로 E 문자열로 본다.
        boolean escapeString =
            i > 0
                && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                && (i < 2 || !isLetterOrDollar(sql.charAt(i - 2)));
        i = skipSingleQuoted(sql, i, escapeString);
      } else if (ch == '"') {
        i = skipDoubleQuoted(sql, i);
      } else if (ch == '$') {
        // JSqlParser 식별자는 숫자·#·$ 로 시작할 수 있고 @ 도 이어 붙인다(LETTER/PART_LETTER).
        // 그래서 "1$$", "#$$", "x@$$", "$1$$" 의 $$ 는 JSqlParser 에겐 식별자의 일부이고 PG 에겐 달러 인용 시작이다.
        // 실측: "SELECT 1$$ ' $$ ..., hidden -- '" 를 JSqlParser 는 식별자 + ' 문자열로 읽어 hidden 을 못 봤다.
        // 영문자·_·비 ASCII 뒤의 $ 는 위 식별자 분기가 PG 와 같게 먹으므로 여기 오지 않는다.
        if (next == '$' && i > 0 && isJsqlOnlyIdentChar(sql.charAt(i - 1))) {
          throw new UnsafeSqlException("숫자·#·@·$ 바로 뒤의 달러 인용($$)은 허용되지 않습니다. 앞에 공백을 두세요.");
        }
        i = skipDollar(sql, i);
      } else if (isIdentStart(ch)) {
        // 식별자·키워드: 뒤따르는 $ 는 식별자 문자다(PG ident_cont) — "a$$b" 는 달러 인용이 아니다.
        i++;
        while (i < n && isIdentChar(sql.charAt(i))) {
          i++;
        }
      } else {
        i++;
      }
    }
  }

  /** 줄 주석 다음 위치 — 첫 {@code \n} 또는 {@code \r}(PG {@code newline [\n\r]}) 다음, 없으면 입력 끝. */
  private static int lineCommentEnd(String sql, int from) {
    for (int j = from; j < sql.length(); j++) {
      char ch = sql.charAt(j);
      if (ch == '\n' || ch == '\r') {
        return j + 1;
      }
    }
    return sql.length();
  }

  /** 블록 주석을 건너뛴다. PG 는 블록 주석을 중첩하고 JSqlParser 는 첫 {@code *}{@code /} 에서 닫으므로 중첩 여는 표기는 거부한다. */
  private static int skipBlockComment(String sql, int start) {
    int n = sql.length();
    int i = start + 2;
    while (i < n) {
      char ch = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
      if (ch == '/' && next == '*') {
        throw new UnsafeSqlException("중첩된 블록 주석(/* 안의 /*)은 허용되지 않습니다.");
      }
      if (ch == '*' && next == '/') {
        return i + 2;
      }
      i++;
    }
    throw new UnsafeSqlException("닫히지 않은 블록 주석이 있습니다.");
  }

  /**
   * 작은따옴표 문자열을 건너뛴다({@code ''} 는 따옴표 문자). E 문자열의 백슬래시와 일반 문자열에서 따옴표 바로 앞의 백슬래시는 PG 와 JSqlParser 가
   * 문자열 끝을 다르게 볼 수 있어 거부한다.
   */
  private static int skipSingleQuoted(String sql, int start, boolean escapeString) {
    int n = sql.length();
    int i = start + 1;
    while (i < n) {
      char ch = sql.charAt(i);
      if (ch == '\\') {
        if (escapeString) {
          throw new UnsafeSqlException("백슬래시 이스케이프가 있는 E'...' 문자열은 허용되지 않습니다.");
        }
        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
          throw new UnsafeSqlException("작은따옴표 바로 앞의 백슬래시(\\')는 해석이 모호해 허용되지 않습니다. 따옴표는 ''로 쓰세요.");
        }
      } else if (ch == '\'') {
        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
          i += 2; // '' — 문자열 안의 따옴표 문자
          continue;
        }
        return i + 1;
      }
      i++;
    }
    throw new UnsafeSqlException("닫히지 않은 문자열 리터럴이 있습니다.");
  }

  /** 큰따옴표 식별자를 건너뛴다({@code ""} 는 따옴표 문자). */
  private static int skipDoubleQuoted(String sql, int start) {
    int n = sql.length();
    int i = start + 1;
    while (i < n) {
      if (sql.charAt(i) == '"') {
        if (i + 1 < n && sql.charAt(i + 1) == '"') {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    throw new UnsafeSqlException("닫히지 않은 따옴표 식별자가 있습니다.");
  }

  /**
   * {@code $} 로 시작하는 토큰. {@code $$} 는 태그 없는 달러 인용(다음 {@code $$} 까지), {@code $tag$} 는 태그 달러 인용(거부),
   * {@code $1} 같은 위치 파라미터·그 밖의 {@code $} 는 한 글자로 넘긴다.
   */
  private static int skipDollar(String sql, int start) {
    int n = sql.length();
    if (start + 1 < n && sql.charAt(start + 1) == '$') {
      int close = sql.indexOf("$$", start + 2);
      if (close < 0) {
        throw new UnsafeSqlException("닫히지 않은 달러 인용($$)이 있습니다.");
      }
      // JSqlParser 의 $$ 인용은 본문에 $ 를 허용하지 않는다("$$" (~["$"])* "$$").
      // 본문에 $ 가 있으면 JSqlParser 는 $$ 를 식별자로 읽고 본문 속 ' 부터 문자열을 시작하지만,
      // PG 는 다음 $$ 까지를 문자열로 본다.
      // 실측: "SELECT $$a$ = '$$ AS c, (SELECT a FROM hidden LIMIT 1) AS d -- '" 를 PG 는 hidden 을 읽어
      // 실행했고 JSqlParser 는 테이블을 하나도 보지 못했다.
      if (sql.indexOf('$', start + 2) < close) {
        throw new UnsafeSqlException("달러 인용($$...$$) 안에는 $ 문자를 쓸 수 없습니다.");
      }
      return close + 2;
    }
    if (start + 1 < n && isIdentStart(sql.charAt(start + 1))) {
      // PG dolqdelim: $ + 태그(식별자 시작 문자 + [A-Za-z0-9_]*) + $
      int j = start + 2;
      while (j < n && isTagChar(sql.charAt(j))) {
        j++;
      }
      if (j < n && sql.charAt(j) == '$') {
        throw new UnsafeSqlException("태그가 있는 달러 인용($tag$...$tag$)은 허용되지 않습니다. $$...$$ 를 쓰세요.");
      }
    }
    return start + 1;
  }

  /** JSqlParser 오라클 대체 인용의 여는 표기: q 뒤 따옴표 다음 글자가 [ ( { ' 중 하나. */
  private static boolean isQQuoteOpener(char ch) {
    return ch == '[' || ch == '(' || ch == '{' || ch == '\'';
  }

  /**
   * PG 식별자에는 못 붙지만 JSqlParser 식별자에는 붙는 글자(숫자·#·@·$). 이 글자 바로 뒤의 $$ 는 JSqlParser 식별자에 먹힌다. 이 위치의 숫자는
   * 식별자 분기 밖(숫자 리터럴·$1 등)이므로 PG 에게는 식별자가 아니다.
   */
  private static boolean isJsqlOnlyIdentChar(char ch) {
    return (ch >= '0' && ch <= '9') || ch == '#' || ch == '@' || ch == '$';
  }

  /** PG ident_start: 영문자·밑줄·비 ASCII. */
  private static boolean isIdentStart(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_' || ch >= 0x80;
  }

  /** PG ident_cont: ident_start + 숫자 + {@code $}. */
  private static boolean isIdentChar(char ch) {
    return isIdentStart(ch) || (ch >= '0' && ch <= '9') || ch == '$';
  }

  /** 숫자를 뺀 식별자 문자(E 접두어 판정용 — 앞 글자가 숫자면 보수적으로 경계로 본다). */
  private static boolean isLetterOrDollar(char ch) {
    return isIdentStart(ch) || ch == '$';
  }

  /** 달러 인용 태그 문자: ident_cont 에서 {@code $} 를 뺀 것. */
  private static boolean isTagChar(char ch) {
    return isIdentStart(ch) || (ch >= '0' && ch <= '9');
  }
}
