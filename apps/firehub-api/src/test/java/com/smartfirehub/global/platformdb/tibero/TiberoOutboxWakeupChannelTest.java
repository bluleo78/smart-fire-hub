package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * {@link TiberoOutboxWakeupChannel} 단위 테스트 — DB 없이 대기 시간 환산, 스키마별 알림 이름, 발행 실패 삼킴, 구독 정리(WAITONE 한
 * 번 준비)를 확인한다.
 */
class TiberoOutboxWakeupChannelTest {

  private static final String REGISTER_SQL = "BEGIN DBMS_ALERT.REGISTER(?); END;";
  private static final String REMOVE_SQL = "BEGIN DBMS_ALERT.REMOVE(?); END;";

  @Test
  void waitSecondsRoundsUpWithMinimumOneAndClampsToInt() {
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ZERO)).isEqualTo(1);
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ofMillis(1))).isEqualTo(1);
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ofMillis(1000))).isEqualTo(1);
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ofMillis(1001))).isEqualTo(2);
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ofSeconds(30))).isEqualTo(30);
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ofMillis(-5))).isEqualTo(1);
    // int 인자로 넘기므로 넘치지 않게 자른다.
    assertThat(TiberoOutboxWakeupChannel.waitSeconds(Duration.ofDays(100_000)))
        .isEqualTo(Integer.MAX_VALUE);
    assertThat(
            TiberoOutboxWakeupChannel.waitSeconds(Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)))
        .isEqualTo(Integer.MAX_VALUE);
  }

  /** 알림 이름은 인스턴스 전역이라 스키마별로 나눈다 — 결정적이고, 스키마가 다르면 다르고, 30자 안이다. */
  @Test
  void alertNameIsNamespacedBySchema() {
    String a = TiberoOutboxWakeupChannel.alertNameFor("FH_APP");
    assertThat(a)
        .startsWith(TiberoOutboxWakeupChannel.ALERT_NAME_PREFIX)
        .matches("FH_OUTBOX_NEW_[0-9A-F]{8}");
    assertThat(a.length()).isLessThanOrEqualTo(TiberoOutboxWakeupChannel.MAX_ALERT_NAME_LENGTH);
    assertThat(TiberoOutboxWakeupChannel.alertNameFor("FH_APP")).isEqualTo(a);
    assertThat(TiberoOutboxWakeupChannel.alertNameFor("FHT_ALERT_ABC123")).isNotEqualTo(a);
  }

  /** 알림 이름은 풀 커넥션에서 한 번만 정한다 — 두 번째 호출은 DB 를 다시 보지 않는다. */
  @Test
  void alertNameResolvedOnceFromPool() throws Exception {
    DataSource ds = schemaDataSource("FH_APP");
    var channel = new TiberoOutboxWakeupChannel(ds, () -> mock(Connection.class));

    assertThat(channel.alertName()).isEqualTo(TiberoOutboxWakeupChannel.alertNameFor("FH_APP"));
    assertThat(channel.alertName()).isEqualTo(TiberoOutboxWakeupChannel.alertNameFor("FH_APP"));
    verify(ds, times(1)).getConnection();
  }

  @Test
  void publishSwallowsFailures() throws Exception {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("boom"));
    var channel = new TiberoOutboxWakeupChannel(ds, () -> mock(Connection.class));

    assertThatCode(channel::publish).doesNotThrowAnyException();
  }

  @Test
  void listenRemovesRegistrationAndClosesOnExit() throws Exception {
    Connection conn = mock(Connection.class);
    CallableStatement register = mock(CallableStatement.class);
    CallableStatement wait = mock(CallableStatement.class);
    CallableStatement remove = mock(CallableStatement.class);
    when(conn.prepareCall(REGISTER_SQL)).thenReturn(register);
    when(conn.prepareCall(TiberoOutboxWakeupChannel.WAITONE_SQL)).thenReturn(wait);
    when(conn.prepareCall(REMOVE_SQL)).thenReturn(remove);
    var channel = new TiberoOutboxWakeupChannel(schemaDataSource("FH_APP"), () -> conn);

    channel.listen(() -> false, Duration.ofSeconds(1), () -> {});

    String name = TiberoOutboxWakeupChannel.alertNameFor("FH_APP");
    verify(conn).setAutoCommit(true);
    verify(register).setString(1, name);
    verify(register).execute();
    verify(remove).setString(1, name);
    verify(remove).execute();
    verify(wait).close();
    verify(conn).close();
  }

  /** WAITONE 은 루프 밖에서 한 번만 준비하고 여러 번 실행한 뒤 닫는다. 신호(0)는 onSignal, 시간 초과(1)는 계속 대기. */
  @Test
  void listenPreparesWaitOnceAndReusesIt() throws Exception {
    Connection conn = mock(Connection.class);
    CallableStatement register = mock(CallableStatement.class);
    CallableStatement wait = mock(CallableStatement.class);
    CallableStatement remove = mock(CallableStatement.class);
    when(conn.prepareCall(REGISTER_SQL)).thenReturn(register);
    when(conn.prepareCall(TiberoOutboxWakeupChannel.WAITONE_SQL)).thenReturn(wait);
    when(conn.prepareCall(REMOVE_SQL)).thenReturn(remove);
    when(wait.getInt(3)).thenReturn(1, 0, 1);
    var channel = new TiberoOutboxWakeupChannel(schemaDataSource("FH_APP"), () -> conn);
    var calls = new AtomicInteger();
    var signals = new AtomicInteger();

    channel.listen(
        () -> calls.getAndIncrement() < 3, Duration.ofSeconds(2), signals::incrementAndGet);

    verify(conn, times(1)).prepareCall(TiberoOutboxWakeupChannel.WAITONE_SQL);
    verify(wait).setInt(4, 2);
    verify(wait, times(3)).execute();
    verify(wait).close();
    assertThat(signals.get()).isEqualTo(1);
  }

  @Test
  void listenRejectsUnexpectedStatusButStillCleansUp() throws Exception {
    Connection conn = mock(Connection.class);
    CallableStatement register = mock(CallableStatement.class);
    CallableStatement wait = mock(CallableStatement.class);
    CallableStatement remove = mock(CallableStatement.class);
    when(conn.prepareCall(REGISTER_SQL)).thenReturn(register);
    when(conn.prepareCall(TiberoOutboxWakeupChannel.WAITONE_SQL)).thenReturn(wait);
    when(conn.prepareCall(REMOVE_SQL)).thenReturn(remove);
    when(wait.getInt(3)).thenReturn(7);
    var channel = new TiberoOutboxWakeupChannel(schemaDataSource("FH_APP"), () -> conn);
    var signals = new AtomicInteger();

    assertThatThrownBy(
            () -> channel.listen(() -> true, Duration.ofSeconds(1), signals::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);

    assertThat(signals.get()).isZero();
    verify(wait).close();
    verify(remove).execute();
    verify(conn).close();
  }

  /** 현재 스키마 조회(SYS_CONTEXT)에 주어진 이름을 돌려주는 풀. */
  private static DataSource schemaDataSource(String schema) throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    when(ds.getConnection()).thenReturn(c);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery(TiberoPlatformLocks.CURRENT_SCHEMA_SQL)).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(schema);
    return ds;
  }
}
