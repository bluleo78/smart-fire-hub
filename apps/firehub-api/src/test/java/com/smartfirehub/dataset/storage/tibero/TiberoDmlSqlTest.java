package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.exception.InvalidTableNameException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DML SQL 빌더는 순수 함수이므로 생성되는 SQL 문자열을 정확히 비교한다. */
class TiberoDmlSqlTest {

  private static final String S = "GENIA_T5";
  private static final String T = "\"GENIA_T5\".\"ORDERS\"";
  private static final String SEQ =
      "\"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "orders") + "\"";

  @Test
  void select_withSearchSortPaging_exactSql() {
    String sql =
        TiberoDmlSql.select(
            S,
            "orders",
            List.of("name", "memo", "qty"),
            List.of("name", "memo", "qty"),
            "qty",
            "desc",
            Map.of());
    assertThat(sql)
        .isEqualTo(
            "SELECT \"ID\", \"NAME\", \"MEMO\", \"QTY\" FROM (SELECT q.*, ROW_NUMBER() OVER ("
                + "ORDER BY \"QTY\" DESC NULLS LAST, \"ID\") AS RN__ FROM "
                + T
                + " q WHERE (UPPER(TO_CHAR(\"NAME\")) LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(TO_CHAR(\"MEMO\")) LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(TO_CHAR(\"QTY\")) LIKE UPPER(?) ESCAPE '\\')) "
                + "WHERE RN__ > ? AND RN__ <= ? ORDER BY RN__");
  }

  @Test
  void select_defaultSortById() {
    String sql = TiberoDmlSql.select(S, "orders", List.of("name"), List.of(), null, null, Map.of());
    assertThat(sql)
        .isEqualTo(
            "SELECT \"ID\", \"NAME\" FROM (SELECT q.*, ROW_NUMBER() OVER (ORDER BY \"ID\") "
                + "AS RN__ FROM "
                + T
                + " q) WHERE RN__ > ? AND RN__ <= ? ORDER BY RN__");
  }

  @Test
  void select_sortByAnyValidName_andDirIsCaseInsensitive() {
    // PG 와 같이 이름 형식만 검증한다 — 없는 컬럼이면 DB 가 오류를 낸다.
    assertThat(
            TiberoDmlSql.select(S, "orders", List.of("name"), List.of(), "other", "desc", Map.of()))
        .contains("ORDER BY \"OTHER\" DESC NULLS LAST, \"ID\"");
    assertThatThrownBy(
            () ->
                TiberoDmlSql.select(
                    S, "orders", List.of("name"), List.of(), "a\"b", "asc", Map.of()))
        .isInstanceOf(InvalidTableNameException.class);
    assertThat(
            TiberoDmlSql.select(S, "orders", List.of("name"), List.of(), "name", "DESC", Map.of()))
        .contains("ORDER BY \"NAME\" DESC NULLS LAST, \"ID\"");
    assertThat(
            TiberoDmlSql.select(S, "orders", List.of("name"), List.of(), "name", "bogus", Map.of()))
        .contains("ORDER BY \"NAME\" ASC NULLS LAST, \"ID\"");
    assertThat(
            TiberoDmlSql.select(
                S, "orders", List.of("name"), List.of(), "created_at", "asc", Map.of()))
        .contains("ORDER BY \"CREATED_AT\" ASC NULLS LAST, \"ID\"");
  }

  @Test
  void select_noColumns_selectsStar() {
    assertThat(TiberoDmlSql.select(S, "orders", List.of(), List.of(), null, null, Map.of()))
        .startsWith("SELECT * FROM (SELECT q.*, ROW_NUMBER()");
  }

  @Test
  void select_rejectsInvalidIdentifier() {
    assertThatThrownBy(
            () ->
                TiberoDmlSql.select(S, "orders", List.of("a\"b"), List.of(), null, null, Map.of()))
        .isInstanceOf(InvalidTableNameException.class);
  }

  @Test
  void count_withSearch() {
    assertThat(TiberoDmlSql.count(S, "orders", List.of("name", "memo"), Map.of()))
        .isEqualTo(
            "SELECT COUNT(*) FROM "
                + T
                + " WHERE (UPPER(TO_CHAR(\"NAME\")) LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(TO_CHAR(\"MEMO\")) LIKE UPPER(?) ESCAPE '\\')");
    assertThat(TiberoDmlSql.count(S, "orders", List.of(), Map.of()))
        .isEqualTo("SELECT COUNT(*) FROM " + T);
  }

