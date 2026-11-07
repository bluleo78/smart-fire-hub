package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link TiberoPlatformLocks} 단위 테스트(WD-493) — 스프링 없이 id 매핑(스키마 이름공간 포함)·반환값 판정·기동 권한 확인·트랜잭션 묶임
 * 가드를 본다.
 *
 * <p>실제 DBMS_LOCK 동작(대기·재진입·커밋 해제)은 실서버 테스트 {@code TiberoPlatformLocksLiveTest} 가 본다.
 */
class TiberoPlatformLocksTest {

  private static final String SCHEMA = "FH_APP";

  /**
   * 정수 키는 앞쪽 100만 칸(MIN_ID..MIN_ID+999,999)에 든다 — 같은 스키마에서는 칸 안 자리가 다르면 id 도 다르고, 칸 크기만큼 떨어지면 같다.
   */
  @Test
  void longKeysMapIntoLeadingSpace() {
    int base = TiberoPlatformLocks.lockId(SCHEMA, 0L);
    assertThat(base).isBetween(1_073_741_824, 1_074_741_823);
    // 같은 스키마 안에서 서로 다른 자리 → 서로 다른 id(칸 안에서 돌린 것뿐이라 겹치지 않는다).
    assertThat(TiberoPlatformLocks.lockId(SCHEMA, 1L)).isNotEqualTo(base);
    assertThat(TiberoPlatformLocks.lockId(SCHEMA, 999_999L)).isNotEqualTo(base);
    assertThat(TiberoPlatformLocks.lockId(SCHEMA, 1_000_000L)).isEqualTo(base);
    assertThat(TiberoPlatformLocks.lockId(SCHEMA, -1L))
        .isEqualTo(TiberoPlatformLocks.lockId(SCHEMA, 999_999L));
    for (long k : new long[] {0L, 1L, -1L, 999_999L, Long.MIN_VALUE, Long.MAX_VALUE}) {
      assertThat(TiberoPlatformLocks.lockId(SCHEMA, k)).isBetween(1_073_741_824, 1_074_741_823);
    }
    // 연속한 정수 키는 같은 스키마 안에서 서로 겹치지 않는다(돌린 칸이라 단사).
    java.util.Set<Integer> ids = new java.util.HashSet<>();
    for (long k = 0; k < 1_000; k++) {
      ids.add(TiberoPlatformLocks.lockId(SCHEMA, k));
    }
    assertThat(ids).hasSize(1_000);
  }

  /** 문자열 키는 정수 칸 뒤의 나머지 공간에 결정적으로 들어간다(JVM·인스턴스가 달라도 같은 스키마·키면 같은 id). */
  @Test
  void stringKeysMapIntoRemainingSpaceDeterministically() {
    for (String k : List.of("", "a", "python_read_sync_t7", "출력", "x".repeat(10_000))) {
      int id = TiberoPlatformLocks.lockId(SCHEMA, k);
      assertThat(id).isBetween(1_074_741_824, 1_999_999_999);
      assertThat(TiberoPlatformLocks.lockId(SCHEMA, k)).isEqualTo(id);
    }
    assertThat(TiberoPlatformLocks.lockId(SCHEMA, "a"))
        .isNotEqualTo(TiberoPlatformLocks.lockId(SCHEMA, "b"));
  }

  /** 인스턴스 전역 잠금이라 스키마로 이름공간을 나눈다 — 같은 키라도 스키마가 다르면 다른 id(정수·문자열 모두). */
  @Test
  void sameKeyDifferentSchemaGivesDifferentId() {
    for (String k : List.of("python_read_sync_t7", "member-admin-guard:1", "5")) {
      assertThat(TiberoPlatformLocks.lockId("FH_APP", k))
          .isNotEqualTo(TiberoPlatformLocks.lockId("FHT_LOCKS_ABC123", k));
    }
    assertThat(TiberoPlatformLocks.lockId("FH_APP", 1L))
        .isNotEqualTo(TiberoPlatformLocks.lockId("FHT_LOCKS_ABC123", 1L));
    // 다른 스키마에서도 범위는 지킨다.
    assertThat(TiberoPlatformLocks.lockId("FHT_LOCKS_ABC123", 1L))
        .isBetween(1_073_741_824, 1_074_741_823);
    assertThat(TiberoPlatformLocks.lockId("FHT_LOCKS_ABC123", "k"))
        .isBetween(1_074_741_824, 1_999_999_999);
  }

