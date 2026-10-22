package com.smartfirehub.dataset.storage.tibero;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tibero 로 보낼 파이프라인 SQL 의 문장 가드(스펙 §4.3·§5.4) — 허용 문장만 통과시키는 fail-closed 검사.
 *
 * <p>실행 계정이 테넌트 OWNER 라 테넌트 모든 테이블을 읽을 수 있으므로, 문자열 안의 SQL 을 실행하는 함수로 테이블 판정 게이트를 우회하는 길을 막는다.
 * firehub-db-executor 가 이 클래스를 패키지명만 바꿔 복사해 같은 고정값으로 검증한다(JDK 외 의존성 없음).
 */
public final class TiberoSqlGuard {

  /** 허용 첫 키워드. */
  private static final Set<String> ALLOWED_FIRST =
      Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE");

  /** 이 접두로 시작하는 이름은 거부한다(단어 시작 기준). 패키지 묶음 이름이 접두로 갈리는 것들이다. */
  private static final List<String> DANGEROUS_PREFIXES = List.of("DBMS_", "UTL_", "OWA_");

  /**
   * 이 단어(전체 일치 — 뒤에 식별자 문자가 오지 않음)는 거부한다. {@code HTP}·{@code HTF} 는 Oracle 웹 툴킷 패키지 이름이 정확히 그 이름이라
   * 접두가 아니라 단어로 막는다 — 접두로 막으면 {@code HTF_RATE}·{@code HTPRICE} 같은 평범한 컬럼까지 거부한다.
   */
  private static final List<String> DANGEROUS_WORDS =
      List.of(
          "XMLQUERY",
          "XMLTABLE",
          "XMLEXISTS",
          "DBURITYPE",
          "HTTPURITYPE",
          "XDBURITYPE",
          "HTP",
          "HTF");

  /** 닫히지 않은 따옴표·주석 거부 안내. api 의 Tibero 어휘 검사({@code SqlValidator})도 같은 문구를 쓴다. */
  public static final String UNTERMINATED_MESSAGE = "SQL 의 따옴표 또는 주석이 닫히지 않았습니다.";

  private TiberoSqlGuard() {}

  /** 가드 위반 — 메시지는 사용자에게 그대로 보여 주는 한국어 안내다. */
  public static final class Rejected extends RuntimeException {
    public Rejected(String message) {
      super(message);
    }
  }

  /**
   * 단일 문장·허용 첫 키워드·PL/SQL 선언 부재·위험 이름 부재를 검사한다.
   *
   * @throws Rejected 위반 시
   */
  public static void requireAllowedStatement(String sql) {
    String body;
    String masked;
    try {
      body = TiberoSqlLexer.stripTrailingSemicolon(sql);
      if (TiberoSqlLexer.countTopLevelSemicolons(body) > 0) {
        throw new Rejected("Tibero 실행 SQL 은 한 문장만 허용됩니다.");
      }
      masked = TiberoSqlLexer.maskLiteralsAndComments(body);
    } catch (IllegalArgumentException e) {
      // 닫히지 않은 따옴표·주석은 어디까지가 코드인지 알 수 없으므로 거부(fail-closed)
      throw new Rejected(UNTERMINATED_MESSAGE);
    }
    String first = TiberoSqlLexer.firstKeyword(body);
    if (!ALLOWED_FIRST.contains(first)) {
      throw new Rejected("Tibero 실행 SQL 은 SELECT·WITH·INSERT·UPDATE·DELETE·MERGE 로 시작해야 합니다.");
    }
    if (first.equals("WITH")) {
      rejectPlsqlDeclaration(masked);
    }
    rejectDangerousNames(masked);
  }

