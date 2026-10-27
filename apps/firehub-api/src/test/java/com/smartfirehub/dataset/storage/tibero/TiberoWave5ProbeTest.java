package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Wave 5 DDL 결정용 실서버 탐침 — 이 테스트가 만든 WD5 객체만 역순으로 지운다. */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
class TiberoWave5ProbeTest {

  private final List<String> cleanups = new ArrayList<>();
  private Connection connection;
  private String prefix;

  /** 연결 정보는 기존 테스트 환경에서만 받고 출력하지 않는다. */
  @BeforeEach
  void connect() throws SQLException {
    TiberoTestEnvironment.assumeDriverPresent();
    connection = TiberoTestEnvironment.openDba(TiberoTestEnvironment.dbaFromEnv());
    connection.setAutoCommit(true);
    prefix = "WD5_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  /** 만든 객체만 역순으로 지운다. 정리 실패는 원래 탐침 실패를 가리지 않게 둔다. */
  @AfterEach
  void cleanUp() throws SQLException {
    if (connection == null) {
      return;
    }
    try {
      for (int i = cleanups.size() - 1; i >= 0; i--) {
        try {
          execute(cleanups.get(i));
        } catch (SQLException ignored) {
          // 앞 단계의 DDL 실패로 객체가 없을 수 있다.
        }
      }
    } finally {
      connection.close();
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private void create(String sql, String dropSql) throws SQLException {
    execute(sql);
    cleanups.add(dropSql);
  }

  private String scalar(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  /**
   * 시퀀스 이름을 바꾸면 옛 이름을 가리키는 ID DEFAULT 는 INSERT 때 JDBC-8034(객체 없음)로 실패하고, DEFAULT 를 새 이름으로 다시
   * 묶으면({@link TiberoDdlSql#setIdDefault} 와 같은 MODIFY) INSERT 가 다시 된다. 보관·복원·맞춤의 재바인딩 코드는 이 사실에 기댄다
   * (WD-233 리뷰 I-1) — Tibero 가 언젠가 DEFAULT 식을 따라 고치게 되면 이 테스트가 먼저 알려 준다.
   */
  @Test
  void sequenceRenameBreaksIdDefaultUntilRebound() throws SQLException {
    String sequence = prefix + "_S";
    String renamed = prefix + "_R";
    String table = prefix + "_T";
    create("CREATE SEQUENCE " + sequence, "DROP SEQUENCE " + sequence);
    create(
        "CREATE TABLE "
            + table
            + " (ID NUMBER(19) DEFAULT "
            + sequence
            + ".NEXTVAL PRIMARY KEY, V NUMBER)",
        "DROP TABLE " + table);
    execute("INSERT INTO " + table + " (V) VALUES (1)");
    execute("RENAME " + sequence + " TO " + renamed);
    cleanups.set(0, "DROP SEQUENCE " + renamed);

    // 이름을 바꾼 뒤: DEFAULT 식은 옛 이름 그대로라 INSERT 가 객체 없음으로 실패한다.
    SQLException broken = null;
    try {
      execute("INSERT INTO " + table + " (V) VALUES (2)");
    } catch (SQLException e) {
      broken = e;
    }
    assertThat((Throwable) broken).as("RENAME 뒤 옛 DEFAULT 로 INSERT").isNotNull();
    System.out.println("PROBE wave5_sequence_rename_default: BROKEN " + broken.getErrorCode());
    assertThat(broken.getErrorCode()).isEqualTo(-8034);

    // 새 이름으로 다시 묶으면 INSERT 가 된다.
    execute("ALTER TABLE " + table + " MODIFY (ID DEFAULT " + renamed + ".NEXTVAL)");
    execute("INSERT INTO " + table + " (V) VALUES (3)");
    assertThat(scalar("SELECT COUNT(DISTINCT ID) FROM " + table)).isEqualTo("2");
    System.out.println("PROBE wave5_sequence_rebind_default: SUPPORTED");
  }

  /**
   * 없는 시퀀스를 가리키는 DEFAULT 로 MODIFY 하면 ALTER 시점에 거부되는지 기록한다(WD-233 리뷰 M-1). 거부되면 시퀀스가 없을 때의 무조건 재바인딩은
   * 복원·맞춤을 중간에 멈추게 한다 — 그래서 코드는 대상 시퀀스가 있을 때만 다시 묶는다. 어느 쪽이든 기록만 하고, 받아들여진 경우에는 그 DEFAULT 로 INSERT
   * 가 실패하는 것까지 확인한다.
   */
  @Test
  void modifyDefaultToMissingSequence() throws SQLException {
    String missing = prefix + "_X";
    String table = prefix + "_T";
    create(
        "CREATE TABLE " + table + " (ID NUMBER(19) PRIMARY KEY, V NUMBER)", "DROP TABLE " + table);
    SQLException rejected = null;
    try {
      execute("ALTER TABLE " + table + " MODIFY (ID DEFAULT " + missing + ".NEXTVAL)");
    } catch (SQLException e) {
      rejected = e;
    }
    if (rejected != null) {
      System.out.println(
          "PROBE wave5_modify_default_missing_sequence: REJECTED_AT_ALTER "
              + rejected.getErrorCode());
      return;
    }
    SQLException insert = null;
    try {
      execute("INSERT INTO " + table + " (V) VALUES (1)");
    } catch (SQLException e) {
      insert = e;
    }
    assertThat((Throwable) insert).as("없는 시퀀스 DEFAULT 로 INSERT").isNotNull();
    System.out.println(
        "PROBE wave5_modify_default_missing_sequence: ACCEPTED_AT_ALTER, INSERT "
            + insert.getErrorCode());
  }

  /**
   * (WD-234 탐침) 지역 이름 시간대는 서머타임을 반영하고({@code America/New_York} 2026-03-08 01:30 → 06:30Z, 03:30 →
   * 07:30Z), {@code FROM_TZ} 의 시간대 인자로 식({@code TO_CHAR(SYSTIMESTAMP,'TZR')})과 오프셋 문자열('+09:00') 모두
   * 받는다.
   */
  @Test
  void regionZoneAppliesDstAndFromTzAcceptsExpression() throws SQLException {
    assertThat(
            scalar(
                "SELECT TO_CHAR(SYS_EXTRACT_UTC(FROM_TZ(TIMESTAMP '2026-03-08 01:30:00',"
                    + " 'America/New_York')), 'YYYY-MM-DD HH24:MI:SS') FROM DUAL"))
        .isEqualTo("2026-03-08 06:30:00");
    assertThat(
            scalar(
                "SELECT TO_CHAR(SYS_EXTRACT_UTC(FROM_TZ(TIMESTAMP '2026-03-08 03:30:00',"
                    + " 'America/New_York')), 'YYYY-MM-DD HH24:MI:SS') FROM DUAL"))
        .isEqualTo("2026-03-08 07:30:00");
    assertThat(
            scalar(
                "SELECT TO_CHAR(SYS_EXTRACT_UTC(FROM_TZ(TIMESTAMP '2026-03-08 03:30:00', '+09:00')),"
                    + " 'YYYY-MM-DD HH24:MI:SS') FROM DUAL"))
        .isEqualTo("2026-03-07 18:30:00");
    // 이 서버는 UTC(Etc/Universal)라 식 결과는 입력과 같다.
    System.out.println(
        "PROBE wd234_from_tz_tzr: "
            + scalar(
                "SELECT TO_CHAR(SYS_EXTRACT_UTC(FROM_TZ(TIMESTAMP '2026-03-08 03:30:00',"
                    + " TO_CHAR(SYSTIMESTAMP, 'TZR'))), 'YYYY-MM-DD HH24:MI:SS') FROM DUAL"));
  }

  /**
   * (WD-234 결함 재현 — <b>공유 서버에서 절대 켜지 말 것</b>) 색인 있는 TSTZ 열을 {@code 열 AT TIME ZONE ...} 로 한 번 조회하면,
   * 그 뒤 그 열을 바꾸는 모든 UPDATE(트리거 대입 포함)가 서버 쪽에서 세션을 끊는다(JDBC-90405 End Of Stream). 새 접속에서도 계속되고 색인을
   * 다시 만들어야 풀린다. INSERT·DELETE·다른 열 UPDATE 는 된다. 색인 없는 TSTZ·색인 있는 옛 TIMESTAMP·TSTZ 리터럴 비교·JDBC
   * 읽기·{@code SYS_EXTRACT_UTC} 조회 뒤에는 끊기지 않는다(2026-10-27 실서버 확인). 켜면 세션을 하나 죽인다 — 기록용으로만 둔다.
   */
  @Disabled("공유 서버 세션을 끊음 — Tibero 결함 재현 기록용, 켜지 말 것")
  @Test
  void tstzIndexAtTimeZoneSelectBreaksUpdate() throws SQLException {
    String table = prefix + "_X";
    create(
        "CREATE TABLE "
            + table
            + " (ID NUMBER, \"_UPDATED_AT\" TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL)",
        "DROP TABLE " + table);
    create(
        "CREATE INDEX " + prefix + "_XI ON " + table + " (\"_UPDATED_AT\")",
        "DROP INDEX " + prefix + "_XI");
    execute("INSERT INTO " + table + " (ID) VALUES (1)");
    scalar(
        "SELECT TO_CHAR(\"_UPDATED_AT\" AT TIME ZONE '+00:00', 'YYYY-MM-DD HH24:MI:SS') FROM "
            + table);
    assertThatThrownBy(() -> execute("UPDATE " + table + " SET \"_UPDATED_AT\" = SYSTIMESTAMP"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("JDBC-90405");
  }
}
