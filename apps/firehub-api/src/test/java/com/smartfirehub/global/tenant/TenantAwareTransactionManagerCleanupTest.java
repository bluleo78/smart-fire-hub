package com.smartfirehub.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformTenantSession;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.ProxyConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TenantAwareTransactionManager#doCleanupAfterCompletion} 단위 시험(WD-492) — 트랜잭션이 끝날 때 커넥션을 풀에
 * 돌려주기 전에 세션 테넌트 값을 지우는지, 지우기에 실패하면 커넥션을 풀에서 버리는지 본다.
 *
 * <p>왜: Tibero 컨텍스트는 세션 범위라 커밋·롤백 뒤에도 남는다. 지우지 않은 커넥션이 풀로 돌아가면 다음 요청이 이전 테넌트를 물려받는다. 실서버 쪽 증명은
 * {@code TiberoPlatformTenantSessionLiveTest} 가 한다 — 여기서는 순서·분기만 목으로 고정한다.
 */
class TenantAwareTransactionManagerCleanupTest {

  private PlatformTenantSession session;
  private Connection con;

  @BeforeEach
  void setUp() {
    session = mock(PlatformTenantSession.class);
    con = mock(Connection.class);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /** 목 DataSource 가 매번 같은 목 커넥션을 내주는 트랜잭션 템플릿. */
  private TransactionTemplate template(DataSource ds) throws SQLException {
    when(ds.getConnection()).thenReturn(con);
    return new TransactionTemplate(new TenantAwareTransactionManager(ds, session));
  }

  @Test
  void 트랜잭션이_끝나면_커넥션을_돌려주기_전에_지운다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));

    tx.executeWithoutResult(status -> {});

    InOrder order = inOrder(session, con);
    order.verify(con).commit();
    order.verify(session).clearConnection(con);
    order.verify(con).close();
  }

  @Test
  void 롤백해도_지운다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));

    tx.executeWithoutResult(status -> status.setRollbackOnly());

    InOrder order = inOrder(session, con);
    order.verify(con).rollback();
    order.verify(session).clearConnection(con);
    order.verify(con).close();
  }

  @Test
  void 예외로_롤백해도_지운다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      throw new IllegalStateException("업무 실패");
                    }))
        .isInstanceOf(IllegalStateException.class);

    InOrder order = inOrder(session, con);
    order.verify(session).clearConnection(con);
    order.verify(con).close();
  }

  @Test
  void 지우기에_실패하면_히카리_풀에서_버리고_예외는_던지지_않는다() throws Exception {
    // Hikari 는 자기 프록시 커넥션만 버린다 — 운영처럼 프록시 커넥션 목을 쓴다.
    con = mock(ProxyConnection.class);
    HikariDataSource ds = mock(HikariDataSource.class);
    TransactionTemplate tx = template(ds);
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    InOrder order = inOrder(session, ds, con);
    order.verify(session).clearConnection(con);
    order.verify(ds).evictConnection(con);
    order.verify(con).close();
    verify(con, never()).abort(any());
  }

  @Test
  void 히카리로_풀리는_래퍼여도_히카리_풀에서_버린다() throws Exception {
    con = mock(ProxyConnection.class);
    DataSource ds = mock(DataSource.class);
    HikariDataSource hikari = mock(HikariDataSource.class);
    when(ds.isWrapperFor(HikariDataSource.class)).thenReturn(true);
    when(ds.unwrap(HikariDataSource.class)).thenReturn(hikari);
    TransactionTemplate tx = template(ds);
    doThrow(new IllegalStateException("clear 실패")).when(session).clearConnection(con);
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    verify(hikari).evictConnection(con);
    verify(con, never()).abort(any());
  }

  @Test
  void 히카리_풀이라도_프록시가_아닌_커넥션은_끊어_버린다() throws Exception {
    // Hikari evictConnection 은 프록시가 아닌 커넥션을 조용히 무시하므로, 그 길로 보내면 커넥션이 풀로 돌아간다.
    HikariDataSource ds = mock(HikariDataSource.class);
    TransactionTemplate tx = template(ds);
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    verify(ds, never()).evictConnection(any());
    verify(con).abort(any());
  }

  @Test
  void 히카리가_아니면_커넥션을_끊어_버린다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    InOrder order = inOrder(session, con);
    order.verify(session).clearConnection(con);
    order.verify(con).abort(any());
    order.verify(con).close();
  }

  @Test
  void 버리기마저_실패해도_예외는_던지지_않고_커넥션은_돌려준다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    doThrow(new java.sql.SQLFeatureNotSupportedException("abort 없음")).when(con).abort(any());
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    verify(con).close();
  }

  @Test
  void 참여한_안쪽_트랜잭션은_지우지_않는다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));

    tx.executeWithoutResult(outer -> tx.executeWithoutResult(inner -> {}));

    // 바깥 시작에 한 번, 바깥 끝에 한 번 — 참여한 안쪽은 시작도 끝도 지우지 않는다.
    verify(session, times(2)).clearConnection(con);
  }

  @Test
  void 안쪽_REQUIRES_NEW_는_자기_커넥션만_지운다() throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection innerCon = mock(Connection.class);
    when(ds.getConnection()).thenReturn(con, innerCon);
    TenantAwareTransactionManager tm = new TenantAwareTransactionManager(ds, session);
    TransactionTemplate outer = new TransactionTemplate(tm);
    TransactionTemplate inner = new TransactionTemplate(tm);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    outer.executeWithoutResult(
        o -> {
          inner.executeWithoutResult(i -> {});
          // 안쪽이 끝난 시점: 안쪽 커넥션은 시작·끝에 지워졌고, 바깥 커넥션은 시작 때 한 번뿐이다(끝 지우기는 아직).
          try {
            verify(session, times(2)).clearConnection(innerCon);
            verify(session, times(1)).clearConnection(con);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });

    verify(session, times(2)).clearConnection(con);
  }

  @Test
  void 테넌트_심기에_실패한_doBegin_경로도_지운다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));
    doThrow(new SQLException("set 실패")).when(session).applyToConnection(con, 7L);
    TenantContext.set(7L);

    assertThatThrownBy(() -> tx.executeWithoutResult(status -> {}))
        .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);

    InOrder order = inOrder(session, con);
    order.verify(session).applyToConnection(con, 7L);
    order.verify(session).clearConnection(con);
    order.verify(con).close();
  }

  @Test
  void 테넌트_없는_트랜잭션은_시작할_때_일하기_전에_지운다() throws Exception {
    // 앞선 대여자의 끝 지우기·버리기가 모두 실패해 값이 남은 커넥션이 와도, 일은 지운 뒤에야 시작한다.
    TransactionTemplate tx = template(mock(DataSource.class));

    tx.executeWithoutResult(
        status -> {
          try {
            verify(session, times(1)).clearConnection(con);
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });

    verify(session, never()).applyToConnection(any(), anyLong());
    verify(session, times(2)).clearConnection(con);
  }

  @Test
  void 시작_지우기에_실패하면_트랜잭션을_열지_않고_커넥션을_버린다() throws Exception {
    TransactionTemplate tx = template(mock(DataSource.class));
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    AtomicBoolean ran = new AtomicBoolean();

    assertThatThrownBy(() -> tx.executeWithoutResult(status -> ran.set(true)))
        .isInstanceOf(CannotCreateTransactionException.class)
        .hasCauseInstanceOf(SQLException.class);

    assertThat(ran).as("값이 남았을 수 있는 커넥션으로 일하지 않는다").isFalse();
    // 시작 지우기 실패 → 정리 경로의 지우기도 실패 → 버림(abort) → 반납.
    InOrder order = inOrder(session, con);
    order.verify(session, times(2)).clearConnection(con);
    order.verify(con).abort(any());
    order.verify(con).close();
    assertThat(TransactionSynchronizationManager.getResourceMap())
        .as("스레드에 커넥션 바인딩이 남지 않는다")
        .isEmpty();
  }

  @Test
  void 래퍼에_싸인_히카리_커넥션도_안쪽_프록시를_풀에서_버린다() throws Exception {
    // con 은 Hikari 프록시가 아닌 래퍼이고 안쪽에 프록시가 있다 — 래퍼째 evict 하면 Hikari 가 무시해 풀로 돌아간다.
    ProxyConnection proxy = mock(ProxyConnection.class);
    when(con.isWrapperFor(ProxyConnection.class)).thenReturn(true);
    when(con.unwrap(ProxyConnection.class)).thenReturn(proxy);
    HikariDataSource ds = mock(HikariDataSource.class);
    TransactionTemplate tx = template(ds);
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    verify(ds).evictConnection(proxy);
    verify(ds, never()).evictConnection(con);
    verify(con, never()).abort(any());
    verify(con).close();
  }

  @Test
  void 래퍼에서_프록시를_꺼내다_실패하면_끊어_버린다() throws Exception {
    // 꺼내기 예외로 버리기 자체를 건너뛰면 값이 남은 커넥션이 풀로 돌아간다 — abort 로 넘어가야 한다.
    when(con.isWrapperFor(ProxyConnection.class)).thenReturn(true);
    when(con.unwrap(ProxyConnection.class)).thenThrow(new SQLException("꺼낼 수 없음"));
    HikariDataSource ds = mock(HikariDataSource.class);
    TransactionTemplate tx = template(ds);
    doThrow(new SQLException("clear 실패")).when(session).clearConnection(con);
    TenantContext.set(7L);

    assertThatCode(() -> tx.executeWithoutResult(status -> {})).doesNotThrowAnyException();

    verify(ds, never()).evictConnection(any());
    verify(con).abort(any());
  }

  @Test
  void PG_세션은_지우기를_재정의하지_않는다() {
    // PG GUC 는 트랜잭션-로컬이라 지울 것이 없다 — 기본 빈 구현을 그대로 써야 PG 동작이 바뀌지 않는다.
    assertThatThrownBy(
            () ->
                PostgresPlatformTenantSession.class.getDeclaredMethod(
                    "clearConnection", Connection.class))
        .isInstanceOf(NoSuchMethodException.class);
    assertThat(PlatformTenantSession.class.getMethods())
        .anySatisfy(
            m -> {
              assertThat(m.getName()).isEqualTo("clearConnection");
              assertThat(m.isDefault()).isTrue();
            });
  }
}
