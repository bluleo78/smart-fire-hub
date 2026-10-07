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
          "SELECT 1 AS x FROM pub --\rWHERE 1 = /* /* */ '*/ (SELECT 1 FROM hidden LIMIT 1) --'\r");

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
            "SELECT 1 FROM data.t -- c\rWHERE a = 1 -- d\r\n")) {
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
