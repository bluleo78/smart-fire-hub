package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tibero 행 트리거 실서버 테스트(WD-77) — 실서버가 있을 때만 돈다(실행: {@code scripts/tibero-test.sh api}).
 *
 * <p>일회용 계정({@code WD77TRG<난수>})을 DBA 로 만들고, OWNER 세션에서 실제 SQL 빌더({@link TiberoDdlSql})가 만든 문장으로
 * 확인한다: 권한 없을 때 거절, 권한 후 {@code SESSION_PRIVS} 행, ID 채움·명시 ID 유지·UPDATE 시 {@code _UPDATED_AT} 갱신,
 * 트리거가 없어도 사용자 INSERT 의 ID 가 채워지고, 옮기기 SQL 의 명시적 ID 가 연속인지 확인한다. 만든 계정만 마지막에 지운다. 접속 정보는 출력하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
class TiberoLiveTriggerTest {

  private static final String STG = "STG_0123456789ABCDEF01234567";

  private static void run(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static List<String> column(Connection c, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static void probe(String name, String result) {
    System.out.println("PROBE " + name + ": " + result);
  }

  @Test
  void rowTrigger_fillsIdAndUpdatedAt_andLeavesExplicitInsertPathsAlone() throws Exception {
    TiberoTestEnvironment.assumeDriverPresent();
    TiberoTestEnvironment.Dba dba = TiberoTestEnvironment.dbaFromEnv();
    String user = "WD77TRG" + ThreadLocalRandom.current().nextInt(100000, 999999);
    String pw = "Pw" + ThreadLocalRandom.current().nextLong(100000000L, 999999999L) + "x";
    boolean created = false;
    try (Connection d = TiberoTestEnvironment.openDba(dba)) {
      d.setAutoCommit(true);
      run(
          d,
          "CREATE USER \"" + user + "\" IDENTIFIED BY \"" + pw + "\" DEFAULT TABLESPACE \"USR\"");
      created = true;
      run(d, "ALTER USER \"" + user + "\" QUOTA UNLIMITED ON \"USR\"");
      // 계정 계획의 기존 권한(트리거 제외)만 먼저 준다.
      run(
          d,
          "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE TO \"" + user + "\"");

      String t = "orders";
      List<DatasetColumnRequest> cols =
          List.of(new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false));
      try (Connection o = DriverManager.getConnection(dba.target().jdbcUrl(), user, pw)) {
        o.setAutoCommit(true);
        // 테이블·시퀀스(읽기 계정 GRANT 는 이 일회용 스키마에 RO 계정이 없어 뺀다).
        for (String sql : TiberoDdlSql.createTable(user, t, cols)) {
          if (!sql.startsWith("GRANT")) {
            run(o, sql);
          }
        }
        String trigger = TiberoDdlSql.createRowTrigger(user, t);

        // 권한 전: SESSION_PRIVS 에 없고, 트리거 생성은 거절된다.
        String privSql =
            "SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN ('CREATE TRIGGER','CREATE ANY TRIGGER')";
        assertThat(column(o, privSql)).isEmpty();
        Throwable denied = null;
        try {
          run(o, trigger);
        } catch (SQLException e) {
          denied = e;
        }
        assertThat(denied).isNotNull();
        probe("trigger-without-privilege", "DENIED " + ((SQLException) denied).getErrorCode());

        // 권한 후: OWNER 세션에서 행 이름이 'CREATE TRIGGER' 로 보인다.
        run(d, "GRANT CREATE TRIGGER TO \"" + user + "\"");
        assertThat(column(o, privSql)).containsExactly("CREATE TRIGGER");
        run(o, trigger);
        assertThat(
                column(
                    o,
                    "SELECT STATUS FROM USER_TRIGGERS WHERE TRIGGER_NAME = '"
                        + TiberoNames.triggerName(user, t)
                        + "'"))
            .containsExactly("ENABLED");

        String tbl = TiberoNames.table(user, t);
        // ID 없이 INSERT → 시퀀스에서 채움, 명시한 ID 는 그대로.
        run(o, "INSERT INTO " + tbl + " (\"NAME\") VALUES ('a')");
        run(o, "INSERT INTO " + tbl + " (\"ID\", \"NAME\") VALUES (100, 'b')");
        run(o, "INSERT INTO " + tbl + " (\"NAME\") VALUES ('c')");
        assertThat(column(o, "SELECT \"ID\" FROM " + tbl + " ORDER BY 1"))
            .containsExactly("1", "2", "100");

        // UPDATE 는 _UPDATED_AT 을 현재 시각으로 옮긴다(옛 값을 직접 넣어도 덮어쓴다).
        run(
            o,
            "UPDATE "
                + tbl
                + " SET \"_UPDATED_AT\" = TIMESTAMP '2000-01-01 00:00:00' WHERE \"ID\" = 100");
        try (Statement st = o.createStatement();
            ResultSet rs =
                st.executeQuery("SELECT \"_UPDATED_AT\" FROM " + tbl + " WHERE \"ID\" = 100")) {
          assertThat(rs.next()).isTrue();
          Timestamp ts = rs.getTimestamp(1);
          assertThat(ts.toLocalDateTime().getYear()).isGreaterThanOrEqualTo(2026);
        }
        run(o, "UPDATE " + tbl + " SET \"NAME\" = 'z' WHERE \"ID\" = 1");

        // 옮기기 SQL: 트리거가 있어도 ID 를 명시하므로 NEXTVAL 이 두 번 쓰이지 않아 연속이다(3, 4).
        run(
            o,
            "CREATE TABLE "
                + TiberoNames.qualified(user, STG)
                + " (\"NAME\" VARCHAR2(10), \"_SEQ\" NUMBER)");
        run(o, "INSERT INTO " + TiberoNames.qualified(user, STG) + " VALUES ('x', 1)");
        run(o, "INSERT INTO " + TiberoNames.qualified(user, STG) + " VALUES ('y', 2)");
        run(o, TiberoDmlSql.moveFromStaging(user, t, STG, List.of("name")));
        assertThat(column(o, "SELECT \"ID\" FROM " + tbl + " ORDER BY 1"))
            .containsExactly("1", "2", "3", "4", "100");

        // 사용자가 직접 쓴 INSERT … SELECT(Task 9): ID 를 적지 않아도 기본값이 이어서 채운다(5, 6). 따옴표 없는 이름도 같다.
        run(
            o,
            "INSERT INTO "
                + user
                + "."
                + t
                + " (name) SELECT 'p' FROM DUAL UNION ALL SELECT 'q' FROM DUAL");
        assertThat(column(o, "SELECT \"ID\" FROM " + tbl + " ORDER BY 1"))
            .containsExactly("1", "2", "3", "4", "5", "6", "100");
        // 사용자 UPDATE 도 _UPDATED_AT 을 올린다(위에서 2000-01-01 로 박은 행이 아니라 이번 갱신 행 기준).
        run(o, "UPDATE " + user + "." + t + " SET name = 'w' WHERE \"ID\" = 5");
        assertThat(
                column(
                    o, "SELECT TO_CHAR(\"_UPDATED_AT\", 'YYYY') FROM " + tbl + " WHERE \"ID\" = 5"))
            .allSatisfy(y -> assertThat(Integer.parseInt(y)).isGreaterThanOrEqualTo(2026));

        // 꺼진 트리거는 STATUS 가 DISABLED — ensureRowTrigger 는 이를 없는 것으로 보고 CREATE OR REPLACE 로 다시 켠다.
        String statusSql =
            "SELECT STATUS FROM USER_TRIGGERS WHERE TRIGGER_NAME = '"
                + TiberoNames.triggerName(user, t)
                + "'";
        run(
            o,
            "ALTER TRIGGER "
                + TiberoNames.qualified(user, TiberoNames.triggerName(user, t))
                + " DISABLE");
        assertThat(column(o, statusSql)).containsExactly("DISABLED");
        // INSERT 에는 트리거가 관여하지 않는다. 꺼 둔 상태에서도 새 ID 를 얻는다.
        run(o, "INSERT INTO " + tbl + " (\"NAME\") VALUES ('trigger-off')");
        assertThat(column(o, "SELECT \"ID\" FROM " + tbl + " WHERE \"NAME\" = 'trigger-off'"))
            .hasSize(1);
        run(o, trigger);
        assertThat(column(o, statusSql)).containsExactly("ENABLED");

        // 컬럼 DEFAULT 시퀀스.NEXTVAL 지원 여부를 별도 작은 테이블로도 확인한다.
        String seq = TiberoNames.qualified(user, TiberoNames.sequenceName(user, t));
        try {
          run(
              o,
              "CREATE TABLE "
                  + TiberoNames.qualified(user, "DFLT")
                  + " (\"ID\" NUMBER(19) DEFAULT "
                  + seq
                  + ".NEXTVAL PRIMARY KEY, \"V\" VARCHAR2(10))");
          run(o, "INSERT INTO " + TiberoNames.qualified(user, "DFLT") + " (\"V\") VALUES ('q')");
          probe("column-default-sequence-nextval", "SUPPORTED (INSERT without ID filled it)");
        } catch (SQLException e) {
          probe("column-default-sequence-nextval", "UNSUPPORTED " + e.getErrorCode());
        }
      }
    } finally {
      if (created) {
        try (Connection d = TiberoTestEnvironment.openDba(dba);
            Statement st = d.createStatement()) {
          st.execute("DROP USER \"" + user + "\" CASCADE");
        }
      }
    }
  }

  /**
   * 옛 행 트리거(3023f150 까지의 {@code createRowTrigger}) — INSERT 때 ID 를 채우고 UPDATE 때 갱신 시각을 옮겼다. 옛 모양
   * 테이블을 실서버에 손으로 재현할 때만 쓴다(WD-233 리뷰 I-2).
   */
  static String legacyRowTrigger(String schema, String table) {
    String seq = TiberoNames.qualified(schema, TiberoNames.sequenceName(schema, table));
    return "CREATE OR REPLACE TRIGGER "
        + TiberoNames.qualified(schema, TiberoNames.triggerName(schema, table))
        + " BEFORE INSERT OR UPDATE ON "
        + TiberoNames.table(schema, table)
        + " FOR EACH ROW\n"
        + "BEGIN\n"
        + "  IF INSERTING AND :NEW.\"ID\" IS NULL THEN :NEW.\"ID\" := "
        + seq
        + ".NEXTVAL; END IF;\n"
        + "  IF UPDATING THEN :NEW.\"_UPDATED_AT\" := SYSTIMESTAMP; END IF;\n"
        + "END;";
  }

  /** 옛 {@code createTable} 처럼 ID 기본값이 없는 테이블 DDL — 새 빌더의 ID 정의에서 DEFAULT 만 뺀다(그 밖의 열·색인은 같다). */
  static List<String> legacyCreateTable(
      String schema, String table, List<DatasetColumnRequest> cols) {
    String withDefault =
        "\"ID\" NUMBER(19) DEFAULT "
            + TiberoNames.qualified(schema, TiberoNames.sequenceName(schema, table))
            + ".NEXTVAL PRIMARY KEY";
    List<String> out = new ArrayList<>();
    for (String sql : TiberoDdlSql.createTable(schema, table, cols)) {
      if (sql.startsWith("CREATE TABLE")) {
        assertThat(sql).contains(withDefault);
        sql = sql.replace(withDefault, "\"ID\" NUMBER(19) PRIMARY KEY");
      }
      out.add(sql);
    }
    return out;
  }

  /**
   * 쓰기 경로의 두 카탈로그 판정({@link TiberoCatalog#enabledTriggerEvents} 의 {@code TRIGGERING_EVENT}, {@link
   * TiberoCatalog#idDefaultUsesSequence} 의 {@code DATA_DEFAULT})이 실서버 표기와 맞는지 확인한다(WD-233 리뷰 I-2).
   * 판정이 어긋나면 모든 쓰기에서 DDL 이 반복된다. 새 모양(기본값 + UPDATE 전용 트리거)은 둘 다 참, 옛 모양(기본값 없음 + INSERT OR UPDATE
   * 트리거)은 둘 다 거짓이어야 한다. 실제 표기는 PROBE 줄로 남긴다.
   */
  @Test
  void catalogJudgments_matchLiveNotation_forNewAndLegacyShapes() throws Exception {
    TiberoTestEnvironment.assumeDriverPresent();
    TiberoTestEnvironment.Dba dba = TiberoTestEnvironment.dbaFromEnv();
    String user = "WD233CAT" + ThreadLocalRandom.current().nextInt(100000, 999999);
    String pw = "Pw" + ThreadLocalRandom.current().nextLong(100000000L, 999999999L) + "x";
    boolean created = false;
    try (Connection d = TiberoTestEnvironment.openDba(dba)) {
      d.setAutoCommit(true);
      run(
          d,
          "CREATE USER \"" + user + "\" IDENTIFIED BY \"" + pw + "\" DEFAULT TABLESPACE \"USR\"");
      created = true;
      run(d, "ALTER USER \"" + user + "\" QUOTA UNLIMITED ON \"USR\"");
      run(
          d,
          "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER TO \""
              + user
              + "\"");
      List<DatasetColumnRequest> cols =
          List.of(new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false));
      try (Connection o = DriverManager.getConnection(dba.target().jdbcUrl(), user, pw)) {
        o.setAutoCommit(true);
        String fresh = "fresh";
        for (String sql : TiberoDdlSql.createTable(user, fresh, cols)) {
          if (!sql.startsWith("GRANT")) {
            run(o, sql);
          }
        }
        run(o, TiberoDdlSql.createRowTrigger(user, fresh));
        String legacy = "legacy";
        for (String sql : legacyCreateTable(user, legacy, cols)) {
          if (!sql.startsWith("GRANT")) {
            run(o, sql);
          }
        }
        run(o, legacyRowTrigger(user, legacy));

        String freshTrigger = TiberoNames.triggerName(user, fresh);
        String legacyTrigger = TiberoNames.triggerName(user, legacy);
        for (String table : List.of(fresh, legacy)) {
          String trigger = TiberoNames.triggerName(user, table);
          probe(
              "catalog-triggering-event-" + table,
              "["
                  + column(
                          o,
                          "SELECT TRIGGERING_EVENT FROM USER_TRIGGERS WHERE TRIGGER_NAME = '"
                              + trigger
                              + "'")
                      .get(0)
                  + "]");
          probe(
              "catalog-data-default-" + table,
              "["
                  + column(
                          o,
                          "SELECT DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME = '"
                              + TiberoNames.physical(table)
                              + "' AND COLUMN_NAME = 'ID'")
                      .get(0)
                  + "]");
        }

        // 운영 경로와 같은 판정(이벤트 조회 한 번 + 판정 함수)으로 새·옛 모양을 가린다.
        Map<String, String> events =
            TiberoCatalog.enabledTriggerEvents(o, List.of(freshTrigger, legacyTrigger));
        assertThat(events).containsOnlyKeys(freshTrigger, legacyTrigger);
        assertThat(TiberoCatalog.classify(events.get(freshTrigger)))
            .isEqualTo(TiberoCatalog.TriggerShape.CURRENT);
        assertThat(TiberoCatalog.classify(events.get(legacyTrigger)))
            .isEqualTo(TiberoCatalog.TriggerShape.LEGACY);
        assertThat(TiberoCatalog.idDefaultUsesSequence(o, user, fresh)).isTrue();
        assertThat(TiberoCatalog.idDefaultUsesSequence(o, user, legacy)).isFalse();

        // 기본값을 다시 묶은 옛 테이블은 기본값 판정이 참으로 바뀐다(트리거는 여전히 옛 것).
        run(o, TiberoDdlSql.setIdDefault(user, legacy));
        assertThat(TiberoCatalog.idDefaultUsesSequence(o, user, legacy)).isTrue();
      }
    } finally {
      if (created) {
        try (Connection d = TiberoTestEnvironment.openDba(dba);
            Statement st = d.createStatement()) {
          st.execute("DROP USER \"" + user + "\" CASCADE");
        }
      }
    }
  }
}
