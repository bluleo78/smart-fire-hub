package com.smartfirehub.global.platformdb.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
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

  /**
   * 시도형 트랜잭션 잠금(WD-644) — 같은 저장소에서 같은 키의 대기형 잠금을 쥔 동안 시도는 기다리지 않고 false, 아무도 쥐지 않으면 true. 두 짝을 본다
   * (WD-493 「한 직렬화 = 한 저장소」 이후 PG 판과 Tibero 판은 같은 잠금 공간을 공유하지 않는다).
   *
   * <ul>
   *   <li>PG 백엔드 짝: {@code lockTenantSync} 와 정적 {@link
   *       PostgresPlatformLocks#tryXactLock(org.jooq.DSLContext, String)} 을 <b>같은 DSL</b>(운영에서는 데이터
   *       저장소)로 — PG 판 커밋 뒤 동기화가 실제로 쓰는 조합이다.
   *   <li>플랫폼 짝: {@link PlatformLocks#xactLock(String)} 와 {@link PlatformLocks#tryXactLock(String)}
   *       — Tibero 판(플랫폼 잠금)이 쓰는 조합이다.
   * </ul>
   */
  @Test
  void tryXactLockFailsFastWhileHeldOnSameStore_andSucceedsWhenFree() throws Exception {
    String pgKey = PythonReadGrantSync.syncLockKey(644L);
    var pgPair =
        PausedTransactionRace.run(
            tx,
            null,
            () -> PythonReadGrantSync.lockTenantSync(dsl, 644L),
            () -> tx.execute(s -> PostgresPlatformLocks.tryXactLock(dsl, pgKey)));
    assertThat(pgPair.secondError()).isNull();
    assertThat(pgPair.secondResult()).as("PG 백엔드 짝: 쥔 동안 false").isEqualTo(false);
    Boolean pgFree = tx.execute(s -> PostgresPlatformLocks.tryXactLock(dsl, pgKey));
    assertThat(pgFree).as("아무도 쥐지 않으면 잡는다").isTrue();

    String platformKey = PythonReadGrantSync.syncLockKey(645L);
    var platformPair =
        PausedTransactionRace.run(
            tx,
            null,
            () -> locks.xactLock(platformKey),
            () -> tx.execute(s -> locks.tryXactLock(platformKey)));
    assertThat(platformPair.secondError()).isNull();
    assertThat(platformPair.secondResult()).as("플랫폼 짝: 쥔 동안 false").isEqualTo(false);
    Boolean free = tx.execute(s -> locks.tryXactLock(platformKey));
    assertThat(free).as("아무도 쥐지 않으면 잡는다").isTrue();
  }

  /**
   * 묶인 대기(WD-644) — 다른 트랜잭션이 쥐고 있으면 제한 시간 뒤 lock_not_available(55P03)로 실패하고, 잡았을 때는 lock_timeout 을
   * 원래 값으로 되돌려 그 뒤 문장(테이블 잠금 등)에 제한이 남지 않는다.
   */
  @Test
  void boundedXactLock_timesOutWhileHeld_andRestoresLockTimeoutWhenAcquired() throws Exception {
    String key = PythonReadGrantSync.syncLockKey(646L);
    var outcome =
        PausedTransactionRace.run(
            tx,
            null,
            () -> locks.xactLock(key),
            () ->
                tx.execute(
                    s -> {
                      locks.xactLock(key, Duration.ofMillis(200));
                      return true;
                    }));
    assertThat(outcome.secondError()).as("쥔 동안에는 시간 초과로 실패한다").isNotNull();
    assertThat(hasSqlState(outcome.secondError(), "55P03")).isTrue();

    String after =
        tx.execute(
            s -> {
              locks.xactLock(key, Duration.ofSeconds(1));
              return String.valueOf(dsl.fetchValue("SELECT current_setting('lock_timeout')"));
            });
    assertThat(after).as("잡은 뒤에는 lock_timeout 이 원래 값(0)으로 돌아온다").isEqualTo("0");
  }

  /**
   * WD-644 리뷰: 묶인 대기는 두 왕복(읽기+걸기, 잠금+되돌리기)으로 줄었어도 <b>원래 값</b>을 되돌린다 — 0 이 아닌 값(7s)이 걸려 있던 트랜잭션에서도
   * 잡은 뒤에는 7s 다. 읽기가 걸기보다 늦게 계산되면(순서가 뒤집히면) 걸어 둔 대기 값으로 "되돌려" 이 단언이 실패한다.
   */
  @Test
  void boundedXactLock_restoresNonDefaultPreviousLockTimeout() {
    String key = PythonReadGrantSync.syncLockKey(647L);
    String after =
        tx.execute(
            s -> {
              dsl.execute("SET LOCAL lock_timeout = '7s'");
              locks.xactLock(key, Duration.ofMillis(300));
              return String.valueOf(dsl.fetchValue("SELECT current_setting('lock_timeout')"));
            });
    assertThat(after).isEqualTo("7s");
  }

  /** 원인 사슬에 그 SQLState 의 SQLException 이 있는가. */
  private static boolean hasSqlState(Throwable e, String state) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && state.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  @Test
  void sessionLockTryAndRelease() throws Exception {
    try (Connection c1 = dataSource.getConnection();
        Connection c2 = dataSource.getConnection()) {
      c1.setAutoCommit(true);
      c2.setAutoCommit(true);
      String key = "wd486-session-" + System.nanoTime();
      assertThat(locks.trySessionLock(c1, key)).isTrue();
      assertThat(locks.trySessionLock(c2, key)).isFalse();
      locks.releaseSessionLock(c1, key);
      assertThat(locks.trySessionLock(c2, key)).isTrue();
      locks.releaseSessionLock(c2, key);
    }
  }
}
