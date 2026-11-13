package com.smartfirehub.global.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TenantAwareTransactionManager#doRollback} 단위 시험(WD-727) — 이미 닫힌 연결의 롤백 실패만 삼키고, 연결이 열려 있으면
 * 지금처럼 롤백 실패를 던지는지 본다. 실서버 쪽 증명(Tibero 문장 취소 → 풀이 연결을 닫음 → 원래 예외 전파, 변경 미커밋)은 {@code
 * TiberoQueryTimeoutLiveTest} 가 한다.
 */
class TenantAwareTransactionManagerRollbackTest {

  private Connection con;
  private TransactionTemplate tx;

  /** 업무 코드가 던지는 원래 예외 — 시간 초과 모양. */
  private final QueryTimeoutException original = new QueryTimeoutException("statement canceled");

  @BeforeEach
  void setUp() throws SQLException {
    con = mock(Connection.class);
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenReturn(con);
    tx =
        new TransactionTemplate(
            new TenantAwareTransactionManager(ds, mock(PlatformTenantSession.class)));
    doThrow(new SQLException("Connection is closed")).when(con).rollback();
  }

  /** 원래 예외를 던지는 트랜잭션을 돌리고 부르는 쪽이 받은 예외를 돌려준다. */
  private Throwable runFailing() {
    return catchThrowable(
        () ->
            tx.executeWithoutResult(
                status -> {
                  throw original;
                }));
  }

  @Test
  @DisplayName("연결이 이미 닫혀 롤백이 실패하면 원래 예외가 그대로 전파된다")
  void closedConnection_rollbackFailureSwallowed() throws SQLException {
    when(con.isClosed()).thenReturn(true);

    assertThat(runFailing()).isSameAs(original);
    verify(con).rollback();
  }

  @Test
  @DisplayName("연결이 열려 있는데 롤백이 실패하면 지금처럼 롤백 실패가 원래 예외를 담아 나간다")
  void openConnection_rollbackFailurePropagates() throws SQLException {
    when(con.isClosed()).thenReturn(false);

    Throwable thrown = runFailing();

    assertThat(thrown).isInstanceOf(TransactionSystemException.class);
    assertThat(((TransactionSystemException) thrown).getApplicationException()).isSameAs(original);
  }

  @Test
  @DisplayName("연결 상태 확인이 실패하면 열린 것으로 보고 지금처럼 롤백 실패를 던진다")
  void closedCheckFails_rollbackFailurePropagates() throws SQLException {
    when(con.isClosed()).thenThrow(new SQLException("isClosed failed"));

    assertThat(runFailing()).isInstanceOf(TransactionSystemException.class);
  }

  /**
   * 커밋이 실패한 뒤의 롤백 실패는 삼키지 않는다(WD-727 리뷰 I1). 커밋 요청이 서버에 닿았는지 모르므로 결과는 「알 수 없음」이어야 한다 — 롤백됨으로 알리면
   * afterCompletion 의 보상 처리(외부 계정 DROP 등)가 이미 커밋됐을 수 있는 작업을 되돌릴 수 없게 지운다.
   */
  @Test
  @DisplayName("커밋이 실패한 뒤 닫힌 연결의 롤백 실패는 삼키지 않고 결과를 알 수 없음으로 알린다")
  void commitFailure_closedConnection_rollbackFailureNotSwallowed() throws SQLException {
    // 연결 끊김(08006) — JdbcTransactionManager 가 DataAccessException 으로 번역해 커밋 실패 뒤 롤백 경로를 탄다.
    doThrow(new SQLException("I/O error on commit", "08006")).when(con).commit();
    when(con.isClosed()).thenReturn(true);
    AtomicInteger completion = new AtomicInteger(-1);

    Throwable thrown =
        catchThrowable(
            () ->
                tx.executeWithoutResult(
                    status ->
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                              @Override
                              public void afterCompletion(int status) {
                                completion.set(status);
                              }
                            })));

    assertThat(thrown).isInstanceOf(TransactionSystemException.class);
    assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_UNKNOWN);
    verify(con).rollback();
  }
}