  /**
   * {@code <식별자>.<식별자>(} 꼴(3단계 포함) 호출의 첫 식별자가 테넌트 스키마가 아니면 거부한다. {@code <별칭>.<컬럼>} 은 뒤에 {@code (} 가
   * 없어 해당 없다.
   *
   * <ul>
   *   <li>점 앞뒤의 공백·주석은 건너뛴다 — Tibero 는 {@code OTHER . FN(1)}·{@code OTHER /*c*}{@code / . FN(1)} 도
   *       한정 호출로 읽는다(주석은 마스킹 후 공백이다).
   *   <li>스키마 비교는 Tibero 식별자 규칙 그대로다 — 따옴표 없는 이름은 대문자로 접어, 따옴표 이름은 대소문자 그대로 정확히 비교한다. 인용 소문자 {@code
   *       "genia_t5"} 는 Tibero 에서 {@code GENIA_T5} 와 다른 스키마다.
   * </ul>
   *
   * @param tenantSchema 테넌트 OWNER 스키마의 실제 이름(예: {@code GENIA_T5})
   * @throws Rejected 다른 스키마로 한정된 호출이 있을 때
   */
  public static void requireNoForeignSchemaCalls(String sql, String tenantSchema) {
    String masked;
    try {
      masked = TiberoSqlLexer.maskLiteralsAndComments(sql);
    } catch (IllegalArgumentException e) {
      throw new Rejected(UNTERMINATED_MESSAGE);
    }
    int n = masked.length();
    int i = 0;
    while (i < n) {
      char c = masked.charAt(i);
      // 식별자 사슬의 시작: 큰따옴표 또는 글자(비ASCII·보충 평면 대리 문자 포함 — Tibero 는 따옴표 없는 비ASCII 식별자를 허용한다).
      boolean start = c == '"' || Character.isLetter(c) || Character.isSurrogate(c);
      boolean prevOk =
          i == 0
              || (!TiberoSqlLexer.isIdentChar(masked.charAt(i - 1))
                  && masked.charAt(i - 1) != '.'
                  && masked.charAt(i - 1) != '"');
      if (!start || !prevOk) {
        i++;
        continue;
      }
      // 식별자 사슬(a.b.c)을 읽는다 — 점 앞뒤 공백(마스킹된 주석 포함)은 건너뛴다.
      String first = null;
      int parts = 0;
      int j = i;
      while (true) {
        int[] end = new int[1];
        String ident = readIdent(masked, j, end);
        if (ident == null) {
          break;
        }
        if (first == null) {
          first = ident;
        }
        parts++;
        j = end[0];
        int dot = skipWhitespace(masked, j);
        if (dot < n && masked.charAt(dot) == '.') {
          // 점 뒤 공백도 건너뛰고 다음 식별자를 읽는다(식별자가 없으면 다음 반복에서 멈춘다).
          j = skipWhitespace(masked, dot + 1);
        } else {
          break;
        }
      }
      int k = skipWhitespace(masked, j);
      // readIdent 가 비따옴표는 대문자로 접고 따옴표는 원문 그대로 주므로 정확히 비교한다(Tibero 식별자 규칙).
      if (parts >= 2
          && k < n
          && masked.charAt(k) == '('
          && !isOuterJoinMarker(masked, k)
          && first != null
          && !first.equals(tenantSchema)) {
        throw new Rejected("테넌트 스키마가 아닌 스키마(" + first + ")의 함수 호출은 허용되지 않습니다.");
      }
      i = Math.max(j, i + 1);
    }
  }

  /** pos 부터 공백을 건너뛴 첫 위치(끝이면 길이). 마스킹 본문에서는 주석도 공백이다. */
  private static int skipWhitespace(String s, int pos) {
    int k = pos;
    while (k < s.length() && Character.isWhitespace(s.charAt(k))) {
      k++;
    }
    return k;
  }

  /** pos 의 '(' 가 Oracle 외부 조인 표기 {@code (+)} (사이 공백 허용)인지 — 함수 호출이 아니다. */
  private static boolean isOuterJoinMarker(String masked, int pos) {
    int n = masked.length();
    int k = pos + 1;
    while (k < n && Character.isWhitespace(masked.charAt(k))) {
      k++;
    }
    if (k >= n || masked.charAt(k) != '+') {
      return false;
    }
    k++;
    while (k < n && Character.isWhitespace(masked.charAt(k))) {
      k++;
    }
    return k < n && masked.charAt(k) == ')';
  }

