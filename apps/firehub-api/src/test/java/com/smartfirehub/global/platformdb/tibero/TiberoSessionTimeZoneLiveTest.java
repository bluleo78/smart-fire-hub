package com.smartfirehub.global.platformdb.tibero;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestAccount;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Tibero 세션 시간대와 {@code LOCALTIMESTAMP} 의 관계 실측(WD-729).
 *
 * <p>왜: 기준선 스키마의 {@code TIMESTAMP DEFAULT LOCALTIMESTAMP} 열 57개는 세션 시간대를 따라 쌓인다. 세션 시간대를 UTC 로 고정하지
 * 않으면 DB 서버 시간대가 UTC 가 아닐 때 기본값이 UTC 벽시계가 아니게 된다. 이 시험은 {@code ALTER SESSION SET TIME_ZONE} 이
 * {@code LOCALTIMESTAMP} 에 실제로 반영되는지 본다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = SECONDS)
class TiberoSessionTimeZoneLiveTest {

  /** LOCALTIMESTAMP 와 UTC 현재 시각의 차이(초). 세션 시간대가 UTC 면 0 근처, +09:00 이면 32400 근처. */
  private static final String OFFSET_SECONDS_SQL =
      "SELECT (CAST(LOCALTIMESTAMP AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 86400"
          + " FROM DUAL";

  private TiberoTestAccount account;

  @BeforeAll
  void setUp() {
    account = TiberoTestAccount.create("TZ", Privileges.TABLES, 2);
  }

  @AfterAll
  void tearDown() {
    if (account != null) account.close();
  }

  private static double offsetSeconds(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(OFFSET_SECONDS_SQL)) {
      rs.next();
      return rs.getDouble(1);
    }
  }

  @Test
  @DisplayName("ALTER SESSION SET TIME_ZONE 이 LOCALTIMESTAMP 에 반영된다")
  void sessionTimeZoneAffectsLocalTimestamp() throws Exception {
    try (HikariDataSource pool = account.newPool(1, TiberoDialect.SESSION_INIT_SQL);
        Connection c = pool.getConnection();
        Statement st = c.createStatement()) {
      double before = offsetSeconds(c);
      st.execute("ALTER SESSION SET TIME_ZONE = '+09:00'");
      double plus9 = offsetSeconds(c);
      st.execute("ALTER SESSION SET TIME_ZONE = '+00:00'");
      double utc = offsetSeconds(c);
      TiberoTestEvidence.write(
          "TZ 실측: 기본 세션 차이 " + before + "초, +09:00 후 " + plus9 + "초, +00:00 후 " + utc + "초");
      assertThat(plus9).isBetween(32400.0 - 5, 32400.0 + 5);
      assertThat(Math.abs(utc)).isLessThan(5);
    }
  }

  /**
   * 운영 init SQL({@code PLATFORM_SESSION_INIT_SQL} — yml 값과 같다는 것은 TiberoProfileSettingsTest 가 대조)이
   * 시간대를 실제로 UTC 로 되돌린다. 검증 서버 기본 세션이 이미 UTC 라 새 연결만 보는 시험은 문장이 빠져도 통과하므로, 같은 연결을 먼저 +09:00 으로 어긋나게
   * 한 뒤 운영 문장을 실행하고 차이가 0 근처로 돌아오는지 본다.
   */
  @Test
  @DisplayName("운영 init SQL 로 연 연결은 LOCALTIMESTAMP 가 UTC 와 같다")
  void productionInitSqlRestoresUtc() throws Exception {
    try (HikariDataSource pool = account.newPool(1, TiberoDialect.SESSION_INIT_SQL);
        Connection c = pool.getConnection();
        Statement st = c.createStatement()) {
      st.execute("ALTER SESSION SET TIME_ZONE = '+09:00'");
      assertThat(offsetSeconds(c)).isBetween(32400.0 - 5, 32400.0 + 5);
      st.execute(TiberoDialect.PLATFORM_SESSION_INIT_SQL);
      double diff = offsetSeconds(c);
      TiberoTestEvidence.write("TZ 운영 init SQL 적용 뒤 차이 " + diff + "초");
      assertThat(Math.abs(diff)).isLessThan(5);
    }
  }

  /** 하네스 계정 풀(운영과 같은 init SQL)로 연 연결은 곧바로 UTC 다 — 하네스가 운영 값을 쓴다는 확인. */
  @Test
  @DisplayName("하네스 계정 풀 연결은 세션 시간대가 UTC 다")
  void harnessPoolConnectionIsUtc() throws Exception {
    try (Connection c = account.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT SESSIONTIMEZONE FROM DUAL")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("+00:00");
      assertThat(Math.abs(offsetSeconds(c))).isLessThan(5);
    }
  }
}
