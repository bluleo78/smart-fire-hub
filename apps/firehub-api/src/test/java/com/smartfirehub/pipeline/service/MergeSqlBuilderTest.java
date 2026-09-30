package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link MergeSqlBuilder}의 순수 함수 계약을 검증한다 — DB 접속 없이도 빠르게 회귀를 잡는다.
 * 실제 DB 대상 upsert 의미(갱신/삽입/무동작)는 {@code MergeSqlBuilderIntegrationTest}가 별도로 증명한다.
 */
class MergeSqlBuilderTest {

  @Test
  void PK기준_upsert와_변경없는행_갱신방지_조건을_만든다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"", List.of("code", "name", "cnt"), List.of("code"), "SELECT code, name, cnt FROM src");
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"data\".\"out\" AS t (\"code\", \"name\", \"cnt\") "
                + "SELECT \"code\", \"name\", \"cnt\" FROM (\nSELECT code, name, cnt FROM src\n) AS _src "
                + "ON CONFLICT (\"code\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"cnt\" = EXCLUDED.\"cnt\" "
                + "WHERE (t.\"name\", t.\"cnt\") IS DISTINCT FROM (EXCLUDED.\"name\", EXCLUDED.\"cnt\")");
  }

  @Test
  void 모든_컬럼이_키이면_DO_NOTHING() {
    String sql = MergeSqlBuilder.build("\"data\".\"out\"", List.of("code"), List.of("code"), "SELECT code FROM src");
    assertThat(sql).endsWith("ON CONFLICT (\"code\") DO NOTHING");
  }

  @Test
  void 키가_INSERT_컬럼에_없으면_거부한다() {
    assertThatThrownBy(
            () -> MergeSqlBuilder.build("\"data\".\"out\"", List.of("name"), List.of("code"), "SELECT name FROM src"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("code");
  }

  /**
   * Fix round 1, blocking — SqlValidator 는 후행 세미콜론을 허용하는 정책이라(SqlValidatorTest.
   * allows_trailing_semicolon 로 고정됨), 저장 시점 검증을 통과한 사용자 SELECT 가 세미콜론을 달고
   * 그대로 이 빌더까지 들어올 수 있다. 세미콜론을 서브쿼리 괄호 안에 그대로 두면
   * "FROM (SELECT ... FROM x;) AS _src" 가 되어 PostgreSQL 문법 오류가 난다 — 여기서 제거해야 한다.
   */
  @Test
  void 후행_세미콜론은_서브쿼리_안에_들어가지_않는다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"", List.of("code"), List.of("code"), "SELECT code FROM src;");
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"data\".\"out\" AS t (\"code\") SELECT \"code\" FROM (\nSELECT code FROM src\n) AS _src ON CONFLICT (\"code\") DO NOTHING");
  }

  /**
   * Fix round 2, must 1 — 세미콜론 없이 후행 한 줄 주석만 있는 경우("SELECT a FROM x -- note")는
   * 예전(서브쿼리가 한 줄이던) 방식에서는 "--"가 줄 끝까지(닫는 괄호·ON CONFLICT 까지) 통째로
   * 주석 처리해 문법 오류가 났다. 서브쿼리를 자기 줄에 얹으면 주석은 그 줄에서만 끝나고 닫는 괄호는
   * 다음 줄이라 살아남는다. #746 부터는 끝 주석 자체를 걷어내므로(SqlLexicalMask) 주석이 서브쿼리에 남지
   * 않고, 개행은 방어적 이중 안전장치로 남는다 — 이 문자열 형태 테스트는 "생성되는 SQL 모양"만 고정한다. 실제로
   * PostgreSQL 이 이 형태를 받아들이는지는 {@code MergeSqlBuilderIntegrationTest}가 실행으로 증명한다.
   */
  @Test
  void 후행_한줄_주석은_닫는_괄호를_삼키지_않는다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"", List.of("code"), List.of("code"), "SELECT code FROM src -- note");
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"data\".\"out\" AS t (\"code\") SELECT \"code\" FROM (\nSELECT code FROM src\n) AS _src ON CONFLICT (\"code\") DO NOTHING");
  }

  /**
   * Fix round 2, must 1 — 세미콜론과 후행 주석이 함께 있는 경우("SELECT a FROM x; -- note")는
   * {@code stripTrailing()} 만으로는 문자열 끝이 ";"가 아니라("--"뒤 텍스트) 세미콜론이 안 지워졌다.
   * 주석보다 앞의 세미콜론을 찾아 지워야 한다(#746 부터는 실행에 의미 없는 끝 주석도 함께 걷는다).
   */
  @Test
  void 세미콜론_뒤_후행_주석도_세미콜론이_제거된다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"", List.of("code"), List.of("code"), "SELECT code FROM src; -- note");
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"data\".\"out\" AS t (\"code\") SELECT \"code\" FROM (\nSELECT code FROM src\n) AS _src ON CONFLICT (\"code\") DO NOTHING");
  }

  /**
   * 코드리뷰 MEDIUM — 주석이 세미콜론과 <b>다른 줄</b>에 있는 형태("SELECT ... ;\n-- note").
   * SqlValidator 가 받아들이는 모양인데, 예전 구현은 "물리적 마지막 줄"만 봐서 그 줄이 통째로 주석이면
   * 세미콜론을 못 찾고 그대로 서브쿼리 안에 넣어 매 실행 문법 오류를 냈다. 마지막 <b>코드</b> 줄의
   * 세미콜론을 지워야 한다.
   */
  @Test
  void 세미콜론_다음_줄에_주석이_있어도_세미콜론이_제거된다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"", List.of("code"), List.of("code"), "SELECT code FROM src;\n-- note");
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"data\".\"out\" AS t (\"code\") SELECT \"code\" FROM (\nSELECT code FROM src\n) AS _src ON CONFLICT (\"code\") DO NOTHING");
  }

  /** 주석이 여러 줄이어도(코드 줄이 더 앞) 마지막 코드 줄의 세미콜론을 찾아야 한다. */
  @Test
  void 주석_여러_줄_뒤에_있어도_세미콜론이_제거된다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"",
            List.of("code"),
            List.of("code"),
            "SELECT code\nFROM src;\n-- note1\n-- note2\n");
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"data\".\"out\" AS t (\"code\") SELECT \"code\" FROM (\nSELECT code\nFROM src\n) AS _src ON CONFLICT (\"code\") DO NOTHING");
  }

  /**
   * 기존 보장 유지 — 문자열 리터럴 안의 세미콜론은 문장 끝이 아니므로 절대 지우면 안 된다.
   * (전체를 앞에서부터 훑으며 따옴표 상태를 추적하기 때문에 성립한다.)
   */
  @Test
  void 문자열_리터럴_안의_세미콜론은_보존된다() {
    String sql =
        MergeSqlBuilder.build(
            "\"data\".\"out\"", List.of("code"), List.of("code"), "SELECT ';' AS code FROM src");
    assertThat(sql).contains("SELECT ';' AS code FROM src");
  }

  /**
   * 기존 보장 유지 — 달러 인용({@code $$...$$}) 본문의 세미콜론도 문장 끝이 아니다.
   */
  @Test
  void 달러_인용_본문_안의_세미콜론은_보존된다() {
    String selectSql = "SELECT $$a;$$ AS code FROM src";
    String sql =
        MergeSqlBuilder.build("\"data\".\"out\"", List.of("code"), List.of("code"), selectSql);
    assertThat(sql).contains(selectSql);
  }

  /**
   * 기존 보장 유지(회귀 방지) — 리터럴이 <b>여러 줄</b>에 걸치고 그 안에 세미콜론과 {@code --} 가 있는
   * 형태. 줄 단위로 뒤에서 앞으로 훑는 구현이면 마지막 줄이 주석처럼 보여 리터럴 안의 세미콜론을
   * 지우게 된다 — 그래서 앞에서부터 상태를 이어 가며 훑어야 한다.
   */
  @Test
  void 여러_줄_문자열_리터럴_안의_세미콜론은_보존된다() {
    String selectSql = "SELECT 'x;\n-- y' AS code FROM src";
    String sql =
        MergeSqlBuilder.build("\"data\".\"out\"", List.of("code"), List.of("code"), selectSql);
    assertThat(sql).contains(selectSql);
  }

  /**
   * #746 — 세미콜론 뒤의 <b>블록 주석</b>. 예전 헬퍼는 블록 주석을 "세미콜론 뒤 코드"로 봐서 세미콜론을
   * 남겼고, 서브쿼리 괄호 안의 {@code ;} 가 구문 오류가 됐다.
   */
  @Test
  void 세미콜론_뒤_블록_주석이어도_세미콜론이_제거된다() {
    assertThat(buildSubquery("SELECT code FROM src; /* note */")).isEqualTo("SELECT code FROM src");
    assertThat(buildSubquery("SELECT code FROM src /* ; */ ;")).isEqualTo("SELECT code FROM src");
    assertThat(buildSubquery("SELECT code FROM src; /* a /* 중첩 */ b */ -- z\n"))
        .isEqualTo("SELECT code FROM src");
  }

  /**
   * #746 — 달러 인용 안의 {@code --}·{@code '} 는 주석·따옴표가 아니다. 예전 헬퍼는 {@code $$--y;$$} 의
   * {@code --} 를 주석 시작으로 봐 진짜 {@code ;} 까지 건너뛰었고, {@code $$it's$$} 의 {@code '} 로 따옴표
   * 상태에 갇혀 세미콜론을 지우지 못했다.
   */
  @Test
  void 달러_인용_안의_주석_기호나_따옴표가_있어도_끝_세미콜론이_제거된다() {
    assertThat(buildSubquery("SELECT code || $$--y;$$ AS code FROM src;"))
        .isEqualTo("SELECT code || $$--y;$$ AS code FROM src");
    assertThat(buildSubquery("SELECT code || $$it's$$ AS code FROM src;"))
        .isEqualTo("SELECT code || $$it's$$ AS code FROM src");
    assertThat(buildSubquery("SELECT code || $t$ $$ ; ' -- $t$ AS code FROM src;"))
        .isEqualTo("SELECT code || $t$ $$ ; ' -- $t$ AS code FROM src");
  }

  /** #746 — E 문자열의 백슬래시 이스케이프({@code \'})는 문자열을 닫지 않는다. */
  @Test
  void E_문자열_안의_이스케이프된_따옴표가_있어도_끝_세미콜론이_제거된다() {
    assertThat(buildSubquery("SELECT E'\\'--;' AS code FROM src;"))
        .isEqualTo("SELECT E'\\'--;' AS code FROM src");
  }

  /** build() 결과에서 서브쿼리 괄호 안의 사용자 SELECT 부분만 떼어 낸다. */
  private static String buildSubquery(String selectSql) {
    String sql = MergeSqlBuilder.build("\"data\".\"out\"", List.of("code"), List.of("code"), selectSql);
    int start = sql.indexOf("FROM (\n") + "FROM (\n".length();
    int end = sql.lastIndexOf("\n) AS _src ");
    return sql.substring(start, end);
  }
}
