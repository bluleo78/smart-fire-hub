package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SqlLexicalMask} 가 공용 픽스처({@code fixtures/sql-lexical-vectors.json})와 같은 결과를 내는지 본다(#746).
 *
 * <p>executor 의 {@code tests/test_sql_lexical_vectors.py} 가 같은 파일로 Python {@code _mask_sql}/{@code
 * _normalize_sql} 을 검증한다 — 두 구현의 토큰 규칙이 갈리면 어느 한쪽 테스트가 깨진다. DB·스프링이 필요 없는 순수 단위 테스트다. 실제 PostgreSQL
 * 이 정규화된 SQL 을 받아들이는지는 {@code PipelineIncrementalIntegrationTest} 가 러너 관통으로 본다.
 */
class SqlLexicalMaskTest {

  private static final JsonNode FIXTURE = loadFixture();

  private static JsonNode loadFixture() {
    try (InputStream in =
        SqlLexicalMaskTest.class.getResourceAsStream("/fixtures/sql-lexical-vectors.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("SQL 어휘 시험 벡터 픽스처를 읽지 못했다", e);
    }
  }

  static Stream<Arguments> maskVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("mask")
        .forEach(v -> rows.add(Arguments.of(v.get("sql").asText(), v.get("mask").asText())));
    return rows.stream();
  }

  static Stream<Arguments> stripVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("stripTrailing")
        .forEach(v -> rows.add(Arguments.of(v.get("sql").asText(), v.get("expected").asText())));
    return rows.stream();
  }

  static Stream<Arguments> rowLimitVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("rowLimit")
        .forEach(
            v ->
                rows.add(
                    Arguments.of(
                        v.get("sql").asText(),
                        v.get("hasLimit").asBoolean(),
                        v.get("applied").asText())));
    return rows.stream();
  }

  /** WD-91: 맨 앞 키워드 후보 토큰. null 벡터는 NullNode.asText() 가 문자열 "null" 이라 직접 null 로 바꾼다. */
  static Stream<Arguments> leadingVectors() {
    List<Arguments> rows = new ArrayList<>();
    FIXTURE
        .get("leading")
        .forEach(
            v -> {
              JsonNode kw = v.get("keyword");
              rows.add(Arguments.of(v.get("sql").asText(), kw.isNull() ? null : kw.asText()));
            });
    return rows.stream();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("leadingVectors")
  void 맨_앞_키워드_토큰이_픽스처와_같다(String sql, String expected) {
    assertThat(SqlLexicalMask.leadingKeyword(sql)).isEqualTo(expected);
  }

  /** 픽스처의 applied 는 maxRows=10 기준이다(픽스처 _설명 참조). */
  private static final int ROW_LIMIT_MAX_ROWS = 10;

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("maskVectors")
  void 마스크가_픽스처와_같다(String sql, String expected) {
    assertThat(SqlLexicalMask.mask(sql)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("stripVectors")
  void 끝_주석_세미콜론_제거가_픽스처와_같다(String sql, String expected) {
    assertThat(SqlLexicalMask.stripTrailingCommentsAndSemicolons(sql)).isEqualTo(expected);
  }

  /** 마스크 위치로 원문을 자르는 소비처가 있으므로 길이(UTF-16 단위)가 원문과 같아야 한다 — BMP 밖 문자 포함. */
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("stripVectors")
  void 마스크는_원문과_길이가_같다(String sql, String ignored) {
    assertThat(SqlLexicalMask.mask(sql)).hasSameSizeAs(sql);
  }

  /** 최상위 LIMIT n / FETCH FIRST 만 사용자 행 제한으로 본다(#749) — Python 과 같은 판정. */
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("rowLimitVectors")
  void 최상위_행_제한_판정이_픽스처와_같다(String sql, boolean hasLimit, String ignored) {
    assertThat(SqlLexicalMask.hasTopLevelRowLimit(sql)).isEqualTo(hasLimit);
  }

  /** 사용자 제한은 존중, LIMIT ALL/NULL 은 maxRows 로 치환, 없으면 덧붙인다(#749). */
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("rowLimitVectors")
  void 행_제한_적용이_픽스처와_같다(String sql, boolean ignored, String applied) {
    assertThat(SqlLexicalMask.applyRowLimit(sql, ROW_LIMIT_MAX_ROWS)).isEqualTo(applied);
  }

  /** 픽스처가 비어 있으면 위 테스트는 공허하게 통과한다 — 벡터가 실제로 읽혔는지 확인한다. */
  @Test
  void 픽스처에_벡터가_있다() {
    assertThat(FIXTURE.get("mask").size()).isGreaterThanOrEqualTo(10);
    assertThat(FIXTURE.get("stripTrailing").size()).isGreaterThanOrEqualTo(20);
    assertThat(FIXTURE.get("rowLimit").size()).isGreaterThanOrEqualTo(20);
    assertThat(FIXTURE.get("leading").size()).isGreaterThanOrEqualTo(30);
  }

  // ---------------------------------------------------------------------------------------------
  // #753 — 위치 파라미터($n) 탐지. Java 전용 규칙이라 공용 픽스처에는 넣지 않는다(executor 는 서버가
  // "there is no parameter $1" 로 답하므로 짝 함수가 없다).
  // ---------------------------------------------------------------------------------------------

  static Stream<Arguments> positionalParameterVectors() {
    return Stream.of(
        // 코드 속 $n 은 파라미터다.
        Arguments.of("SELECT * FROM t WHERE a = $1", 26),
        Arguments.of("SELECT $12", 7),
        Arguments.of("SELECT \"x\"$1", 10),
        Arguments.of("SELECT 1$2", 8),
        Arguments.of("SELECT $$a$$$1", 12),
        Arguments.of("SELECT '$1', $2", 13),
        // 리터럴·주석·따옴표 식별자·달러 인용·식별자 속 $n 은 아니다.
        Arguments.of("SELECT '$1'", -1),
        Arguments.of("SELECT E'\\'$1'", -1),
        Arguments.of("SELECT \"$1\"", -1),
        Arguments.of("SELECT 1 -- $1", -1),
        Arguments.of("SELECT 1 /* /* $1 */ $2 */", -1),
        Arguments.of("SELECT $$ $1 $$", -1),
        Arguments.of("SELECT $tag$ $1 $tag$", -1),
        Arguments.of("SELECT a$1 FROM t", -1),
        Arguments.of("SELECT '{\"a\":1}'::jsonb ? 'a'", -1),
        Arguments.of("SELECT $ 1", -1));
  }

  @ParameterizedTest
  @MethodSource("positionalParameterVectors")
  void 위치_파라미터를_리터럴_주석_밖에서만_찾는다(String sql, int expected) {
    assertThat(SqlLexicalMask.findPositionalParameter(sql)).isEqualTo(expected);
  }

  @Test
  void 위치_파라미터_탐지는_마스크_결과를_바꾸지_않는다() {
    String sql = "SELECT '$1', $2 -- $3";
    SqlLexicalMask.findPositionalParameter(sql);
    assertThat(SqlLexicalMask.mask(sql)).isEqualTo("SELECT 'xx', $2" + " ".repeat(6));
  }

  // ---------------------------------------------------------------------------------------------
  // WD-563 — 실행 전 검증(SqlValidationUtils.stripAndValidate)용 주석 제거·닫히지 않은 어휘 탐지.
  // ---------------------------------------------------------------------------------------------

  static Stream<Arguments> replaceCommentsVectors() {
    return Stream.of(
        // 주석 하나는 공백 하나로 바뀐다(토큰이 붙어 -/**/- 가 -- 주석이 되지 않게).
        Arguments.of("SELECT/**/1", "SELECT 1"),
        Arguments.of("SELECT--x\n1", "SELECT \n1"),
        Arguments.of("SELECT 1 -/**/- 2", "SELECT 1 - - 2"),
        Arguments.of("SELECT 1 /* /* */ x */ + 2", "SELECT 1   + 2"),
        Arguments.of("SELECT 1 --c\r\n+ 2", "SELECT 1  \r\n+ 2"),
        // 리터럴·따옴표 식별자·달러 인용 속 주석 표기는 그대로 둔다.
        Arguments.of("SELECT 'x /* y */ z', '--', \"a--b\", $$/*$$", null),
        Arguments.of("SELECT E'\\' -- '", null));
  }

  @ParameterizedTest
  @MethodSource("replaceCommentsVectors")
  void 리터럴_밖의_주석만_공백_하나로_바꾼다(String sql, String expected) {
    assertThat(SqlLexicalMask.replaceComments(sql)).isEqualTo(expected == null ? sql : expected);
  }

  static Stream<Arguments> unclosedVectors() {
    return Stream.of(
        Arguments.of("SELECT 'a''b'", null),
        Arguments.of("SELECT E'\\''", null),
        Arguments.of("SELECT $a$ $b$ $a$", null),
        Arguments.of("SELECT 1 /* /* */ */", null),
        Arguments.of("SELECT 1 -- 'x", null),
        Arguments.of("SELECT a$$", null),
        Arguments.of("SELECT 'abc", SqlLexicalMask.Unclosed.STRING),
        Arguments.of("SELECT E'abc\\'", SqlLexicalMask.Unclosed.STRING),
        Arguments.of("SELECT E'abc\\", SqlLexicalMask.Unclosed.STRING),
        Arguments.of("SELECT \"a", SqlLexicalMask.Unclosed.QUOTED_IDENTIFIER),
        Arguments.of("SELECT $a$ x $b$", SqlLexicalMask.Unclosed.DOLLAR_QUOTE),
        Arguments.of("SELECT 1 /* x", SqlLexicalMask.Unclosed.BLOCK_COMMENT),
        Arguments.of("SELECT 1 /* /* */", SqlLexicalMask.Unclosed.BLOCK_COMMENT));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 1$$x$$",
        "SELECT $$a$$$$b$$",
        "SELECT 1E'x'",
        "SELECT $$a$$e'x'",
        // 코드 리뷰 1번: E 앞 글자가 pgjdbc 식별자 종결 문자가 아닌 경우(' \ { } VT), 위치 0 의 E
        "SELECT 'a'E'x'",
        "SELECT \\E'x'",
        "SELECT {E'x'",
        "SELECT }E'x'",
        "SELECT\u000BE'x'",
        "E'x'",
        // E 문자열 속 '' 와 \ 가 함께 있으면 pgjdbc 가 닫는 위치가 다르다.
        "SELECT E'a''\\b'",
        // $ 앞 제어 문자, 제어 문자가 든 태그, 비ASCII 뒤 덩어리 속 $$, 비ASCII 태그
        "SELECT \u0001$$x$$",
        "SELECT $a\u0001$x$a\u0001$",
        "SELECT  $$x$$",
        "SELECT $한$x$한$"
      })
  void pgjdbc_와_리터럴_경계가_갈리는_표기를_찾는다(String sql) {
    assertThat(SqlLexicalMask.hasDriverAmbiguousQuote(sql)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 1 $$x$$",
        "SELECT $$a$$ $$b$$",
        "SELECT E'x', 1 E'y'",
        "SELECT a$$x$$",
        "SELECT \"a\"$$x$$",
        "SELECT '1$$', $1",
        "SELECT x$1E'z'",
        "SELECT \"a\"E'x'",
        "SELECT (E'\\n'), x||e'y'",
        "SELECT E'it''s'",
        "SELECT (\n$$x$$)",
        "SELECT 한$x FROM t"
      })
  void 공백이나_식별자_뒤의_인용은_드라이버와_같다(String sql) {
    assertThat(SqlLexicalMask.hasDriverAmbiguousQuote(sql)).isFalse();
  }

  @ParameterizedTest
  @MethodSource("unclosedVectors")
  void 닫히지_않은_리터럴_주석을_찾는다(String sql, SqlLexicalMask.Unclosed expected) {
    assertThat(SqlLexicalMask.findUnclosed(sql)).isEqualTo(expected);
  }
}
