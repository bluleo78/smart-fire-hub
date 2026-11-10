package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformLocks;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.LockMode;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.SyncLockOps;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 묶인 대기 시간 초과 뒤 "트랜잭션을 끝낸다" 계약의 호출자 쪽 검증(WD-694).
 *
 * <p>{@link PlatformLocks#xactLock(String, Duration)} 이 시간을 넘기면 PG 는 트랜잭션을 중단시키고 Tibero 는 살려 둔다(저장소
 * 쪽 차이는 {@code PostgresPlatformLocksTest}·{@code TiberoPlatformLocksTest} 가 고정한다). 그래서 인터페이스는 "시간
 * 초과 뒤에는 트랜잭션을 롤백으로 끝내고 이어 쓰지 않는다" 를 계약으로 둔다. 이 테스트는 유일한 호출자인 {@link
 * PythonReadGrantBackend#withTenantSyncLock} 이 두 저장소 모두에서 그 계약을 지키는지 — 시간 초과면 빈 값, {@code work} 는
 * 돌지 않음, 트랜잭션은 롤백으로 끝남 — 를 본다. 현재 동작을 고정하는 계약 테스트라 구현 변경 없이 통과한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 보유자·대기자가 서로 다른 커넥션이어야 한다.
 */
class PythonReadSyncLockTimeoutContractTest extends IntegrationTestBase {

  /** 잠금을 잡을 테넌트 — 다른 테스트의 동기화 잠금 키와 겹치지 않는 값. */
  private static final long TENANT_ID = 69_401L;

  @Autowired private PlatformLocks locks;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private TransactionTemplate tx;

  /**
   * PG: 보유자가 같은 테넌트 동기화 잠금을 쥔 동안 묶인 대기(200ms)로 들어가면 시간 초과 → 빈 값, work 미실행, 그 REQUIRES_NEW 트랜잭션은
   * 롤백으로 끝난다(중단된 PG 트랜잭션을 커밋하려 하지 않는다).
   */
  @Test
  void postgres_boundedWaitTimeout_rollsBackTransaction() throws Exception {
    TransactionTemplate requiresNew = new TransactionTemplate(txManager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    AtomicInteger completion = new AtomicInteger(-1);
    AtomicBoolean workRan = new AtomicBoolean();
    String key = PythonReadGrantSync.syncLockKey(TENANT_ID);

    var outcome =
        PausedTransactionRace.run(
            tx,
            null,
            () -> locks.xactLock(key),
            () ->
                TenantContext.runScopedGet(
                    TENANT_ID,
                    () ->
                        PythonReadGrantBackend.withTenantSyncLock(
                            requiresNew,
                            recordingCompletion(platformOps(locks), completion),
                            LockMode.WAIT_BOUNDED,
                            Duration.ofMillis(200),
                            () -> {
                              workRan.set(true);
                              return true;
                            })));

    assertThat(outcome.secondError()).isNull();
    assertThat(outcome.secondResult()).as("시간 초과는 바쁨(빈 값)").isEqualTo(Optional.empty());
    assertThat(workRan.get()).as("잠금을 못 잡았으니 work 는 돌지 않는다").isFalse();
    assertThat(completion.get())
        .as("시간 초과 뒤 트랜잭션은 롤백으로 끝난다")
        .isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
  }

  /**
   * Tibero(모의 커넥션): DBMS_LOCK 시간 초과(반환값 1)는 트랜잭션을 깨뜨리지 않지만, 호출자는 PG 와 똑같이 롤백으로 끝낸다 — 커넥션에 rollback
   * 이 가고 commit 은 가지 않는다. 트랜잭션이 살아 있다고 이어 쓰면 저장소마다 동작이 갈라진다.
   */
  @Test
  void tibero_boundedWaitTimeout_rollsBackTransaction() throws Exception {
    DataSource ds = mock(DataSource.class);
    Connection probe = mock(Connection.class);
    Connection txConn = mock(Connection.class);
    // 첫 커넥션은 기동 확인(스키마·권한), 그다음은 트랜잭션 매니저가 여는 트랜잭션 커넥션이다.
    when(ds.getConnection()).thenReturn(probe, txConn);
    stubProbe(probe);
    CallableStatement request = mock(CallableStatement.class);
    when(txConn.prepareCall(contains("release_on_commit => TRUE"))).thenReturn(request);
    when(request.getInt(3)).thenReturn(1); // DBMS_LOCK.REQUEST 시간 초과
    when(txConn.getAutoCommit()).thenReturn(true);

    TiberoPlatformLocks tiberoLocks = new TiberoPlatformLocks(ds);
    tiberoLocks.afterPropertiesSet();
    TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(ds));
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    AtomicInteger completion = new AtomicInteger(-1);
    AtomicBoolean workRan = new AtomicBoolean();

    Optional<Boolean> result =
        TenantContext.runScopedGet(
            TENANT_ID,
            () ->
                PythonReadGrantBackend.withTenantSyncLock(
                    requiresNew,
                    recordingCompletion(platformOps(tiberoLocks), completion),
                    LockMode.WAIT_BOUNDED,
                    Duration.ofMillis(200),
                    () -> {
                      workRan.set(true);
                      return true;
                    }));

    assertThat(result).as("시간 초과는 바쁨(빈 값)").isEmpty();
    assertThat(workRan.get()).isFalse();
    assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(txConn).rollback();
    verify(txConn, never()).commit();
  }

  /**
   * 플랫폼 잠금을 테넌트 동기화 잠금 원시 동작으로 — Tibero 판 동기화({@code TiberoPythonReadGrantSync#lockOps})와 같은 연결.
   */
  private static SyncLockOps platformOps(PlatformLocks platformLocks) {
    return new SyncLockOps() {
      @Override
      public void lock(String key) {
        platformLocks.xactLock(key);
      }

      @Override
      public boolean tryLock(String key) {
        return platformLocks.tryXactLock(key);
      }

      @Override
      public void lock(String key, Duration timeout) {
        platformLocks.xactLock(key, timeout);
      }
    };
  }

  /** 묶인 대기 직전에 현재 트랜잭션의 끝 상태(커밋·롤백)를 기록하는 동기화를 걸고 원래 동작에 넘긴다. */
  private static SyncLockOps recordingCompletion(SyncLockOps delegate, AtomicInteger completion) {
    return new SyncLockOps() {
      @Override
      public void lock(String key) {
        delegate.lock(key);
      }

      @Override
      public boolean tryLock(String key) {
        return delegate.tryLock(key);
      }

      @Override
      public void lock(String key, Duration timeout) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                completion.set(status);
              }
            });
        delegate.lock(key, timeout);
      }
    };
  }

  /** 기동 확인 커넥션 — 현재 스키마를 돌려주고 확인 키는 비어 있다고(0) 응답한다. */
  private static void stubProbe(Connection probe) throws Exception {
    Statement st = mock(Statement.class);
    ResultSet rs = mock(ResultSet.class);
    when(probe.createStatement()).thenReturn(st);
    when(st.executeQuery(anyString())).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn("FH_APP");
    CallableStatement call = mock(CallableStatement.class);
    when(probe.prepareCall(anyString())).thenReturn(call);
    when(call.getInt(anyInt())).thenReturn(0);
  }
}