  @Test
  void searchPattern_escapesWildcards() {
    assertThat(TiberoDmlSql.searchPattern("50%")).isEqualTo("%50\\%%");
    assertThat(TiberoDmlSql.searchPattern("a_b")).isEqualTo("%a\\_b%");
    assertThat(TiberoDmlSql.searchPattern("\\")).isEqualTo("%\\\\%");
    assertThat(TiberoDmlSql.searchPattern("plain")).isEqualTo("%plain%");
  }

  @Test
  void insert_usesSequenceAndSystimestamp() {
    assertThat(TiberoDmlSql.insert(S, "orders", List.of("name", "qty"), false))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"NAME\", \"QTY\", \"_UPDATED_AT\") VALUES ("
                + SEQ
                + ".NEXTVAL, ?, ?, SYSTIMESTAMP)");
    assertThat(TiberoDmlSql.insert(S, "orders", List.of("name"), true))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"IMPORT_ID\", \"NAME\", \"_UPDATED_AT\") VALUES ("
                + SEQ
                + ".NEXTVAL, ?, ?, SYSTIMESTAMP)");
  }

  @Test
  void insertWithId_andNextId() {
    assertThat(TiberoDmlSql.insertWithId(S, "orders", List.of("name", "qty")))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"NAME\", \"QTY\", \"_UPDATED_AT\") VALUES (?, ?, ?, SYSTIMESTAMP)");
    assertThat(TiberoDmlSql.nextId(S, "orders")).isEqualTo("SELECT " + SEQ + ".NEXTVAL FROM DUAL");
  }

  @Test
  void merge_exactSql_singlePk_noImportId() {
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
  }

  @Test
  void merge_exactSql_compositePk_withImportId() {
    assertThat(TiberoDmlSql.merge(S, "orders", List.of("a", "b", "name"), List.of("a", "b"), true))
        .isEqualTo(
            "MERGE INTO "
                + T
                + " t USING (SELECT ? \"IMPORT_ID\", ? \"A\", ? \"B\", ? \"NAME\" FROM DUAL) s "
                + "ON (t.\"A\" = s.\"A\" AND t.\"B\" = s.\"B\") "
                + "WHEN MATCHED THEN UPDATE SET t.\"NAME\" = s.\"NAME\", "
                + "t.\"IMPORT_ID\" = s.\"IMPORT_ID\", t.\"_UPDATED_AT\" = SYSTIMESTAMP "
                + "WHEN NOT MATCHED THEN INSERT (\"ID\", \"IMPORT_ID\", \"A\", \"B\", \"NAME\", "
                + "\"_UPDATED_AT\") VALUES ("
                + SEQ
                + ".NEXTVAL, s.\"IMPORT_ID\", s.\"A\", s.\"B\", s.\"NAME\", SYSTIMESTAMP)");
  }

  @Test
  void merge_onlyPkColumns_updatesOnlyTimestamp() {
    assertThat(TiberoDmlSql.merge(S, "orders", List.of("code"), List.of("code"), false))
        .contains("WHEN MATCHED THEN UPDATE SET t.\"_UPDATED_AT\" = SYSTIMESTAMP WHEN NOT");
  }

  @Test
  void existingKeys_singleAndComposite() {
    assertThat(TiberoDmlSql.existingKeys(S, "orders", List.of("code"), 3))
        .isEqualTo("SELECT \"CODE\" FROM " + T + " WHERE \"CODE\" IN (?, ?, ?)");
    assertThat(TiberoDmlSql.existingKeys(S, "orders", List.of("a", "b"), 2))
        .isEqualTo("SELECT \"A\", \"B\" FROM " + T + " WHERE (\"A\", \"B\") IN ((?, ?), (?, ?))");
  }

  @Test
  void update_setsSystimestamp() {
    assertThat(TiberoDmlSql.update(S, "orders", List.of("name", "qty")))
        .isEqualTo(
            "UPDATE "
                + T
                + " SET \"NAME\" = ?, \"QTY\" = ?, \"_UPDATED_AT\" = SYSTIMESTAMP "
                + "WHERE \"ID\" = ?");
  }

  @Test
  void deleteStatements() {
    assertThat(TiberoDmlSql.deleteByIds(S, "orders", 3))
        .isEqualTo("DELETE FROM " + T + " WHERE \"ID\" IN (?, ?, ?)");
    assertThat(TiberoDmlSql.deleteAll(S, "orders")).isEqualTo("DELETE FROM " + T);
  }

  @Test
  void getRow_selectsCreatedAt() {
    assertThat(TiberoDmlSql.getRow(S, "orders", List.of("name")))
        .isEqualTo("SELECT \"ID\", \"NAME\", \"CREATED_AT\" FROM " + T + " WHERE \"ID\" = ?");
  }

  @Test
  void uniquenessAndDuplicates() {
    // 한 번의 스캔으로 (전체 행 수, 고유 조합 수). 단일 PK 는 PG 의 COUNT(DISTINCT (a)) 처럼 NULL 을 세지 않는다
    // (NULL 이 있으면 유일하지 않음).
    assertThat(TiberoDmlSql.uniqueness(S, "orders", List.of("a")))
        .isEqualTo("SELECT COUNT(*), COUNT(DISTINCT \"A\") FROM " + T);
    // 복합은 NULL 이 섞인 조합도 하나의 값으로 센다(GROUP BY 가 NULL 을 한 묶음으로 본다).
    assertThat(TiberoDmlSql.uniqueness(S, "orders", List.of("a", "b")))
        .isEqualTo(
            "SELECT NVL(SUM(CNT__), 0), COUNT(*) FROM (SELECT COUNT(*) AS CNT__ FROM "
                + T
                + " GROUP BY \"A\", \"B\")");
    assertThat(TiberoDmlSql.duplicates(S, "orders", List.of("a", "b")))
        .isEqualTo(
            "SELECT * FROM (SELECT \"A\", \"B\", COUNT(*) AS \"DUPLICATE_COUNT\" FROM "
                + T
                + " GROUP BY \"A\", \"B\" HAVING COUNT(*) > 1) WHERE ROWNUM <= ?");
  }

  // ---------- 스테이징 ----------

  private static final String STG = "STG_0123456789ABCDEF01234567";
  private static final String STG_T = "\"GENIA_T5\".\"" + STG + "\"";

  /** 스테이징 = 테이블 → _SEQ 인덱스 → 전용 시퀀스(WD-77). 인덱스가 시퀀스보다 앞이라 시퀀스 실패 시 테이블 DROP 만으로 정리된다. */
  @Test
  void createStaging_tableSeqIndexAndSequence() {
    List<String> sql =
        TiberoDmlSql.createStaging(
            S, STG, List.of("code", "qty"), List.of("VARCHAR2(10)", "NUMBER(18,6)"));
    assertThat(sql).hasSize(3);
    assertThat(sql.get(0))
        .isEqualTo(
            "CREATE TABLE "
                + STG_T
                + " (\"_SEQ\" NUMBER(19), \"CODE\" VARCHAR2(10), \"QTY\" NUMBER(18,6))");
    assertThat(sql.get(1))
        .isEqualTo(
            "CREATE INDEX \"GENIA_T5\".\""
                + TiberoNames.stagingSeqIndexName(STG)
                + "\" ON "
                + STG_T
                + " (\"_SEQ\")");
    assertThat(sql.get(2))
        .isEqualTo(
            "CREATE SEQUENCE \"GENIA_T5\".\""
                + TiberoNames.stagingSequenceName(STG)
                + "\" START WITH 1 INCREMENT BY 1 NOCACHE");
  }

  @Test
  void dropStaging_tableAndSequence() {
    assertThat(TiberoDmlSql.dropStaging(S, STG))
        .containsExactly(
            "DROP TABLE " + STG_T,
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.stagingSequenceName(STG) + "\"");
  }

  @Test
  void insertStaging_usesStagingSequence() {
    assertThat(TiberoDmlSql.insertStaging(S, STG, List.of("code", "qty")))
        .isEqualTo(
            "INSERT INTO "
                + STG_T
                + " (\"_SEQ\", \"CODE\", \"QTY\") VALUES (\"GENIA_T5\".\""
                + TiberoNames.stagingSequenceName(STG)
                + "\".NEXTVAL, ?, ?)");
  }

  private static final String SOURCE =
      "SELECT \"CODE\", \"QTY\" FROM (SELECT \"CODE\", \"QTY\", ROW_NUMBER() OVER ("
          + "PARTITION BY \"CODE\" ORDER BY \"_SEQ\" DESC) AS RN__ FROM "
          + STG_T
          + ") WHERE RN__ = 1";

  @Test
  void promoteUpsertSource_rowNumberDedup() {
    assertThat(TiberoDmlSql.promoteUpsertSource(S, STG, List.of("code", "qty"), List.of("code")))
        .isEqualTo(SOURCE);
  }

  @Test
  void promoteUpsertCount_countsExistingKeysOfDedupSource() {
    assertThat(
            TiberoDmlSql.promoteUpsertCount(
                S, "orders", STG, List.of("code", "qty"), List.of("code")))
        .isEqualTo(
            "SELECT COUNT(*), NVL(SUM(CASE WHEN EXISTS (SELECT 1 FROM "
                + T
                + " t WHERE t.\"CODE\" = s.\"CODE\") THEN 1 ELSE 0 END), 0) FROM ("
                + SOURCE
                + ") s");
  }

  @Test
  void promoteUpsertMerge_withAndWithoutImportId() {
    assertThat(
            TiberoDmlSql.promoteUpsertMerge(
                S, "orders", STG, List.of("code", "qty"), List.of("code"), false))
        .isEqualTo(
            "MERGE INTO "
                + T
                + " t USING ("
                + SOURCE
                + ") s ON (t.\"CODE\" = s.\"CODE\") WHEN MATCHED THEN UPDATE SET "
                + "t.\"QTY\" = s.\"QTY\", t.\"_UPDATED_AT\" = SYSTIMESTAMP "
                + "WHEN NOT MATCHED THEN INSERT (\"ID\", \"CODE\", \"QTY\", \"_UPDATED_AT\") "
                + "VALUES ("
                + SEQ
                + ".NEXTVAL, s.\"CODE\", s.\"QTY\", SYSTIMESTAMP)");
    assertThat(
            TiberoDmlSql.promoteUpsertMerge(
                S, "orders", STG, List.of("code", "qty"), List.of("code"), true))
        .contains(" t USING (SELECT src.*, ? \"IMPORT_ID\" FROM (" + SOURCE + ") src) s ON (")
        .contains("t.\"IMPORT_ID\" = s.\"IMPORT_ID\", t.\"_UPDATED_AT\" = SYSTIMESTAMP")
        .contains("(\"ID\", \"IMPORT_ID\", \"CODE\", \"QTY\", \"_UPDATED_AT\")");
  }

  @Test
  void promoteReplace_exactSql() {
    assertThat(
            TiberoDmlSql.promoteReplace(S, "orders", STG, List.of("code", "qty"), List.of("code")))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"CODE\", \"QTY\", \"_UPDATED_AT\") SELECT "
                + SEQ
                + ".NEXTVAL, \"CODE\", \"QTY\", SYSTIMESTAMP FROM ("
                + SOURCE
                + ")");
  }

  @Test
  void promoteReplace_noPk_copiesEveryRowInSeqOrder() {
    assertThat(TiberoDmlSql.promoteReplace(S, "orders", STG, List.of("code"), List.of()))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"CODE\", \"_UPDATED_AT\") SELECT "
                + SEQ
                + ".NEXTVAL, \"CODE\", SYSTIMESTAMP FROM (SELECT \"CODE\" FROM "
                + STG_T
                + " ORDER BY \"_SEQ\")");
  }

  /**
   * 옮기기 SQL 고정(WD-77). ID 는 전용 시퀀스, _UPDATED_AT 은 SYSTIMESTAMP — promoteReplace 와 같은 방식이며 출력 테이블에는
   * _SEQ 가 없다. 스테이징 _SEQ(load 가 입력 순번으로 채움)로 입력 순서를 잡는다.
   */
  @Test
  void moveFromStaging_exactSql() {
    assertThat(
            TiberoDmlSql.moveFromStaging(
                S, "orders", "STG_0123456789ABCDEF01234567", List.of("code", "qty")))
        .isEqualTo(
            "INSERT INTO "
                + T
                + " (\"ID\", \"CODE\", \"QTY\", \"_UPDATED_AT\") SELECT "
                + SEQ
                + ".NEXTVAL, \"CODE\", \"QTY\", SYSTIMESTAMP FROM (SELECT \"CODE\", \"QTY\" FROM"
                + " \"GENIA_T5\".\"STG_0123456789ABCDEF01234567\" ORDER BY \"_SEQ\")");
  }

  @Test
  void moveFromStaging_rejectsBadNames() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> TiberoDmlSql.moveFromStaging(S, "orders", "orders; DROP", List.of("code")))
        .isInstanceOf(RuntimeException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                TiberoDmlSql.moveFromStaging(
                    S, "orders", "STG_0123456789ABCDEF01234567", List.of("co\"de")))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void columnTypes_query_andDdlRebuild() {
    assertThat(TiberoCatalog.columnTypesQuery())
        .isEqualTo(
            "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, CHAR_USED,"
                + " CHAR_LENGTH FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ?");
    assertThat(TiberoColumnType.of("VARCHAR2", 50, null, null).ddl()).isEqualTo("VARCHAR2(50)");
    assertThat(TiberoColumnType.of("NUMBER", 22, 18, 6).ddl()).isEqualTo("NUMBER(18,6)");
    assertThat(TiberoColumnType.of("NUMBER", 22, 19, 0).ddl()).isEqualTo("NUMBER(19,0)");
    assertThat(TiberoColumnType.of("NUMBER", 22, null, null).ddl()).isEqualTo("NUMBER");
    assertThat(TiberoColumnType.of("DATE", 7, null, null).ddl()).isEqualTo("DATE");
    assertThat(TiberoColumnType.of("TIMESTAMP(6)", 11, null, 6).ddl()).isEqualTo("TIMESTAMP");
  }

  @Test
  void ddlRebuild_keepsCharSemantics() {
    // VARCHAR2(10 CHAR): DATA_LENGTH 는 바이트(40), CHAR_LENGTH 는 문자 수(10). 스테이징도 CHAR 의미를 유지해야 한다.
    assertThat(new TiberoColumnType("VARCHAR2", 40, null, null, "C", 10).ddl())
        .isEqualTo("VARCHAR2(10 CHAR)");
    // 바이트 의미(TEXT = VARCHAR2(4000)) 는 DATA_LENGTH 그대로.
    assertThat(new TiberoColumnType("VARCHAR2", 4000, null, null, "B", 4000).ddl())
        .isEqualTo("VARCHAR2(4000)");
  }

  @Test
  void physicalIndexKeyBytes() {
    assertThat(new TiberoColumnType("VARCHAR2", 40, null, null, "C", 10).indexKeyBytes())
        .isEqualTo(40);
    assertThat(TiberoColumnType.of("NUMBER", 22, 19, 0).indexKeyBytes()).isEqualTo(22);
    assertThat(TiberoColumnType.of("DATE", 7, null, null).indexKeyBytes()).isEqualTo(7);
    assertThat(TiberoColumnType.of("TIMESTAMP(6)", 11, null, 6).indexKeyBytes()).isEqualTo(11);
  }

  @Test
  void search_textExprFromPhysicalType_exactSql() {
    // PG 의 CAST(c AS TEXT) 와 같은 모양: NLS 와 무관한 날짜 마스크, BOOLEAN 은 true/false, 문자는 그대로.
    Map<String, TiberoColumnType> types =
        Map.of(
            "d", TiberoColumnType.of("DATE", 7, null, null),
            "ts", TiberoColumnType.of("TIMESTAMP(6)", 11, null, 6),
            "price", TiberoColumnType.of("NUMBER", 22, 18, 6),
            "qty", TiberoColumnType.of("NUMBER", 22, 19, 0),
            "flag", TiberoColumnType.of("NUMBER", 22, 1, 0),
            "name", new TiberoColumnType("VARCHAR2", 400, null, null, "C", 100));
    List<String> cols = List.of("d", "ts", "price", "qty", "flag", "name", "unknown");
    assertThat(TiberoDmlSql.count(S, "orders", cols, types))
        .isEqualTo(
            "SELECT COUNT(*) FROM "
                + T
                + " WHERE (UPPER(TO_CHAR(\"D\", 'YYYY-MM-DD')) LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(TO_CHAR(\"TS\", 'YYYY-MM-DD HH24:MI:SS.FF6')) LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(TO_CHAR(\"PRICE\", 'FM999999999999990.000000')) LIKE UPPER(?) ESCAPE"
                + " '\\' "
                + "OR UPPER(TO_CHAR(\"QTY\")) LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(CASE \"FLAG\" WHEN 1 THEN 'true' WHEN 0 THEN 'false' END) LIKE UPPER(?)"
                + " ESCAPE '\\' "
                + "OR UPPER(\"NAME\") LIKE UPPER(?) ESCAPE '\\' "
                + "OR UPPER(TO_CHAR(\"UNKNOWN\")) LIKE UPPER(?) ESCAPE '\\')");
    assertThat(
            TiberoDmlSql.select(S, "orders", List.of("flag"), List.of("flag"), null, null, types))
        .contains("UPPER(CASE \"FLAG\" WHEN 1 THEN 'true' WHEN 0 THEN 'false' END) LIKE UPPER(?)");
  }
}
