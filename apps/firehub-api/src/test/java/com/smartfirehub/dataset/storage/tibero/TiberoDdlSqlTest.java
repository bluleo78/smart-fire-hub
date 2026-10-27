package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.storage.DataStoreFeatureUnsupportedException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tibero DDL 빌더가 만드는 SQL 문자열과 실행 순서를 고정한다. 실서버 없이 순수 문자열만 검증한다. */
class TiberoDdlSqlTest {

  private static final String S = "GENIA_T5";

  private static DatasetColumnRequest req(
      String name, String type, Integer len, boolean nullable, boolean indexed, boolean pk) {
    return new DatasetColumnRequest(name, name, type, len, nullable, indexed, null, pk);
  }

  @Test
  void createTable_exactStatements() {
    List<String> sql =
        TiberoDdlSql.createTable(
            S,
            "t",
            List.of(
                req("name", "TEXT", null, true, false, true),
                req("qty", "INTEGER", null, true, true, false),
                req("active", "BOOLEAN", null, true, false, false)));

    assertThat(sql)
        .containsExactly(
            "CREATE SEQUENCE \"GENIA_T5\".\""
                + TiberoNames.sequenceName(S, "t")
                + "\" START WITH 1 INCREMENT BY 1 NOCACHE",
            "CREATE TABLE \"GENIA_T5\".\"T\" (\"ID\" NUMBER(19) DEFAULT \"GENIA_T5\".\""
                + TiberoNames.sequenceName(S, "t")
                + "\".NEXTVAL PRIMARY KEY, \"IMPORT_ID\""
                + " NUMBER(19), \"NAME\" VARCHAR2(4000), \"QTY\" NUMBER(19), \"ACTIVE\" NUMBER(1)"
                + " CONSTRAINT \""
                + TiberoNames.checkConstraintName(S, "t", "active")
                + "\" CHECK (\"ACTIVE\" IN (0,1)), \"CREATED_AT\" TIMESTAMP DEFAULT"
                + " SYSTIMESTAMP,"
                + " \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)",
            "CREATE INDEX \"GENIA_T5\".\""
                + TiberoNames.indexName(S, "t", "qty")
                + "\" ON \"GENIA_T5\".\"T\" (\"QTY\")",
            "CREATE UNIQUE INDEX \"GENIA_T5\".\""
                + TiberoNames.primaryKeyIndexName(S, "t")
                + "\" ON \"GENIA_T5\".\"T\" (\"NAME\")",
            "CREATE INDEX \"GENIA_T5\".\""
                + TiberoNames.indexName(S, "t", "_updated_at")
                + "\" ON \"GENIA_T5\".\"T\" (\"_UPDATED_AT\")",
            "GRANT SELECT ON \"GENIA_T5\".\"T\" TO \"GENIA_T5_RO\"");
  }

  @Test
  void createTable_notNullColumn_appendsNotNull() {
    List<String> sql =
        TiberoDdlSql.createTable(S, "t", List.of(req("a", "VARCHAR", 10, false, false, false)));
    assertThat(sql.get(1)).contains("\"A\" VARCHAR2(10 CHAR) NOT NULL, \"CREATED_AT\"");
  }

  @Test
  void createTable_indexKeyLength_singleTextAllowed() {
    // TEXT 하나(4000 바이트)는 한도(6000) 안이다 — 컬럼 인덱스·단일 PK 모두 허용.
    List<String> sql =
        TiberoDdlSql.createTable(S, "t", List.of(req("memo", "TEXT", null, true, true, true)));
    assertThat(sql).anyMatch(s -> s.startsWith("CREATE UNIQUE INDEX"));
  }

