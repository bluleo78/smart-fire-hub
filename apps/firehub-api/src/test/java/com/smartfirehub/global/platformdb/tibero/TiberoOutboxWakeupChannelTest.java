package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.tibero.TiberoOutboxWakeupChannel.ProbeResult;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link TiberoOutboxWakeupChannel} 단위 테스트 — DB 없이 대기 시간 환산, 스키마별 알림 이름, 발행 실패 삼킴, 구독 정리(WAITONE 한
 * 번 준비)를 확인한다.
 */
class TiberoOutboxWakeupChannelTest {

  private static final String REGISTER_SQL = "BEGIN DBMS_ALERT.REGISTER(?); END;";
  private static final String REMOVE_SQL = TiberoOutboxWakeupChannel.REMOVE_SQL;

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

  private static SQLException sqlErr(int code) {
    return new SQLException("x", "x", code);
  }

  /** 기동 점검(WD-691) — 점검용 REMOVE 가 주어진 예외(없으면 성공)를 내는 기본 풀의 채널. 점검까지 마친 상태로 돌려준다. */
  private static TiberoOutboxWakeupChannel checkedChannel(Exception removeFailure)
      throws Exception {
    return checkedChannel(schemaDataSource("FH_APP"), () -> mock(Connection.class), removeFailure);
  }

  private static TiberoOutboxWakeupChannel checkedChannel(
      DataSource ds, Callable<Connection> listenConn, Exception removeFailure) throws Exception {
    Connection c = ds.getConnection();
    CallableStatement remove = mock(CallableStatement.class);
    when(c.prepareCall(REMOVE_SQL)).thenReturn(remove);
    if (removeFailure != null) {
      when(remove.execute()).thenThrow(removeFailure);
    }
    var channel = new TiberoOutboxWakeupChannel(ds, listenConn);
    channel.afterPropertiesSet();
    return channel;
  }

  static Stream<Arguments> 점검_사례() {
    return Stream.of(
        Arguments.of("오류 없음", null, ProbeResult.OK, true),
        Arguments.of("등록 없음 -14207(실측: 권한은 있음)", sqlErr(-14207), ProbeResult.OK, true),
        Arguments.of("권한 없음 -15146", sqlErr(-15146), ProbeResult.NO_GRANT, false),
        Arguments.of("권한 없음 양수 15146", sqlErr(15146), ProbeResult.NO_GRANT, false),
        Arguments.of("그 밖의 오류 -12345", sqlErr(-12345), ProbeResult.FAILED, true),
        Arguments.of("런타임 예외", new IllegalStateException("x"), ProbeResult.FAILED, true));
  }

  @ParameterizedTest(name = "{0} → {2}, 채널 켜짐={3}")
  @MethodSource("점검_사례")
  void 기동_점검_결과에_따라_채널을_켜고_끈다(String 사례, Exception 실패, ProbeResult 기대, boolean 켜짐)
      throws Exception {
    var channel = checkedChannel(실패);

    assertThat(channel.probe()).isEqualTo(기대);
    assertThat(channel.enabled()).isEqualTo(켜짐);
  }

  @Test
  void 꺼진_채널의_발행은_DB에_닿지_않는다() throws Exception {
    DataSource ds = schemaDataSource("FH_APP");
    var channel = checkedChannel(ds, () -> mock(Connection.class), sqlErr(-15146));

    clearInvocations(ds);
    channel.publish();
    verify(ds, never()).getConnection();
  }

  /** 꺼진 채널의 listen — DB 에 붙지 않고, onSignal 도 부르지 않으며, pollTimeout 0 이어도 1초(1000ms)를 요청한다. */
  @Test
  void 꺼진_채널의_listen은_DB에_붙지_않고_최소_1초씩_쉰다() throws Exception {
    var listenCalls = new AtomicInteger();
    var channel =
        checkedChannel(
            schemaDataSource("FH_APP"),
            () -> {
              listenCalls.incrementAndGet();
              return mock(Connection.class);
            },
            sqlErr(-15146));
    var slept = new ArrayList<Long>();
    var running = new AtomicBoolean(true);
    channel.sleeper =
        millis -> {
          slept.add(millis);
          running.set(false);
        };
    var signals = new AtomicInteger();

    channel.listen(running::get, Duration.ZERO, signals::incrementAndGet);

    assertThat(slept).containsExactly(1000L);
    assertThat(listenCalls.get()).isZero();
    assertThat(signals.get()).isZero();
  }

  @Test
  void 꺼진_채널의_listen은_인터럽트되면_플래그를_세우고_돌아온다() throws Exception {
    var channel = checkedChannel(sqlErr(-15146));
    channel.sleeper =
        millis -> {
          throw new InterruptedException();
        };
    try {
      channel.listen(() -> true, Duration.ofSeconds(30), () -> {});
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // 다른 테스트에 플래그를 남기지 않는다
    }
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
