package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.exception.SqlQueryException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SqlValidationUtils 단위 테스트.
 *
 * <p>특히 CTE(WITH 절) 쿼리에서 문자열 리터럴 내부에 DML 키워드가 포함될 때 detectQueryType()이 올바르게 SELECT로 분류하는지 검증한다.
 * (GitHub 이슈 #161)
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
    String sql = "WITH x AS (SELECT id, 'needs update' AS info FROM customers) SELECT * FROM x";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithStringLiteralContainingInsert_returnsSelect() {
    // 이슈 #161: INSERT 키워드를 문자열 리터럴 내에 포함한 CTE
    String sql = "WITH cte AS (SELECT 'INSERT INTO foo' AS txt) SELECT * FROM cte";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithStringLiteralContainingDelete_returnsSelect() {
    // 이슈 #161: DELETE 키워드를 문자열 리터럴 내에 포함한 CTE
    String sql = "WITH cte AS (SELECT 'delete old rows' AS action, id FROM t) SELECT * FROM cte";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithMultipleStringLiterals_returnsSelect() {
    // 여러 문자열 리터럴에 DML 키워드가 산재한 경우
    String sql =
        "WITH a AS (SELECT 'insert' AS op, 'update me' AS note FROM t)" + " SELECT op, note FROM a";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  @Test
  void detectQueryType_cteWithEscapedQuoteInLiteral_returnsSelect() {
    // 이중 따옴표 이스케이프('')가 포함된 리터럴 처리
    String sql = "WITH cte AS (SELECT 'it''s an update' AS info FROM t) SELECT * FROM cte";
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
    String sql =
        "WITH src AS (SELECT id FROM foo) UPDATE bar SET col=1 WHERE id IN (SELECT id FROM src)";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("UPDATE");
  }

  @Test
  void detectQueryType_cteFollowedByDelete_returnsDelete() {
    // 실제로 본문이 DELETE인 CTE
    String sql =
        "WITH old AS (SELECT id FROM foo WHERE ts < NOW()) DELETE FROM foo WHERE id IN (SELECT id FROM old)";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("DELETE");
  }

  // ─── detectQueryType: data-modifying CTE (이슈 #227 보안 결함) ────────────
  // PostgreSQL data-modifying CTE는 본문이 SELECT여도 내부 DELETE/UPDATE/INSERT가 실제로 실행된다.
  // readOnly 검증이 우회되지 않도록 CTE 내부 DML 키워드를 감지하여 DML 타입을 반환해야 한다.

  @Test
  void detectQueryType_cteWithInnerDelete_returnsDelete() {
    String sql = "WITH d AS (DELETE FROM t WHERE 1=0 RETURNING *) SELECT * FROM d";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("DELETE");
  }

  @Test
  void detectQueryType_cteWithInnerUpdate_returnsUpdate() {
    String sql = "WITH u AS (UPDATE t SET c=c WHERE 1=0 RETURNING id) SELECT * FROM u";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("UPDATE");
  }

  @Test
  void detectQueryType_cteWithInnerInsert_returnsInsert() {
    String sql = "WITH i AS (INSERT INTO t(c) VALUES('x') RETURNING id) SELECT * FROM i";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("INSERT");
  }

  @Test
  void detectQueryType_nestedCteWithDelete_returnsDelete() {
    // 중첩 CTE — 두 번째 CTE가 data-modifying
    String sql = "WITH x AS (SELECT 1), d AS (DELETE FROM t WHERE 1=0 RETURNING *) SELECT * FROM d";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("DELETE");
  }

  @Test
  void detectQueryType_cteWithSelectOnly_returnsSelect() {
    // 정상 케이스 — 내부도 SELECT만, 본문도 SELECT → 통과해야 함
    String sql = "WITH x AS (SELECT * FROM customers) SELECT * FROM x";
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo("SELECT");
  }

  // ─── stripAndValidate: 거부 메시지 ─────────────────────────────────────────
  // 허용 문장 목록은 어떤 메시지에도 싣지 않는다 — 읽기 전용 애널리틱스와 DML 을 허용하는 데이터셋 SQL 탭이 이 검사를 공유한다.

  private static final String TYPO = "SQL 문법 오류: '%s' 은(는) SQL 명령어가 아닙니다. 철자를 확인하세요.";

  private static final String SPECIAL_SPACE =
      "'%s' 뒤에 보이지 않는 특수 공백(NBSP 등)이 있습니다. 일반 공백으로 바꿔 다시 실행하세요.";

  static Stream<Arguments> rejectedSqlMessages() {
    return Stream.of(
        Arguments.of(
            "SELECT 1; SELECT 2", "SQL 문장은 한 번에 하나만 실행할 수 있습니다. 세미콜론(;)으로 나뉜 문장을 하나씩 실행하세요."),
        Arguments.of("", "실행할 SQL 이 없습니다."),
        Arguments.of("   ", "실행할 SQL 이 없습니다."),
        Arguments.of("-- 주석만\n", "실행할 SQL 이 없습니다."),
        Arguments.of("/* 블록 주석만 */", "실행할 SQL 이 없습니다."),
        Arguments.of(";", "실행할 SQL 이 없습니다."),
        // 오타는 원문 대소문자 그대로 토큰을 보여준다.
        Arguments.of("SELEC region FROM sales", TYPO.formatted("SELEC")),
        Arguments.of("selcet * from t", TYPO.formatted("selcet")),
        // 토큰은 판정과 같은 어휘 규칙으로 자른다 — 구두점 앞까지가 PostgreSQL 이 보는 첫 토큰이다.
        Arguments.of("SELEC*FROM t", TYPO.formatted("SELEC")),
        Arguments.of("선택 a FROM t", TYPO.formatted("선택")),
        // 알려진 명령어(허용 안 됨)는 오타와 다른 메시지다. 구두점이 붙어도 선행 영문자열로 판별한다.
        Arguments.of("drop table data.t", "DROP 문은 실행할 수 없습니다."),
        Arguments.of("TRUNCATE data.t", "TRUNCATE 문은 실행할 수 없습니다."),
        Arguments.of("EXPLAIN SELECT 1", "EXPLAIN 문은 실행할 수 없습니다."),
        Arguments.of("vacuum(full) t", "VACUUM 문은 실행할 수 없습니다."),
        // WD-91: 허용 키워드 뒤에 식별자 글자($·_·숫자·0x80 이상)가 이어지면 PostgreSQL 에선 식별자 하나다 — 키워드가 아니라 오타로 안내한다.
        Arguments.of("SELECT$x FROM t", TYPO.formatted("SELECT$x")),
        Arguments.of("SELECT_1 FROM t", TYPO.formatted("SELECT_1")),
        Arguments.of("SELECT1", TYPO.formatted("SELECT1")),
        Arguments.of("SELECT$$x$$", TYPO.formatted("SELECT$$x$$")),
        // WD-91 리뷰: 허용 키워드 뒤 눈에 안 보이는 특수 공백(NBSP·전각 공백·ZWSP)은 원인을 짚어 안내한다.
        Arguments.of("select\u00a0* from t", SPECIAL_SPACE.formatted("select")),
        Arguments.of("SELECT\u3000* FROM t", SPECIAL_SPACE.formatted("SELECT")),
        Arguments.of("WITH\u200Bx AS (SELECT 1) SELECT 1", SPECIAL_SPACE.formatted("WITH")),
        // 비ASCII 철자는 PostgreSQL 키워드가 아니다(ſ U+017F, ı U+0131) — toUpperCase 로 접혀 통과하면 안 된다.
        Arguments.of("ſelect * from t", TYPO.formatted("ſelect")),
        Arguments.of("ınsert into t values (1)", TYPO.formatted("ınsert")),
        // 금지 명령어 판정도 같은 토큰으로 한다 — DROP$x 는 식별자라 DROP 문이 아니다.
        Arguments.of("DROP$x", TYPO.formatted("DROP$x")),
        // (SELECT 1) 은 예전에도 거부됐다. PG 에선 맞는 문장이라 "문법 오류"라 하지 않는다.
        Arguments.of("(SELECT 1)", "괄호로 시작하는 문장은 실행할 수 없습니다. SELECT 나 WITH 로 시작하게 바꿔 주세요."),
        Arguments.of("1 + 1", "SQL 문장은 SELECT·WITH 같은 SQL 명령어로 시작해야 합니다."));
  }

  @ParameterizedTest
  @MethodSource("rejectedSqlMessages")
  void stripAndValidate_rejected_returnsCauseSpecificMessage(String sql, String message) {
    assertThatThrownBy(() -> SqlValidationUtils.stripAndValidate(sql))
        .isInstanceOf(SqlQueryException.class)
        .hasMessage(message);
  }

  /** 회귀 가드 — 허용 목록·끝 세미콜론 처리는 그대로다(대소문자 무관). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 1",
        "SELECT 1;",
        "with x as (select 1) select * from x",
        "INSERT INTO t VALUES (1)",
        "update t set a = 1",
        "DELETE FROM t",
        // WD-91: 첫 키워드 바로 뒤의 구두점(공백 없음)은 PostgreSQL 이 받는다 — 키워드 경계는 식별자 어휘 규칙으로 본다.
        "SELECT*FROM t",
        "select(a) from t",
        "SELECT\"a\" FROM t",
        "SELECT'x'",
        "SELECT-1",
        "WITH\"d\" AS (SELECT 1) SELECT*FROM\"d\"",
        "UPDATE\"t\" SET a = 1",
        "DELETE*FROM t"
      })
  void stripAndValidate_allowedKeywords_stillAccepted(String sql) {
    assertThat(SqlValidationUtils.stripAndValidate(sql)).isEqualTo(sql);
  }

  /**
   * WD-91: 주석은 첫 키워드 판정 전에 공백으로 바뀐다 — {@code SELECT/**}{@code /1} 은 {@code SELECT 1} 로 받는다(기존 동작
   * 유지).
   */
  @Test
  void stripAndValidate_commentRightAfterKeyword_isAccepted() {
    assertThat(SqlValidationUtils.stripAndValidate("SELECT/**/1")).isEqualTo("SELECT 1");
    assertThat(SqlValidationUtils.stripAndValidate("SELECT--x\n1")).isEqualTo("SELECT \n1");
  }

  // ─── detectQueryType: 공용 픽스처 (WD-91) ─────────────────────────────────
  // executor 의 tests/test_query_type_vectors.py 가 같은 파일로 Python _detect_query_type 을 검증한다 —
  // 운영(executor
  // 경로)과 api 의 분류가 갈리면 읽기 전용 판정이 경로마다 달라진다. 구두점이 붙은 첫 키워드, 식별자 글자가 이어진 토큰, CTE 이름·따옴표
  // 식별자 속 SELECT 뒤의 DML 본문(보안, WD-91 리뷰) 등은 모두 이 픽스처에 있다.

  private static final JsonNode QUERY_TYPE_FIXTURE = loadQueryTypeFixture();

  private static JsonNode loadQueryTypeFixture() {
    try (InputStream in =
        SqlValidationUtilsTest.class.getResourceAsStream("/fixtures/query-type-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("문 타입 분류 시험 벡터 픽스처를 읽지 못했다", e);
    }
  }

  static Stream<Arguments> queryTypeVectors() {
    List<Arguments> rows = new ArrayList<>();
    QUERY_TYPE_FIXTURE
        .get("classify")
        .forEach(v -> rows.add(Arguments.of(v.get("sql").asText(), v.get("type").asText())));
    return rows.stream();
  }

  @Test
  void queryTypeFixtureHasVectors() {
    // 픽스처가 비면 아래 파라미터 테스트가 공허하게 통과한다
    assertThat(QUERY_TYPE_FIXTURE.get("classify").size()).isGreaterThanOrEqualTo(50);
  }

  @ParameterizedTest
  @MethodSource("queryTypeVectors")
  void detectQueryTypeMatchesFixture(String sql, String expected) {
    assertThat(SqlValidationUtils.detectQueryType(sql)).isEqualTo(expected);
  }

  // ─── cteInnerDmlKeyword (파싱 실패 메시지 전용) ─────────────────────────────

  @ParameterizedTest
  @ValueSource(
      strings = {
        "with d as (delete from sales returning *) select * from d",
        // 앞 주석이 있어도 WITH 문으로 본다.
        "/* note */ WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d",
        // WD-91: WITH 바로 뒤에 구두점이 붙어도 WITH 문이다(예전 공백 분리는 null 을 돌려 원인 안내를 놓쳤다).
        "WITH\"d\"AS(DELETE FROM t RETURNING *)SELECT*FROM\"d\""
      })
  void cteInnerDmlKeyword_detectsDmlInsideCte(String sql) {
    assertThat(SqlValidationUtils.cteInnerDmlKeyword(sql)).isEqualTo("DELETE");
  }

  /** 주석·리터럴·큰따옴표 식별자·달러 인용 속 DML 단어, WITH 가 아닌 문장은 CTE 안 DML 이 아니다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "WITH a AS ( -- delete old\n SELECT id FROM t) SELEC * FROM a",
        "WITH a AS (SELECT \"update\" FROM t) SELECT 1",
        "WITH a AS (SELECT $$delete$$, $q$insert$q$ /* merge */ FROM t) SELECT 1",
        "WITH x AS (SELECT 'delete me' AS t) SELECT * FROM x",
        "SELECT * FROM (DELETE FROM t) x"
      })
  void cteInnerDmlKeyword_ignoresMaskedWordsAndNonWith(String sql) {
    assertThat(SqlValidationUtils.cteInnerDmlKeyword(sql)).isNull();
  }

  @Test
  void detectQueryType_quotedIdentifierWord_isNotDml() {
    // WD-91 리뷰: 판정도 SqlLexicalMask 로 큰따옴표 식별자를 가린다 — "update" 는 PostgreSQL 에서 식별자라 DML 이 아니다.
    // 예전엔 작은따옴표 리터럴만 지워, 따옴표 식별자 "select" 를 CTE 이름으로 쓴 DML 본문을 SELECT 로 오인할 여지가 있었다.
    assertThat(
            SqlValidationUtils.detectQueryType(
                "WITH a AS (SELECT \"update\" FROM t) SELECT * FROM a"))
        .isEqualTo("SELECT");
  }

  /**
   * WD-91 리뷰: 대문자화는 로캘과 무관해야 한다. 터키어 로캘의 {@link String#toUpperCase()} 는 {@code with}·{@code insert}
   * 의 {@code i} 를 {@code İ} 로 바꿔 정상 문장을 거부하거나 분류를 틀린다.
   */
  @Test
  void firstKeyword_isLocaleIndependent_underTurkishLocale() {
    Locale saved = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(SqlValidationUtils.stripAndValidate("with x as (select 1) select * from x"))
          .isEqualTo("with x as (select 1) select * from x");
      assertThat(SqlValidationUtils.detectQueryType("with x as (select 1) select * from x"))
          .isEqualTo("SELECT");
      assertThat(SqlValidationUtils.detectQueryType("insert into t values (1)"))
          .isEqualTo("INSERT");
      assertThat(
              SqlValidationUtils.detectQueryType("with d as (insert into t values (1)) select 1"))
          .isEqualTo("INSERT");
    } finally {
      Locale.setDefault(saved);
    }
  }

  @Test
  void displayToken_collapsesWhitespaceAndTruncates() {
    assertThat(SqlValidationUtils.displayToken("'a\n  b'")).isEqualTo("'a b'");
    assertThat(SqlValidationUtils.displayToken("y".repeat(31))).isEqualTo("y".repeat(30) + "…");
    assertThat(SqlValidationUtils.displayToken("y".repeat(30))).isEqualTo("y".repeat(30));
  }
}