  /** 트랜잭션 잠금 반환값: 0·4 통과, 1(시간 초과)·2(교착) 잠금 실패, 그 밖은 잘못된 상태. */
  @Test
  void xactResultMapping() {
    assertThatCode(() -> TiberoPlatformLocks.checkXactResult(0)).doesNotThrowAnyException();
    assertThatCode(() -> TiberoPlatformLocks.checkXactResult(4)).doesNotThrowAnyException();
    for (int code : new int[] {1, 2}) {
      assertThatThrownBy(() -> TiberoPlatformLocks.checkXactResult(code))
          .isInstanceOf(CannotAcquireLockException.class)
          .hasMessageContaining(Integer.toString(code));
    }
    for (int code : new int[] {3, 5, 99}) {
      assertThatThrownBy(() -> TiberoPlatformLocks.checkXactResult(code))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(Integer.toString(code));
    }
  }

  /** 세션 잠금 시도 반환값: 0·4 잡음, 1 다른 세션이 쥠, 그 밖은 잘못된 상태. */
  @Test
  void tryResultMapping() {
    assertThat(TiberoPlatformLocks.checkTryResult(0)).isTrue();
    assertThat(TiberoPlatformLocks.checkTryResult(4)).isTrue();
    assertThat(TiberoPlatformLocks.checkTryResult(1)).isFalse();
    assertThatThrownBy(() -> TiberoPlatformLocks.checkTryResult(3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3");
  }

  /** 해제 반환값: 0·4(쥐지 않음) 통과, 3·5 는 SQLException. */
  @Test
  void releaseResultMapping() {
    assertThatCode(() -> TiberoPlatformLocks.checkReleaseResult(0)).doesNotThrowAnyException();
    assertThatCode(() -> TiberoPlatformLocks.checkReleaseResult(4)).doesNotThrowAnyException();
    for (int code : new int[] {3, 5}) {
      assertThatThrownBy(() -> TiberoPlatformLocks.checkReleaseResult(code))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining(Integer.toString(code));
    }
  }

  /** 확인 키를 다른 인스턴스가 쥐고 있으면(반환 1) 권한은 있는 것 — 예외 없이 끝나고, 쥐지 않았으니 해제하지 않는다. */
  @Test
  void startupCheckTreatsBusyProbeKeyAsGranted() throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    CallableStatement request = mock(CallableStatement.class);
    CallableStatement release = mock(CallableStatement.class);
    when(ds.getConnection()).thenReturn(c);
    stubSchema(c, SCHEMA);
    when(c.prepareCall(contains("DBMS_LOCK.REQUEST"))).thenReturn(request);
    when(c.prepareCall(contains("DBMS_LOCK.RELEASE"))).thenReturn(release);
    when(request.getInt(anyInt())).thenReturn(1);

    assertThatCode(() -> new TiberoPlatformLocks(ds).afterPropertiesSet())
        .doesNotThrowAnyException();

    verify(c).setAutoCommit(true);
    verify(c, never()).prepareCall(contains("DBMS_LOCK.RELEASE"));
    verify(c).close();
  }

  /** 풀 세션이 확인 키를 이미 쥐고 있으면(반환 4) 권한은 있고, 세션에 남기지 않도록 해제한다. */
  @Test
  void startupCheckReleasesProbeKeyAlreadyHeldBySession() throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    CallableStatement request = mock(CallableStatement.class);
    CallableStatement release = mock(CallableStatement.class);
    when(ds.getConnection()).thenReturn(c);
    stubSchema(c, SCHEMA);
    when(c.prepareCall(contains("DBMS_LOCK.REQUEST"))).thenReturn(request);
    when(c.prepareCall(contains("DBMS_LOCK.RELEASE"))).thenReturn(release);
    when(request.getInt(anyInt())).thenReturn(4);
    when(release.getInt(anyInt())).thenReturn(0);

    assertThatCode(() -> new TiberoPlatformLocks(ds).afterPropertiesSet())
        .doesNotThrowAnyException();

    verify(c).prepareCall(contains("DBMS_LOCK.RELEASE"));
    verify(release).execute();
    verify(c).close();
  }

  /** DBMS_LOCK 실행 권한이 없으면(오류 -15146) 운영자가 할 일을 담은 안내로 기동을 멈춘다. */
  @Test
  void startupCheckStopsWithGuidanceWhenGrantMissing() throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection c = mock(Connection.class);
    CallableStatement request = mock(CallableStatement.class);
    when(ds.getConnection()).thenReturn(c);
    stubSchema(c, SCHEMA);
    when(c.prepareCall(anyString())).thenReturn(request);
    when(request.execute()).thenThrow(new SQLException("권한 없음", "42000", -15146));

    assertThatThrownBy(() -> new TiberoPlatformLocks(ds).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(TiberoPlatformLocks.MISSING_GRANT_MESSAGE);
  }

  /** 현재 스키마가 비어 오면 이름공간 없이 잠그지 않도록 예외를 던진다. */
  @Test
  void currentSchemaRejectsBlank() throws Exception {
    Connection c = mock(Connection.class);
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery(TiberoPlatformLocks.CURRENT_SCHEMA_SQL)).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(" ");

