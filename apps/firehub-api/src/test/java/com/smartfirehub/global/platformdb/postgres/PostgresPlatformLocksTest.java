package com.smartfirehub.global.platformdb.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import java.sql.Connection;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgresPlatformLocks 의 계약(WD-486) — 실제 DB 로 "기다리는가·풀리는가"를 확인한다. 트랜잭션 범위 잠금은 두 번째 트랜잭션이 첫째 커밋 뒤에야
 * 끝나는지로, 세션 잠금은 다른 커넥션의 시도 결과로 본다. 클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 서로 다른 커넥션이 필요하다.
 */
class PostgresPlatformLocksTest extends IntegrationTestBase {

  @Autowired private PlatformLocks locks;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;
  @Autowired private TransactionTemplate tx;

  /**
   * tx1 이 {@code holder} 로 잠금을 쥔 채 커밋 전에 멈추고, tx2 가 {@code contender} 로 같은 잠금을 요구한다. tx2 는 tx1 커밋
   * 전에는 끝나지 않아야 하고(대기 관측) 커밋 뒤에는 오류 없이 끝나야 한다. 키가 어긋나면 tx2 가 바로 끝나 대기 단언이 실패한다. 잠금만 보므로 테넌트 컨텍스트는
   * 쓰지 않는다(null).
   */
  private void assertBlocksUntilCommit(
      Consumer<PlatformLocks> holder, Consumer<PlatformLocks> contender) throws Exception {
    var outcome =
        PausedTransactionRace.run(
            tx,
            null,
            () -> holder.accept(locks),
            () -> {
              // tx2 는 감싸지 않고 실행되므로 스스로 트랜잭션을 세운다(xactLock 은 트랜잭션 안에서만 의미가 있다).
              tx.executeWithoutResult(s -> contender.accept(locks));
              return null;
            });
    assertThat(outcome.secondBlocked()).as("tx2 가 tx1 커밋 전에 잠금을 얻었다").isTrue();
    assertThat(outcome.secondError()).isNull();
  }

  @Test
  void xactLockBlocksSecondTransactionUntilCommit() throws Exception {
    assertBlocksUntilCommit(l -> l.xactLock("wd486-k"), l -> l.xactLock("wd486-k"));
  }

  /** R4 불변식 — Tibero 판이 쓰는 문자열 키가 PG 판 {@code lockTenantSync} 와 같은 잠금 공간이다. */
  @Test
  void xactLockSerializesWithPythonReadGrantSyncLockTenantSync() throws Exception {
    assertBlocksUntilCommit(
        l -> PythonReadGrantSync.lockTenantSync(dsl, 7L), l -> l.xactLock("python_read_sync_t7"));
  }

  /** 정수 키는 가입 잠금(acquireFirstUserLock)의 {@code pg_advisory_xact_lock(1)} 과 같은 키 공간이다. */
  @Test
  void xactLockLongUsesSameKeySpaceAsAcquireFirstUserLock() throws Exception {
    // tx1 이 정수 키를 쥔 동안 tx2 가 같은 키를 try 해 보면 실패해야 한다(대기 없이 즉시 false).
    var outcome =
        PausedTransactionRace.run(
            tx,
            null,
            () -> locks.xactLock(1L),
            () ->
                tx.execute(
                    s ->
                        dsl.fetchOne("SELECT pg_try_advisory_xact_lock(1)").get(0, Boolean.class)));
    assertThat(outcome.secondError()).isNull();
    assertThat(outcome.secondResult()).isEqualTo(false);
  }

  @Test
  void sessionLockTryAndReleaseAll() throws Exception {
    try (Connection c1 = dataSource.getConnection();
        Connection c2 = dataSource.getConnection()) {
      c1.setAutoCommit(true);
      c2.setAutoCommit(true);
      String key = "wd486-session-" + System.nanoTime();
      assertThat(locks.trySessionLock(c1, key)).isTrue();
      assertThat(locks.trySessionLock(c2, key)).isFalse();
      locks.releaseAllSessionLocks(c1);
      assertThat(locks.trySessionLock(c2, key)).isTrue();
      locks.releaseAllSessionLocks(c2);
    }
  }
}
