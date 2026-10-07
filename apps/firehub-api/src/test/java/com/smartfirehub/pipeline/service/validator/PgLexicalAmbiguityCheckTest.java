package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PG 와 JSqlParser 의 어휘 규칙이 갈리는 표기가 검증·참조 추출을 우회하지 못하는지 고정한다(보안 등급 Task 13 재리뷰).
 *
 * <p>아래 우회 문자열은 모두 리뷰에서 PG 실측으로 hidden 을 스캔하는 것이 확인됐고, 사전 검사 전에는 validate 를 통과하고 referencedTables 가
 * hidden 을 보지 못했다.
 */
class PgLexicalAmbiguityCheckTest {

  private final SqlValidator permissive = new SqlValidator("data", true);
  private final SqlValidator strict = new SqlValidator("data", false);

  /** PG 실측 우회 6종 + 일반 문자열 끝의 \' (JSqlParser 가 이스케이프로 읽을 수 있는 표기). */
  static final List<String> BYPASSES =
      List.of(
          // PG 는 블록 주석을 중첩한다
          "SELECT 1 AS x FROM pub WHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'",
          "SELECT 1 AS x FROM pub WHERE 1 = /* /* */ $$*/ (SELECT 1 FROM hidden LIMIT 1) --$$",
          "SELECT 1 AS x FROM pub WHERE 1 = /* /* */ \"*/ (SELECT 1 FROM hidden LIMIT 1) --\"",
          // E 문자열 백슬래시 이스케이프
          "SELECT E'\\' /*' AS a FROM pub, hidden -- */ AS a FROM pub",
          // 태그 달러 인용
          "SELECT $a$ ' $a$ AS x FROM pub, hidden --'",
          "SELECT $a$ /* $a$ AS x FROM pub, hidden -- */",
          // 일반 문자열 끝의 백슬래시(standard_conforming_strings=on 이면 PG 에선 리터럴 \)
          "SELECT '\\' AS a FROM pub, hidden -- '",
          // PG 는 줄 주석을 \r 에서도 끝낸다(newline [\n\r]) — \r 뒤 코드도 훑어야 한다
          "SELECT 1 AS x FROM pub --\rWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\n",
          "SELECT 1 AS x FROM pub --\r\nWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\r\n",
          "SELECT 1 AS x FROM pub --\rWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\r",
          // JSqlParser 는 // 를 줄 주석으로 본다. PG 는 //* 를 / + 블록 주석으로 자른다(PG 실측: hidden 값 반환)
          "SELECT 0 * 1 //* c */ 1 + (SELECT a FROM hidden LIMIT 1) AS v",
          "SELECT 1 AS x FROM pub WHERE 1 = 1 //* c */ + (SELECT 1 FROM hidden LIMIT 1)",
          // 오라클 대체 인용: JSqlParser 는 q'[ ... ]' 를 리터럴 하나로, PG 는 q + 일반 문자열로 자른다
          "SELECT q'[ ' AS c, (SELECT a FROM hidden LIMIT 1) AS w -- ]' FROM pub",
          // 본문에 $ 가 든 $$: JSqlParser 는 $$a$ 를 식별자로 읽는다(PG 실측: hidden 값 반환)
          "SELECT $$a$ = '$$ AS c, (SELECT a FROM hidden LIMIT 1) AS d -- '",
          // 숫자 뒤 $$: JSqlParser 는 1$$ 를 식별자로 읽는다
          "SELECT 1$$ ' $$ AS c, (SELECT a FROM hidden LIMIT 1) AS d -- '",
          // 백틱: JSqlParser 는 `...` 를 식별자 하나로 읽는다
          "SELECT `x, (SELECT a FROM hidden LIMIT 1)` FROM pub");

  static List<String> bypasses() {
    return BYPASSES;
  }

