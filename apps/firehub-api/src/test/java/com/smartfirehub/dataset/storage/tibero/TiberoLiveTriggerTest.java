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
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tibero 행 트리거 실서버 테스트(WD-77) — 실서버가 있을 때만 돈다(실행: {@code scripts/tibero-test.sh api}).
 *
 * <p>일회용 계정({@code WD77TRG<난수>})을 DBA 로 만들고, OWNER 세션에서 실제 SQL 빌더({@link TiberoDdlSql})가 만든 문장으로
 * 확인한다: 권한 없을 때 거절, 권한 후 {@code SESSION_PRIVS} 행, ID 채움·명시 ID 유지·UPDATE 시 {@code _UPDATED_AT} 갱신,
 * 트리거가 있어도 옮기기 SQL 의 ID 가 연속, 컬럼 {@code DEFAULT 시퀀스.NEXTVAL} 지원 여부(PROBE 줄로 기록). 만든 계정만 마지막에 지운다.
 * 접속 정보는 출력하지 않는다.
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

        // 사용자가 직접 쓴 INSERT … SELECT(Task 9): ID 를 적지 않아도 트리거가 이어서 채운다(5, 6). 따옴표 없는 이름도 같다.
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
        run(o, trigger);
        assertThat(column(o, statusSql)).containsExactly("ENABLED");

        // 컬럼 DEFAULT 시퀀스.NEXTVAL 지원 여부(결정 근거 — 트리거는 _UPDATED_AT 때문에 어차피 필요).
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
}