    assertThatThrownBy(() -> TiberoPlatformLocks.currentSchema(c)).isInstanceOf(SQLException.class);
  }

  /** 초기화(afterPropertiesSet) 전에는 이름공간 없는 id 로 잠그지 않고 바로 실패한다. */
  @Test
  void lockingBeforeInitFailsLoudly() {
    var locks = new TiberoPlatformLocks(mock(DataSource.class));
    Connection c = mock(Connection.class);
    assertThatThrownBy(() -> locks.trySessionLock(c, "k"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("초기화");
    assertThatThrownBy(() -> locks.xactLock(1L)).isInstanceOf(IllegalStateException.class);
  }

  /** 트랜잭션 동기화는 켜져 있지만 이 플랫폼 DataSource 가 트랜잭션에 묶이지 않았으면 거절한다 — 그대로 두면 자동 커밋 새 커넥션에서 잠겨 곧바로 풀린다. */
  @Test
  void xactLockRejectsActiveSynchronizationWithoutBoundDataSource() throws Exception {
    DataSource ds = mock(DataSource.class);
    var locks = initialized(ds);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> locks.xactLock("k"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("트랜잭션 안에서만")
          .hasMessageNotContaining("k)");
      assertThatThrownBy(() -> locks.xactLock(7L)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> locks.tryXactLock("k", Duration.ofMillis(10)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("트랜잭션 안에서만");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * 시도형 트랜잭션 잠금(WD-624)은 호출자 트랜잭션 커넥션에서 커밋 해제형·기다리지 않는(timeout 0) 요청을 되풀이한다 — 다른 세션이 쥐고 있으면(1) 다시
   * 시도하고, 잡히면(0) true.
   */
  @Test
  void tryXactLockRetriesUntilAcquired() throws Exception {
    DataSource ds = mock(DataSource.class);
    var locks = initialized(ds);
    Connection txConn = mock(Connection.class);
    CallableStatement request = mock(CallableStatement.class);
    when(txConn.prepareCall(contains("release_on_commit => TRUE"))).thenReturn(request);
    when(request.getInt(3)).thenReturn(1, 1, 0);
    TransactionSynchronizationManager.bindResource(ds, new ConnectionHolder(txConn));
    try {
      assertThat(locks.tryXactLock("k", Duration.ofSeconds(5))).isTrue();
      verify(request, times(3)).setInt(2, 0);
      verify(txConn, never()).prepareCall(contains("release_on_commit => FALSE"));
    } finally {
      TransactionSynchronizationManager.unbindResource(ds);
    }
  }

  /** 상한 안에 못 잡으면 예외 없이 false — 호출자 트랜잭션은 그대로 쓸 수 있어야 한다. */
  @Test
  void tryXactLockReturnsFalseAfterMaxWait() throws Exception {
    DataSource ds = mock(DataSource.class);
    var locks = initialized(ds);
    Connection txConn = mock(Connection.class);
    CallableStatement request = mock(CallableStatement.class);
    when(txConn.prepareCall(contains("release_on_commit => TRUE"))).thenReturn(request);
    when(request.getInt(3)).thenReturn(1);
    TransactionSynchronizationManager.bindResource(ds, new ConnectionHolder(txConn));
    try {
      assertThat(locks.tryXactLock("k", Duration.ofMillis(120))).isFalse();
    } finally {
      TransactionSynchronizationManager.unbindResource(ds);
    }
  }

  /** 기동 확인을 거친(스키마를 읽은) 잠금 — 확인 키는 비어 있다고(0) 응답하게 한다. */
  private static TiberoPlatformLocks initialized(DataSource ds) throws Exception {
    Connection c = mock(Connection.class);
    CallableStatement request = mock(CallableStatement.class);
    CallableStatement release = mock(CallableStatement.class);
    when(ds.getConnection()).thenReturn(c);
    stubSchema(c, SCHEMA);
    when(c.prepareCall(contains("DBMS_LOCK.REQUEST"))).thenReturn(request);
    when(c.prepareCall(contains("DBMS_LOCK.RELEASE"))).thenReturn(release);
    when(request.getInt(anyInt())).thenReturn(0);
    when(release.getInt(anyInt())).thenReturn(0);
    var locks = new TiberoPlatformLocks(ds);
    locks.afterPropertiesSet();
    return locks;
  }

  /** 커넥션의 현재 스키마 조회(SYS_CONTEXT)가 주어진 이름을 돌려주게 한다. */
  private static void stubSchema(Connection c, String schema) throws SQLException {
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    when(c.createStatement()).thenReturn(st);
    when(st.executeQuery(TiberoPlatformLocks.CURRENT_SCHEMA_SQL)).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(schema);
  }
}