  /** WITH 다음 첫 단어가 FUNCTION/PROCEDURE 면 PL/SQL 선언이라 거부한다. masked 는 주석이 공백이라 건너뛰기가 단순하다. */
  private static void rejectPlsqlDeclaration(String masked) {
    int i = 0;
    int n = masked.length();
    while (i < n && (Character.isWhitespace(masked.charAt(i)) || masked.charAt(i) == '(')) {
      i++;
    }
    i += 4; // WITH
    while (i < n && Character.isWhitespace(masked.charAt(i))) {
      i++;
    }
    int s = i;
    while (i < n && TiberoSqlLexer.isIdentChar(masked.charAt(i))) {
      i++;
    }
    String word = masked.substring(s, i).toUpperCase(Locale.ROOT);
    if (word.equals("FUNCTION") || word.equals("PROCEDURE")) {
      throw new Rejected("Tibero 실행 SQL 에서는 WITH 절의 PL/SQL 선언(" + word + ")을 허용하지 않습니다.");
    }
  }

  /** 마스킹한 본문에서 위험 이름을 단어 경계로 찾아 거부한다. 큰따옴표 식별자 안도 검사한다. */
  private static void rejectDangerousNames(String masked) {
    String up = masked.toUpperCase(Locale.ROOT);
    int n = up.length();
    for (int i = 0; i < n; i++) {
      if (i > 0 && TiberoSqlLexer.isIdentChar(up.charAt(i - 1))) {
        continue; // 단어 시작이 아님
      }
      for (String p : DANGEROUS_PREFIXES) {
        if (up.startsWith(p, i)) {
          throw rejectedName(identRun(up, i));
        }
      }
      for (String w : DANGEROUS_WORDS) {
        if (up.startsWith(w, i) && !endsInIdent(up, i + w.length())) {
          throw rejectedName(w);
        }
      }
      if (up.startsWith("SYS.", i)) {
        throw rejectedName("SYS.");
      }
      if (up.startsWith("CURSOR", i) && !endsInIdent(up, i + 6)) {
        int k = i + 6;
        while (k < n && Character.isWhitespace(up.charAt(k))) {
          k++;
        }
        if (k < n && up.charAt(k) == '(') {
          throw rejectedName("CURSOR");
        }
      }
    }
  }

  private static Rejected rejectedName(String name) {
    return new Rejected("Tibero 에서 허용하지 않는 함수·패키지(" + name + ")가 포함되어 있습니다.");
  }

  private static boolean endsInIdent(String s, int pos) {
    return pos < s.length() && TiberoSqlLexer.isIdentChar(s.charAt(pos));
  }

  /** pos 부터 이어지는 식별자 문자열(보고용 이름). */
  private static String identRun(String s, int pos) {
    int e = pos;
    while (e < s.length() && TiberoSqlLexer.isIdentChar(s.charAt(e))) {
      e++;
    }
    return s.substring(pos, e);
  }

  /** pos 에서 식별자 하나를 읽는다. 따옴표 식별자는 그대로(따옴표 제거), 비따옴표는 대문자로 정규화한다. 식별자가 아니면 null. end[0] 에 끝 위치. */
  private static String readIdent(String s, int pos, int[] end) {
    int n = s.length();
    if (pos >= n) {
      return null;
    }
    char c = s.charAt(pos);
    if (c == '"') {
      StringBuilder sb = new StringBuilder();
      int j = pos + 1;
      while (j < n) {
        if (s.charAt(j) == '"') {
          if (j + 1 < n && s.charAt(j + 1) == '"') {
            sb.append('"');
            j += 2;
            continue;
          }
          end[0] = j + 1;
          return sb.toString();
        }
        sb.append(s.charAt(j));
        j++;
      }
      return null;
    }
    if (!TiberoSqlLexer.isIdentChar(c)) {
      return null;
    }
    int j = pos;
    while (j < n && TiberoSqlLexer.isIdentChar(s.charAt(j))) {
      j++;
    }
    end[0] = j;
    return s.substring(pos, j).toUpperCase(Locale.ROOT);
  }
}
