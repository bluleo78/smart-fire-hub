package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 파이프라인 적재 문장 생성기는 순수 함수이므로 생성 SQL 을 정확히 비교한다. */
class TiberoPipelineSqlTest {

  private static final String S = "GENIA_T5";
  private static final String T = "\"GENIA_T5\".\"ORDERS\"";
  private static final String SEQ =
      "\"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "orders") + "\"";
  private static final Pattern DELETE_PRE =
      Pattern.compile("^DELETE FROM \"([A-Z0-9_]+)\"\\.\"([A-Z0-9_$#]+)\"$");

  @Test
  void deleteAll_exactAndMatchesExecutorRegex() {
    String sql = TiberoPipelineSql.deleteAll(S, "orders");
    assertThat(sql).isEqualTo("DELETE FROM " + T);
    assertThat(DELETE_PRE.matcher(sql).matches()).isTrue();
  }

  @Test
  void insertSelect_exactSql() {
    assertThat(
            TiberoPipelineSql.insertSelect(
                S, "orders", List.of("amount", "name"), "SELECT 1 amount, 'a' name FROM DUAL"))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"AMOUNT\", \"NAME\", \"_UPDATED_AT\") SELECT "
                + SEQ
                + ".NEXTVAL, src.\"AMOUNT\", src.\"NAME\", SYSTIMESTAMP FROM (\n"
                + "SELECT 1 amount, 'a' name FROM DUAL\n) src");
  }

  @Test
  void insertSelect_userSqlEndingWithLineComment_keepsClosingParen() {
    String sql =
        TiberoPipelineSql.insertSelect(S, "orders", List.of("a"), "SELECT 1 a FROM DUAL -- c");
    assertThat(sql).contains("-- c\n) src");
  }

  @Test
  void insertSelect_trailingSemicolonStripped() {
    String sql = TiberoPipelineSql.insertSelect(S, "orders", List.of("a"), "SELECT 1 a FROM DUAL;");
    assertThat(sql).contains("FROM DUAL\n) src").doesNotContain(";");
  }

  @Test
  void mergeSelect_exactSql() {
    assertThat(
            TiberoPipelineSql.mergeSelect(
                S,
                "orders",
                List.of("id_key", "amount", "name"),
                List.of("id_key"),
                "SELECT 1 id_key, 2 amount, 'a' name FROM DUAL;"))
        .isEqualTo(
            "MERGE INTO "
                + T
                + " t USING (\nSELECT 1 id_key, 2 amount, 'a' name FROM DUAL\n) s "
                + "ON (t.\"ID_KEY\" = s.\"ID_KEY\") "
                + "WHEN MATCHED THEN UPDATE SET t.\"AMOUNT\" = s.\"AMOUNT\", "
                + "t.\"NAME\" = s.\"NAME\", t.\"_UPDATED_AT\" = SYSTIMESTAMP "
                + "WHERE (DECODE(t.\"AMOUNT\", s.\"AMOUNT\", 0, 1) = 1 "
                + "OR DECODE(t.\"NAME\", s.\"NAME\", 0, 1) = 1) "
                + "WHEN NOT MATCHED THEN INSERT (\"ID\", \"ID_KEY\", \"AMOUNT\", \"NAME\", "
                + "\"_UPDATED_AT\") VALUES ("
                + SEQ
                + ".NEXTVAL, s.\"ID_KEY\", s.\"AMOUNT\", s.\"NAME\", SYSTIMESTAMP)");
  }

  @Test
  void mergeSelect_allColumnsArePk_omitsWhenMatched() {
    String sql =
        TiberoPipelineSql.mergeSelect(
            S, "orders", List.of("a", "b"), List.of("a", "b"), "SELECT 1 a, 2 b FROM DUAL");
    assertThat(sql)
        .doesNotContain("WHEN MATCHED")
        .contains(") s ON (t.\"A\" = s.\"A\" AND t.\"B\" = s.\"B\") WHEN NOT MATCHED THEN INSERT");
  }

  @Test
  void mergeSelect_pkNotInColumns_rejected() {
    assertThatThrownBy(
            () ->
                TiberoPipelineSql.mergeSelect(
                    S, "orders", List.of("a"), List.of("zz"), "SELECT 1 a FROM DUAL"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mergeCore_existingCallers_unchanged() {
    // 기존 merge() 는 바뀐 행만 조건 없이 항상 UPDATE 하고, 키만 있어도 WHEN MATCHED 를 유지한다.
    assertThat(TiberoDmlSql.merge(S, "orders", List.of("code", "name"), List.of("code"), false))
        .isEqualTo(
            "MERGE INTO "
                + T
                + " t USING (SELECT ? \"CODE\", ? \"NAME\" FROM DUAL) s ON (t.\"CODE\" = s.\"CODE\") "
                + "WHEN MATCHED THEN UPDATE SET t.\"NAME\" = s.\"NAME\", "
                + "t.\"_UPDATED_AT\" = SYSTIMESTAMP "
                + "WHEN NOT MATCHED THEN INSERT (\"ID\", \"CODE\", \"NAME\", \"_UPDATED_AT\") "
                + "VALUES ("
                + SEQ
                + ".NEXTVAL, s.\"CODE\", s.\"NAME\", SYSTIMESTAMP)");
    assertThat(TiberoDmlSql.merge(S, "orders", List.of("code"), List.of("code"), false))
        .contains("WHEN MATCHED THEN UPDATE SET t.\"_UPDATED_AT\" = SYSTIMESTAMP WHEN NOT")
        .doesNotContain("DECODE");
  }

  @Test
  void generatedStatements_passExecutorGuard() {
    String ins = TiberoPipelineSql.insertSelect(S, "orders", List.of("a"), "SELECT 1 a FROM DUAL");
    String mrg =
        TiberoPipelineSql.mergeSelect(
            S, "orders", List.of("k", "a"), List.of("k"), "SELECT 1 k, 2 a FROM DUAL");
    for (String sql : List.of(ins, mrg)) {
      assertThatCode(
              () -> {
                TiberoSqlGuard.requireAllowedStatement(sql);
                TiberoSqlGuard.requireNoForeignSchemaCalls(sql, "GENIA_T5");
              })
          .doesNotThrowAnyException();
    }
  }
}
