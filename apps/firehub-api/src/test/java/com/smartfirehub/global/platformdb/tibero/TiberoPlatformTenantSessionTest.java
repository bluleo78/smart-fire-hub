package com.smartfirehub.global.platformdb.tibero;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TiberoPlatformTenantSession} 단위 시험(WD-492) — 호출 문장과 「현재 트랜잭션」 가드를 목으로 고정한다. 실서버 동작은 {@code
 * TiberoPlatformTenantSessionLiveTest} 가 본다.
 */
class TiberoPlatformTenantSessionTest {

  private DataSource platform;
  private Connection con;
  private CallableStatement cs;
  private TiberoPlatformTenantSession session;

  @BeforeEach
  void setUp() throws SQLException {
    platform = mock(DataSource.class);
    con = mock(Connection.class);
    cs = mock(CallableStatement.class);
    when(platform.getConnection()).thenReturn(con);
    when(con.prepareCall(TiberoPlatformTenantSession.SET_TENANT_CALL)).thenReturn(cs);
    when(con.prepareCall(TiberoPlatformTenantSession.CLEAR_TENANT_CALL)).thenReturn(cs);
    session = new TiberoPlatformTenantSession(platform);
  }

  @Test
  void 커넥션에_패키지로_테넌트를_심는다() throws SQLException {
    session.applyToConnection(con, 3L);

    verify(con).prepareCall("{call FH_TENANT.set_tenant(?)}");
    // 멈춘 네트워크에서 끝없이 기다리지 않게 타임아웃을 건다.
    verify(cs).setQueryTimeout(10);
    verify(cs).setLong(1, 3L);
    verify(cs).execute();
    verify(cs).close();
  }

  @Test
  void 지우기는_clear_tenant_를_부른다() throws SQLException {
    session.clearConnection(con);

    verify(con).prepareCall("{call FH_TENANT.clear_tenant}");
    // 정리 경로가 멈춘 세션에 묶여 스레드·풀을 잡지 않게 타임아웃을 건다.
    verify(cs).setQueryTimeout(10);
    verify(cs).execute();
    verify(cs).close();
  }

  @Test
  void 트랜잭션_밖에서_현재_트랜잭션에_심으면_거부한다() throws SQLException {
    assertThatThrownBy(() -> session.applyToCurrentTransaction(1L))
        .isInstanceOf(IllegalStateException.class);
    verify(platform, never()).getConnection();
  }

  @Test
  void 다른_DataSource_의_트랜잭션_안이어도_거부한다() throws SQLException {
    // 다른 풀의 트랜잭션이면 isActualTransactionActive() 는 참이지만, 플랫폼 풀 커넥션은 그 트랜잭션에 묶이지 않아 지워 줄 자리가 없다.
    DataSource other = mock(DataSource.class);
    when(other.getConnection()).thenReturn(mock(Connection.class));
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(other));

    tx.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> session.applyToCurrentTransaction(1L))
                .isInstanceOf(IllegalStateException.class));
    verify(platform, never()).getConnection();
  }

  @Test
  void 플랫폼_트랜잭션_안에서는_묶인_커넥션에_심는다() throws SQLException {
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(platform));

    tx.executeWithoutResult(status -> session.applyToCurrentTransaction(5L));

    verify(cs).setLong(1, 5L);
    verify(cs).execute();
  }

  @Test
  void 심기에_실패하면_자원_실패로_감싼다() throws SQLException {
    when(cs.execute()).thenThrow(new SQLException("set 실패"));
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(platform));

    tx.executeWithoutResult(
        status ->
            assertThatThrownBy(() -> session.applyToCurrentTransaction(5L))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCauseInstanceOf(SQLException.class));
  }
}
