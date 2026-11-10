package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 세션 수 함수 소유자의 통계 열람 권한 기동 점검(WD-713) — 소유자가 superuser 면 경고가 없고, 통계 권한 없는 롤이면 롤 이름·조치를 담은 WARN 한 줄,
 * 점검 질의가 실패해도 예외 없이 WARN 한 줄만 남는지 실제 데이터 저장소(Testcontainers)로 확인한다. 점검은 운영처럼 런타임 롤(app_tenant) 연결로
 * 한다.
 */
class PythonReadOwnerStatsCheckTest extends IntegrationTestBase {

  @Autowired @DataStoreDb private DSLContext dataDsl;

  private final Logger logger = (Logger) LoggerFactory.getLogger(PythonReadUnconfirmedMarks.class);
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attach() {
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  /** 데이터 저장소 DB 의 슈퍼유저(app) 연결 — 함수 소유자를 바꾸는 데만 쓴다(두 컨테이너 자격증명은 같다). */
  private static Connection superuser() throws SQLException {
    return DriverManager.getConnection(PostgresTestContainer.dataStoreJdbcUrl(), "app", "app");
  }

  private long warnCount() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
  }

  @Test
  void ownerIsSuperuser_noWarning() {
    PythonReadUnconfirmedMarks.OwnerStatsPrivilege owner =
        PythonReadUnconfirmedMarks.sessionCountOwner(dataDsl);
    assertThat(owner).as("런타임 롤로도 소유자를 읽는다").isNotNull();
    assertThat(owner.superuser()).as("전제: 테스트 Flyway 계정(app)은 superuser").isTrue();

    assertThat(PythonReadUnconfirmedMarks.warnIfOwnerCannotReadStats(dataDsl)).isNull();
    assertThat(warnCount()).isZero();
  }

  /**
   * 소유자를 통계 권한 없는 롤로 잠시 바꾸면 경고 한 줄 — 롤 이름과 GRANT 조치가 들어간다. 끝나면 원래 소유자로 되돌리고 롤을 지운다(같은 JVM 의 다른 테스트가
   * 함수를 쓴다).
   */
  @Test
  void ownerWithoutStatsPrivilege_warnsOnceWithRoleAndRemedy() throws Exception {
    String role = "wd713_no_stats_" + System.nanoTime();
    try (Connection su = superuser();
        Statement st = su.createStatement()) {
      String original;
      try (ResultSet rs =
          st.executeQuery(
              "select p.proowner::regrole::text from pg_proc p where p.oid = to_regprocedure('"
                  + PythonReadUnconfirmedMarks.SESSION_COUNT_FUNCTION
                  + "')")) {
        assertThat(rs.next()).isTrue();
        original = rs.getString(1);
      }
      st.execute("create role " + role + " nologin");
      try {
        st.execute(
            "alter function "
                + PythonReadUnconfirmedMarks.SESSION_COUNT_FUNCTION
                + " owner to "
                + role);

        String msg = PythonReadUnconfirmedMarks.warnIfOwnerCannotReadStats(dataDsl);

        assertThat(msg).contains(role).contains("GRANT pg_read_all_stats TO " + role);
        assertThat(warnCount()).isEqualTo(1);
      } finally {
        st.execute(
            "alter function "
                + PythonReadUnconfirmedMarks.SESSION_COUNT_FUNCTION
                + " owner to "
                + original);
        st.execute("drop role " + role);
      }
    }
  }

  /** pg_read_all_stats 를 받은 롤이면(superuser 아님) 경고가 없다 — USAGE 로 보므로 상속되는 일반 부여는 통과. */
  @Test
  void ownerWithReadAllStats_noWarning() throws Exception {
    String role = "wd713_stats_" + System.nanoTime();
    try (Connection su = superuser();
        Statement st = su.createStatement()) {
      String original;
      try (ResultSet rs =
          st.executeQuery(
              "select p.proowner::regrole::text from pg_proc p where p.oid = to_regprocedure('"
                  + PythonReadUnconfirmedMarks.SESSION_COUNT_FUNCTION
                  + "')")) {
        assertThat(rs.next()).isTrue();
        original = rs.getString(1);
      }
      st.execute("create role " + role + " nologin");
      st.execute("grant pg_read_all_stats to " + role);
      try {
        st.execute(
            "alter function "
                + PythonReadUnconfirmedMarks.SESSION_COUNT_FUNCTION
                + " owner to "
                + role);

        assertThat(PythonReadUnconfirmedMarks.warnIfOwnerCannotReadStats(dataDsl)).isNull();
        assertThat(warnCount()).isZero();
      } finally {
        st.execute(
            "alter function "
                + PythonReadUnconfirmedMarks.SESSION_COUNT_FUNCTION
                + " owner to "
                + original);
        st.execute("drop role " + role);
      }
    }
  }

  /** 점검 질의가 실패해도(데이터 저장소 접속 불가) 던지지 않고 WARN 한 줄 — 기동을 막지 않는다. */
  @Test
  void queryFailure_warnsWithoutThrowing() throws Exception {
    DataSource broken = mock(DataSource.class);
    when(broken.getConnection()).thenThrow(new SQLException("접속 불가 흉내", "08006"));

    String msg =
        PythonReadUnconfirmedMarks.warnIfOwnerCannotReadStats(
            DSL.using(broken, SQLDialect.POSTGRES));

    assertThat(msg).contains("점검하지 못했다");
    assertThat(warnCount()).isEqualTo(1);
  }

  /** 판정 — superuser 나 통계 권한이 있으면 문구 없음, 둘 다 없으면 롤 이름 포함, 함수가 없으면 점검 불가 문구. */
  @Test
  void decision_onlyWarnsWhenNeitherSuperuserNorStats() {
    assertThat(
            PythonReadUnconfirmedMarks.ownerStatsWarning(
                new PythonReadUnconfirmedMarks.OwnerStatsPrivilege("app", true, false)))
        .isNull();
    assertThat(
            PythonReadUnconfirmedMarks.ownerStatsWarning(
                new PythonReadUnconfirmedMarks.OwnerStatsPrivilege("ops", false, true)))
        .isNull();
    assertThat(
            PythonReadUnconfirmedMarks.ownerStatsWarning(
                new PythonReadUnconfirmedMarks.OwnerStatsPrivilege("flyway_u", false, false)))
        .contains("flyway_u")
        .contains("GRANT pg_read_all_stats TO flyway_u");
    assertThat(PythonReadUnconfirmedMarks.ownerStatsWarning(null)).contains("없어");
  }
}
