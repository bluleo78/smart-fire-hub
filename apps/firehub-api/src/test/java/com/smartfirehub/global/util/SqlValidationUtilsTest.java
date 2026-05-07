package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SqlValidationUtils 단위 테스트.
 *
 * <p>특히 CTE(WITH 절) 쿼리에서 문자열 리터럴 내부에 DML 키워드가 포함될 때
 * detectQueryType()이 올바르게 SELECT로 분류하는지 검증한다. (GitHub 이슈 #161)
 */
class SqlValidationUtilsTest {

  // ─── detectQueryType: 일반 쿼리 ────────────────────────────────────────────

  @Test
  void detectQueryType_plainSelect_returnsSelect() {
    assertThat(SqlValidationUtils.detectQueryType("SELECT * FROM foo")).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_plainInsert_returnsInsert() {
    assertThat(SqlValidationUtils.detectQueryType("INSERT INTO foo VALUES (1)"))
        .isEqualTo("INSERT");
  }

  @Test
  void detectQueryType_plainUpdate_returnsUpdate() {
    assertThat(SqlValidationUtils.detectQueryType("UPDATE foo SET col=1")).isEqualTo("UPDATE");
  }

  @Test
  void detectQueryType_plainDelete_returnsDelete() {
    assertThat(SqlValidationUtils.detectQueryType("DELETE FROM foo WHERE id=1"))
        .isEqualTo("DELETE");
  }

  // ─── detectQueryType: CTE + SELECT (이슈 #161 핵심 케이스) ────────────────

  @Test
  void detectQueryType_cteWithSelectBody_returnsSelect() {
    // 단순 CTE + SELECT
    String sql = "WITH cte AS (SELECT id FROM customers) SELECT * FROM cte";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithStringLiteralContainingUpdate_returnsSelect() {
    // 이슈 #161 재현 케이스: 문자열 리터럴 'needs update'가 UPDATE로 오탐되던 버그
    String sql =
        "WITH x AS (SELECT id, 'needs update' AS info FROM customers) SELECT * FROM x";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithStringLiteralContainingInsert_returnsSelect() {
    // 이슈 #161: INSERT 키워드를 문자열 리터럴 내에 포함한 CTE
    String sql =
        "WITH cte AS (SELECT 'INSERT INTO foo' AS txt) SELECT * FROM cte";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithStringLiteralContainingDelete_returnsSelect() {
    // 이슈 #161: DELETE 키워드를 문자열 리터럴 내에 포함한 CTE
    String sql =
        "WITH cte AS (SELECT 'delete old rows' AS action, id FROM t) SELECT * FROM cte";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithMultipleStringLiterals_returnsSelect() {
    // 여러 문자열 리터럴에 DML 키워드가 산재한 경우
    String sql =
        "WITH a AS (SELECT 'insert' AS op, 'update me' AS note FROM t)"
            + " SELECT op, note FROM a";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithEscapedQuoteInLiteral_returnsSelect() {
    // 이중 따옴표 이스케이프('')가 포함된 리터럴 처리
    String sql =
        "WITH cte AS (SELECT 'it''s an update' AS info FROM t) SELECT * FROM cte";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  // ─── detectQueryType: CTE + 실제 DML ──────────────────────────────────────

  @Test
  void detectQueryType_cteFollowedByInsert_returnsInsert() {
    // 실제로 본문이 INSERT인 CTE
    String sql = "WITH src AS (SELECT id FROM foo) INSERT INTO bar SELECT id FROM src";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("INSERT");
  }

  @Test
  void detectQueryType_cteFollowedByUpdate_returnsUpdate() {
    // 실제로 본문이 UPDATE인 CTE
    String sql = "WITH src AS (SELECT id FROM foo) UPDATE bar SET col=1 WHERE id IN (SELECT id FROM src)";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("UPDATE");
  }

  @Test
  void detectQueryType_cteFollowedByDelete_returnsDelete() {
    // 실제로 본문이 DELETE인 CTE
    String sql = "WITH old AS (SELECT id FROM foo WHERE ts < NOW()) DELETE FROM foo WHERE id IN (SELECT id FROM old)";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("DELETE");
  }

  // ─── stripStringLiterals ──────────────────────────────────────────────────

  @Test
  void stripStringLiterals_noLiterals_returnsUnchanged() {
    String sql = "SELECT id FROM foo";
    assertThat(SqlValidationUtils.stripStringLiterals(sql)).isEqualTo(sql);
  }

  @Test
  void stripStringLiterals_singleLiteral_removesContent() {
    String sql = "SELECT 'hello world' AS col FROM foo";
    // 리터럴 내용이 제거되어 키워드가 남지 않아야 한다
    String result = SqlValidationUtils.stripStringLiterals(sql);
    assertThat(result).doesNotContain("hello world");
    assertThat(result).contains("SELECT");
  }

  @Test
  void stripStringLiterals_escapedQuote_handledCorrectly() {
    // '' 이스케이프가 포함된 리터럴
    String sql = "SELECT 'it''s fine' AS col";
    String result = SqlValidationUtils.stripStringLiterals(sql);
    assertThat(result).doesNotContain("it");
    assertThat(result).doesNotContain("fine");
  }
}