  @Test
  void createTable_indexKeyLength_twoTextPkRejectedBeforeAnyDdl() {
    // 빌더가 문장을 하나도 돌려주지 않고 거부하므로 실행부는 아무 DDL 도 실행하지 않는다.
    assertThatThrownBy(
            () ->
                TiberoDdlSql.createTable(
                    S,
                    "t",
                    List.of(
                        req("a", "TEXT", null, false, false, true),
                        req("b", "TEXT", null, false, false, true))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Tibero 저장소의 인덱스 키 길이 제한(약 6000바이트)을 넘습니다: a, b — TEXT 대신 짧은 VARCHAR(n) 을 쓰세요");
  }

  @Test
  void createTable_indexKeyLength_twoShortVarcharsAllowed() {
    List<String> sql =
        TiberoDdlSql.createTable(
            S,
            "t",
            List.of(
                req("a", "VARCHAR", 100, false, false, true),
                req("b", "VARCHAR", 100, false, false, true)));
    assertThat(sql).anyMatch(s -> s.startsWith("CREATE UNIQUE INDEX"));
  }

  @Test
  void checkIndexKeyLength_boundary() {
    TiberoDdlSql.checkIndexKeyLength(List.of("a", "b"), List.of(4000, 2000));
    assertThatThrownBy(
            () -> TiberoDdlSql.checkIndexKeyLength(List.of("a", "b"), List.of(4000, 2001)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createTable_rejectsReservedOrLongNames() {
    assertThatThrownBy(
            () ->
                TiberoDdlSql.createTable(
                    S, "t", List.of(req("order", "TEXT", null, true, false, false))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TiberoDdlSql.createTable(S, "select", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TiberoDdlSql.createTable(S, "a".repeat(31), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createTable_geometry_unsupported() {
    assertThatThrownBy(
            () ->
                TiberoDdlSql.createTable(
                    S, "t", List.of(req("g", "GEOMETRY", null, true, false, false))))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
  }

  /** DdlStep 목록의 SQL 만. */
  private static List<String> sqlOf(List<TiberoDdlSql.DdlStep> steps) {
    return steps.stream().map(TiberoDdlSql.DdlStep::sql).toList();
  }

  @Test
  void alterColumnType_usesModify() {
    assertThat(sqlOf(TiberoDdlSql.alterColumnType(S, "t", "qty", "VARCHAR", 20, "INTEGER")))
        .containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" MODIFY (\"QTY\" VARCHAR2(20 CHAR))");
    String ck = TiberoNames.checkConstraintName(S, "t", "flag");
    // 비 BOOLEAN -> BOOLEAN: MODIFY 뒤 이름 있는 CHECK 추가
    assertThat(sqlOf(TiberoDdlSql.alterColumnType(S, "t", "flag", "BOOLEAN", null, "INTEGER")))
        .containsExactly(
            "ALTER TABLE \"GENIA_T5\".\"T\" MODIFY (\"FLAG\" NUMBER(1))",
            "ALTER TABLE \"GENIA_T5\".\"T\" ADD CONSTRAINT \""
                + ck
                + "\" CHECK (\"FLAG\" IN (0,1))");
    // BOOLEAN -> 비 BOOLEAN: CHECK 먼저 삭제
    assertThat(sqlOf(TiberoDdlSql.alterColumnType(S, "t", "flag", "INTEGER", null, "BOOLEAN")))
        .containsExactly(
            "ALTER TABLE \"GENIA_T5\".\"T\" DROP CONSTRAINT \"" + ck + "\"",
            "ALTER TABLE \"GENIA_T5\".\"T\" MODIFY (\"FLAG\" NUMBER(19))");
    // BOOLEAN -> BOOLEAN: 제약을 건드리지 않는다
    assertThat(sqlOf(TiberoDdlSql.alterColumnType(S, "t", "flag", "BOOLEAN", null, "BOOLEAN")))
        .containsExactly("ALTER TABLE \"GENIA_T5\".\"T\" MODIFY (\"FLAG\" NUMBER(1))");
  }

  @Test
  void alterColumnType_stepsCarryIgnorableErrors() {
    // 문자열 매칭 대신, 빌더가 "CHECK 삭제는 제약 없음 무시" 를 문장에 붙여 넘긴다(B1).
    List<TiberoDdlSql.DdlStep> steps =
        TiberoDdlSql.alterColumnType(S, "t", "flag", "INTEGER", null, "BOOLEAN");
    assertThat(steps.get(0).ignorable()).containsExactly(TiberoDialect.NotFound.CONSTRAINT);
    assertThat(steps.get(1).ignorable()).isEmpty();
  }

  @Test
  void renameCheckConstraint_andRenameIndexValidation() {
    assertThat(TiberoDdlSql.renameCheckConstraint(S, "t", "a", "b"))
        .isEqualTo(
            "ALTER TABLE \"GENIA_T5\".\"T\" RENAME CONSTRAINT \""
                + TiberoNames.checkConstraintName(S, "t", "a")
                + "\" TO \""
                + TiberoNames.checkConstraintName(S, "t", "b")
                + "\"");
    assertThatThrownBy(() -> TiberoDdlSql.renameIndex(S, "t", "a", "level"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void alterColumnType_geometryEitherSide_unsupported() {
    assertThatThrownBy(() -> TiberoDdlSql.alterColumnType(S, "t", "c", "GEOMETRY", null, "TEXT"))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
    assertThatThrownBy(() -> TiberoDdlSql.alterColumnType(S, "t", "c", "TEXT", null, "GEOMETRY"))
        .isInstanceOf(DataStoreFeatureUnsupportedException.class);
  }

  @Test
  void simpleColumnStatements() {
    assertThat(TiberoDdlSql.renameColumn(S, "t", "a", "b"))
        .isEqualTo("ALTER TABLE \"GENIA_T5\".\"T\" RENAME COLUMN \"A\" TO \"B\"");
    assertThat(TiberoDdlSql.setNullable(S, "t", "a", true))
        .isEqualTo("ALTER TABLE \"GENIA_T5\".\"T\" MODIFY (\"A\" NULL)");
    assertThat(TiberoDdlSql.setNullable(S, "t", "a", false))
        .isEqualTo("ALTER TABLE \"GENIA_T5\".\"T\" MODIFY (\"A\" NOT NULL)");
    assertThat(TiberoDdlSql.dropColumn(S, "t", "a"))
        .isEqualTo("ALTER TABLE \"GENIA_T5\".\"T\" DROP COLUMN \"A\"");
    assertThat(TiberoDdlSql.renameIndex(S, "t", "a", "b"))
        .isEqualTo(
            "ALTER INDEX \"GENIA_T5\".\""
                + TiberoNames.indexName(S, "t", "a")
                + "\" RENAME TO \""
                + TiberoNames.indexName(S, "t", "b")
                + "\"");
  }

  @Test
  void addColumn_withIndexAndBoolean() {
    assertThat(TiberoDdlSql.addColumn(S, "t", req("flag", "BOOLEAN", null, false, true, false)))
        .containsExactly(
            "ALTER TABLE \"GENIA_T5\".\"T\" ADD (\"FLAG\" NUMBER(1) CONSTRAINT \""
                + TiberoNames.checkConstraintName(S, "t", "flag")
                + "\" CHECK (\"FLAG\" IN (0,1)) NOT NULL)",
            "CREATE INDEX \"GENIA_T5\".\""
                + TiberoNames.indexName(S, "t", "flag")
                + "\" ON \"GENIA_T5\".\"T\" (\"FLAG\")");
  }

  @Test
  void dropTable_tableThenSequence() {
    assertThat(TiberoDdlSql.dropTable(S, "t"))
        .containsExactly(
            "DROP TABLE \"GENIA_T5\".\"T\"",
            "DROP SEQUENCE \"GENIA_T5\".\"" + TiberoNames.sequenceName(S, "t") + "\"");
  }

  @Test
  void recreatePrimaryKeyIndex_dropThenCreate() {
    assertThat(TiberoDdlSql.dropPrimaryKeyIndex(S, "t"))
        .isEqualTo("DROP INDEX \"GENIA_T5\".\"" + TiberoNames.primaryKeyIndexName(S, "t") + "\"");
    assertThat(TiberoDdlSql.createPrimaryKeyIndex(S, "t", List.of("a", "b")))
        .isEqualTo(
            "CREATE UNIQUE INDEX \"GENIA_T5\".\""
                + TiberoNames.primaryKeyIndexName(S, "t")
                + "\" ON \"GENIA_T5\".\"T\" (\"A\", \"B\")");
  }

  @Test
  void cloneTable_exactStatements() {
    List<DatasetColumnResponse> defs =
        List.of(
            new DatasetColumnResponse(1L, "a", "A", "TEXT", null, false, false, null, 0, true),
            new DatasetColumnResponse(2L, "b", "B", "INTEGER", null, true, true, null, 1, false),
            new DatasetColumnResponse(3L, "c", "C", "BOOLEAN", null, true, false, null, 2, false));
    List<String> sql = TiberoDdlSql.cloneTable(S, "src", "dst", List.of("a", "b", "c"), defs);

    // 사용자 인덱스·PK 인덱스는 만들지 않는다(호출자가 따로 만든다). _UPDATED_AT 인덱스와 GRANT 는 유지.
    assertThat(sql)
        .containsExactly(
            "CREATE SEQUENCE \"GENIA_T5\".\""
                + TiberoNames.sequenceName(S, "dst")
                + "\" START WITH 1 INCREMENT BY 1 NOCACHE",
            "CREATE TABLE \"GENIA_T5\".\"DST\" (\"ID\" NUMBER(19) DEFAULT \"GENIA_T5\".\""
                + TiberoNames.sequenceName(S, "dst")
                + "\".NEXTVAL PRIMARY KEY, \"IMPORT_ID\""
                + " NUMBER(19), \"A\" VARCHAR2(4000) NOT NULL, \"B\" NUMBER(19), \"C\" NUMBER(1)"
                + " CONSTRAINT \""
                + TiberoNames.checkConstraintName(S, "dst", "c")
                + "\" CHECK (\"C\" IN (0,1)), \"CREATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP,"
                + " \"_UPDATED_AT\" TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)",
            "CREATE INDEX \"GENIA_T5\".\""
                + TiberoNames.indexName(S, "dst", "_updated_at")
                + "\" ON \"GENIA_T5\".\"DST\" (\"_UPDATED_AT\")",
            "GRANT SELECT ON \"GENIA_T5\".\"DST\" TO \"GENIA_T5_RO\"",
            "INSERT INTO \"GENIA_T5\".\"DST\" (\"ID\", \"A\", \"B\", \"C\", \"CREATED_AT\","
                + " \"_UPDATED_AT\") SELECT \"GENIA_T5\".\""
                + TiberoNames.sequenceName(S, "dst")
                + "\".NEXTVAL, \"A\", \"B\", \"C\", \"CREATED_AT\", SYSTIMESTAMP FROM"
                + " \"GENIA_T5\".\"SRC\"");
  }

  @Test
  void columnStats_queries_perType() {
    assertThat(TiberoDdlSql.columnStats(S, "t", "qty", "INTEGER"))
        .isEqualTo(
            "SELECT COUNT(*) AS TOTAL, COUNT(*) - COUNT(\"QTY\") AS NULL_COUNT,"
                + " COUNT(DISTINCT \"QTY\") AS DISTINCT_COUNT, TO_CHAR(MIN(\"QTY\")) AS MIN_VAL,"
                + " TO_CHAR(MAX(\"QTY\")) AS MAX_VAL, AVG(\"QTY\") AS AVG_VAL FROM"
                + " \"GENIA_T5\".\"T\"");
    assertThat(TiberoDdlSql.columnStats(S, "t", "name", "TEXT"))
        .doesNotContain("AVG")
        .contains("TO_CHAR(MIN(\"NAME\")) AS MIN_VAL");
    assertThat(TiberoDdlSql.columnStats(S, "t", "d", "DATE"))
        .doesNotContain("AVG")
        .contains("TO_CHAR(MIN(\"D\"), 'YYYY-MM-DD') AS MIN_VAL")
        .contains("TO_CHAR(MAX(\"D\"), 'YYYY-MM-DD') AS MAX_VAL");
    assertThat(TiberoDdlSql.columnStats(S, "t", "ts", "TIMESTAMP"))
        .contains("TO_CHAR(MIN(\"TS\"), 'YYYY-MM-DD HH24:MI:SS.FF6') AS MIN_VAL")
        .contains("TO_CHAR(MAX(\"TS\"), 'YYYY-MM-DD HH24:MI:SS.FF6') AS MAX_VAL");
    assertThat(TiberoDdlSql.columnStats(S, "t", "x", "DECIMAL"))
        .contains("TO_CHAR(MIN(\"X\"), 'FM999999999999990.000000') AS MIN_VAL")
        .contains("AVG(\"X\") AS AVG_VAL");
    assertThat(TiberoDdlSql.columnStats(S, "t", "b", "BOOLEAN"))
        .doesNotContain("AVG")
        .contains("TO_CHAR(MIN(\"B\")) AS MIN_VAL");
  }

  @Test
  void topValues_perType() {
    assertThat(TiberoDdlSql.topValues(S, "t", "name", "TEXT"))
        .isEqualTo(
            "SELECT VAL, CNT FROM (SELECT TO_CHAR(\"NAME\") AS VAL, COUNT(*) AS CNT,"
                + " ROW_NUMBER() OVER (ORDER BY COUNT(*) DESC, TO_CHAR(\"NAME\")) AS RN FROM"
                + " \"GENIA_T5\".\"T\" WHERE \"NAME\" IS NOT NULL GROUP BY \"NAME\") WHERE RN <= 5"
                + " ORDER BY RN");
    assertThat(TiberoDdlSql.topValues(S, "t", "d", "DATE"))
        .contains("SELECT TO_CHAR(\"D\", 'YYYY-MM-DD') AS VAL")
        .contains("ORDER BY COUNT(*) DESC, TO_CHAR(\"D\", 'YYYY-MM-DD'))");
    assertThat(TiberoDdlSql.topValues(S, "t", "ts", "TIMESTAMP"))
        .contains("TO_CHAR(\"TS\", 'YYYY-MM-DD HH24:MI:SS.FF6') AS VAL");
    assertThat(TiberoDdlSql.topValues(S, "t", "x", "DECIMAL"))
        .contains("TO_CHAR(\"X\", 'FM999999999999990.000000') AS VAL");
  }

  /** 행 트리거 DDL: INSERT 는 ID 기본값이 맡고 UPDATE 만 _UPDATED_AT 을 갱신한다. */
  @Test
  void createRowTrigger_exactStatement() {
    String trg = TiberoNames.triggerName("GENIA_T5", "orders");
    assertThat(TiberoDdlSql.createRowTrigger("GENIA_T5", "orders"))
        .isEqualTo(
            "CREATE OR REPLACE TRIGGER \"GENIA_T5\".\""
                + trg
                + "\" BEFORE UPDATE ON \"GENIA_T5\".\"ORDERS\" FOR EACH ROW\n"
                + "BEGIN\n"
                + "  :NEW.\"_UPDATED_AT\" := SYSTIMESTAMP;\n"
                + "END;");
  }

  /** 트리거는 createTable 문장 목록에 섞이지 않는다 — 권한이 없어도 테이블 생성은 그대로여야 하므로 저장소가 따로 만든다. */
  @Test
  void createTable_statementsHaveNoTrigger() {
    assertThat(
            TiberoDdlSql.createTable("GENIA_T5", "t", List.of()).stream()
                .noneMatch(sql -> sql.contains("TRIGGER")))
        .isTrue();
  }
}