  /** 우회 문자열마다 독립 케이스 — 사전 검사를 끄면 각 케이스가 따로 실패해야 한다(변이 확인). */
  @ParameterizedTest
  @MethodSource("bypasses")
  void bypasses_areRejectedByValidateAndReferencedTables(String sql) {
    {
      assertThatThrownBy(() -> permissive.validate(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
      assertThatThrownBy(() -> permissive.referencedTables(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
      assertThatThrownBy(() -> permissive.unqualifiedTableNames(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
      assertThatThrownBy(() -> PgLexicalAmbiguityCheck.requireUnambiguous(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
    }
  }

  @Test
  void unterminatedTokens_areRejected() {
    for (String sql :
        List.of(
            "SELECT 1 FROM data.t /* open",
            "SELECT 'open FROM data.t",
            "SELECT \"open FROM data.t",
            "SELECT $$ open FROM data.t")) {
      assertThatThrownBy(() -> PgLexicalAmbiguityCheck.requireUnambiguous(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
    }
  }

  private static void assertRejected(String... sqls) {
    for (String sql : sqls) {
      assertThatThrownBy(() -> PgLexicalAmbiguityCheck.requireUnambiguous(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
    }
  }

  /** JSqlParser 줄 주석 // — //* 뿐 아니라 문자열·주석 밖의 // 는 전부 거부(PG 에 // 연산자 없음). */
  @Test
  void doubleSlash_isRejected() {
    assertRejected(
        "SELECT 0 * 1 //* c */ 1 FROM data.t",
        "SELECT 4 // 2 FROM data.t",
        "SELECT 1 FROM data.t // c",
        "SELECT 2 *// x\n FROM data.t");
  }

  /** 오라클 대체 인용 q'[ q'( q'{ q'' (대소문자·접두어 무관). */
  @Test
  void oracleQQuote_isRejected() {
    assertRejected(
        "SELECT q'[x]' FROM data.t",
        "SELECT Q'(x)' FROM data.t",
        "SELECT q'{x}' FROM data.t",
        "SELECT q''x'' FROM data.t",
        "SELECT eq'[x]' FROM data.t",
        "SELECT Nq'[x]' FROM data.t");
  }

  /** 본문에 $ 가 든 태그 없는 달러 인용 — JSqlParser 의 $$ 인용 본문은 $ 를 못 담는다. */
  @Test
  void untaggedDollarQuoteWithDollarInBody_isRejected() {
    assertRejected(
        "SELECT $$a$ = '$$ AS c FROM data.t",
        "SELECT $$ $ $$ FROM data.t",
        "SELECT $$$ FROM data.t");
  }

  /** JSqlParser 식별자에만 붙는 글자(숫자·#·@·$) 바로 뒤의 $$ — JSqlParser 는 $$ 를 식별자 일부로 먹는다. */
  @Test
  void dollarQuoteAfterJsqlOnlyIdentChar_isRejected() {
    assertRejected(
        "SELECT 1$$x$$ FROM data.t",
        "SELECT 3 #$$x$$ FROM data.t",
        "SELECT x@$$x$$ FROM data.t",
        "SELECT $1$$x$$ FROM data.t",
        "SELECT $$x$$$$y$$ FROM data.t");
  }

  /** 백틱 인용 식별자 — JSqlParser 만 인용으로 본다. */
  @Test
  void backtick_isRejected() {
    assertRejected("SELECT `a` FROM data.t", "SELECT 1 FROM data.t WHERE a ` b");
  }

  /**
   * 사전 검사를 바꾸지 않아도 JSqlParser 가 이미 거부하는 차이(문법 전수 대조 결과) — 회귀 시 우회가 되지 않게 고정한다: 줄바꿈 든 큰따옴표 식별자·\f
   * 공백(어휘 오류), \n/\n 문장 구분자(멀티 스테이트먼트), \n\n\n·\ngo\n 문장 구분자(파싱 실패).
   */
  @Test
  void otherJsqlOnlyDivergences_failClosedInParser() {
    for (String sql :
        List.of(
            "SELECT 1 AS \"x\n', (SELECT a FROM hidden LIMIT 1) AS w -- \" FROM pub",
            "SELECT 1\fFROM hidden",
            "SELECT 4\n/\n(SELECT a FROM hidden LIMIT 1)",
            "SELECT 1\n\n\n+ (SELECT a FROM hidden LIMIT 1)",
            "SELECT 1 AS v\ngo\n, (SELECT a FROM hidden LIMIT 1)")) {
      assertThatCode(() -> PgLexicalAmbiguityCheck.requireUnambiguous(sql))
          .as(sql)
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> permissive.referencedTables(sql))
          .as(sql)
          .isInstanceOf(UnsafeSqlException.class);
    }
  }

  /** 대조군 — PG 와 JSqlParser 가 같게 자르는 정상 표기는 그대로 통과한다. */
  @Test
  void unambiguousForms_stillPass() {
    for (String sql :
        List.of(
            "SELECT $$it's /* not a comment */ -- nor this$$ AS x FROM data.t",
            "SELECT 'it''s' AS x FROM data.t",
            "SELECT 'C:\\path\\x' AS x FROM data.t",
            "SELECT 1 FROM data.t /* hidden */ WHERE a = 1 -- hidden",
            "SELECT \"we\"\"ird\" FROM data.t",
            "SELECT e'plain' AS x, somee'x' FROM data.t",
            "SELECT a$$b FROM data.t",
            "SELECT 1 FROM data.t -- c\rWHERE a = 1 -- d\r\n",
            // 문자열·주석·$$ 안의 //·`·q'[ 는 PG·JSqlParser 모두 그 안의 글자다
            "SELECT 'http://x/`q'' q''[' AS u FROM data.t /* // ` q'[ */ -- // ` q'[",
            "SELECT $$http://x ` q'[$$ AS u, 4 / 2 AS d, a $$x$$ FROM data.t",
            "SELECT q 'x', $1, a.b$c FROM data.t")) {
      assertThatCode(() -> PgLexicalAmbiguityCheck.requireUnambiguous(sql))
          .as(sql)
          .doesNotThrowAnyException();
    }
    assertThatCode(
            () -> strict.validate("SELECT $$x$$ AS a, 'it''s' AS b FROM data.t /* c */ -- d"))
        .doesNotThrowAnyException();
    // 진짜 주석 안의 테이블 이름은 참조가 아니다
    assertThat(permissive.referencedTables("SELECT 1 FROM pub /* hidden */ -- hidden").reads())
        .containsExactly(new SqlValidator.TableName(null, "pub"));
  }

  /** 태그 없는 $$ 는 허용하므로 JSqlParser 가 PG 와 같은 경계로 자르는지 확인한다 — 안의 따옴표가 뒤의 hidden 을 숨기면 안 된다. */
  @Test
  void untaggedDollarQuote_isLexedLikePg() {
    var refs = permissive.referencedTables("SELECT $$ ' $$ AS x FROM pub, hidden --'");
    assertThat(refs.reads())
        .contains(
            new SqlValidator.TableName(null, "pub"), new SqlValidator.TableName(null, "hidden"));
    var refs2 = permissive.referencedTables("SELECT $$ /* $$ AS x FROM pub, hidden -- */");
    assertThat(refs2.reads()).contains(new SqlValidator.TableName(null, "hidden"));
  }
}
