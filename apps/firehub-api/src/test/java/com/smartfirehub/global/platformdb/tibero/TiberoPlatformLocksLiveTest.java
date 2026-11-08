package com.smartfirehub.global.platformdb.tibero;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.global.platformdb.PlatformLockWaitTimeoutException;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoTestAccount;
import com.smartfirehub.support.tibero.TiberoTestAccount.Privileges;
import com.smartfirehub.support.tibero.TiberoTestEvidence;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TiberoPlatformLocks} 실서버 테스트(WD-493) — DBMS_LOCK 이 PG advisory 잠금과 같은 계약을 지키는지 공유 개발
 * Tibero 에서 본다.
 *
 * <p>트랜잭션은 계정 풀 하나에 묶인 {@link DataSourceTransactionManager} 로 연다 — 잠금 구현이 쓰는 {@code DataSource} 와
 * 같은 인스턴스여야 {@code DataSourceUtils} 가 트랜잭션 커넥션을 돌려준다(다르면 자동 커밋 커넥션에서 잡혀 곧바로 풀린다).
 *
 * <p>공유 서버 안전장치: 풀은 3, 키는 테스트마다 무작위 접미사(세션 잠금은 풀의 물리 세션에 남으므로 테스트끼리 섞이지 않게), 보유자는 {@code finally}
 * 에서 반드시 끝내 대기자가 서버에 남지 않게 한다. 대기자의 서버 쪽 대기는 인터럽트로 끊기지 않으므로 이 순서가 중요하다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = SECONDS)
class TiberoPlatformLocksLiveTest {

  private TiberoTestAccount account;
  private TiberoPlatformLocks locks;
  private TransactionTemplate tx;

  @BeforeAll
  void setUp() {
    account = TiberoTestAccount.create("LOCKS", Privileges.TABLES_WITH_DBMS_LOCK, 3);
    locks = new TiberoPlatformLocks(account.dataSource());
    // 운영 빈처럼 초기화(스키마 이름공간 읽기·권한 확인)를 거친 뒤에만 잠근다.
    locks.afterPropertiesSet();
    tx = new TransactionTemplate(new DataSourceTransactionManager(account.dataSource()));
  }

  /** 이번 계정을 지우고, 이번 용도의 계정이 서버에 남았는지 읽기 전용으로 세어 증거로 남긴다(지우지 않는다). */
  @AfterAll
  void tearDown() {
    if (account != null) account.close();
    ev(
        "남은 FHT_LOCKS_* 계정 "
            + TiberoTestAccount.remainingAccounts("LOCKS")
            + ", FHT_LOCKNOGRANT_* 계정 "
            + TiberoTestAccount.remainingAccounts("LOCKNOGRANT"));
  }

  private static void ev(String line) {
    TiberoTestEvidence.write("LOCKS " + line);
  }

  /** 테스트마다 다른 키 — 앞선 테스트가 실패해 남긴 세션 잠금과 겹치지 않게 한다. */
  private static String key(String name) {
    return "fh_locks_live:" + name + ":" + UUID.randomUUID();
  }

  @Test
  void xactLockBlocksSecondTransactionUntilCommit() throws Exception {
    long waited = assertBlocksUntilHolderEnds(key("block"), false);
    ev("xact 대기자: 300ms 안에 안 끝남, 커밋 뒤 " + waited + "ms 에 끝남");
  }

  @Test
  void rollbackAlsoReleasesXactLock() throws Exception {
    long waited = assertBlocksUntilHolderEnds(key("rollback"), true);
    ev("xact 대기자: 300ms 안에 안 끝남, 롤백 뒤 " + waited + "ms 에 끝남");
  }

  /**
   * 보유 트랜잭션이 잠금을 쥔 동안 두 번째 트랜잭션이 300ms 안에 끝나지 않고, 보유자가 끝나면(커밋·롤백) 5초 안에 끝나는지 본다.
   *
   * @return 보유자를 끝낸 뒤 대기자가 끝날 때까지 걸린 밀리초
   */
  private long assertBlocksUntilHolderEnds(String key, boolean rollback) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        locks.xactLock(key);
                        held.countDown();
                        awaitQuietly(finish);
                        if (rollback) status.setRollbackOnly();
                      }));
      assertThat(held.await(10, SECONDS)).as("보유자가 잠금을 잡음").isTrue();
      Future<?> waiter = pool.submit(() -> tx.executeWithoutResult(s -> locks.xactLock(key)));
      assertThatThrownBy(() -> waiter.get(300, MILLISECONDS)).isInstanceOf(TimeoutException.class);
      long t0 = System.nanoTime();
      finish.countDown();
      holder.get(5, SECONDS);
      waiter.get(5, SECONDS);
      return (System.nanoTime() - t0) / 1_000_000;
    } finally {
      // 어떤 단언이 실패해도 보유자를 끝내 대기자가 서버에서 풀려나게 한다.
      finish.countDown();
      pool.shutdown();
      pool.awaitTermination(10, SECONDS);
    }
  }

  @Test
  void xactLockIsReentrantWithinTransaction() throws Exception {
    String k = key("reentrant");
    assertThatCode(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      locks.xactLock(k);
                      locks.xactLock(k);
                    }))
        .doesNotThrowAnyException();
    // 커밋 뒤에는 다른 세션이 같은 id 를 잡을 수 있어야 한다(재진입이 잠금을 남기지 않는다).
    try (Connection c = account.dataSource().getConnection()) {
      c.setAutoCommit(true);
      assertThat(locks.trySessionLock(c, k)).isTrue();
      locks.releaseSessionLock(c, k);
    }
    ev("xact 재진입: 같은 트랜잭션 두 번 → 예외 없음, 커밋 뒤 풀림");
  }

  @Test
  void xactLockOutsideTransactionIsRejected() {
    assertThatThrownBy(() -> locks.xactLock(key("outside")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("트랜잭션 안에서만");
    assertThatThrownBy(() -> locks.xactLock(42L)).isInstanceOf(IllegalStateException.class);
    ev("xact 트랜잭션 밖 → IllegalStateException");
  }

  /**
   * WD-644: 커밋 뒤 PYTHON 읽기 동기화의 시도형·묶인 대기가 실제 DBMS_LOCK 에서 같은 잠금을 두고 겨룬다 — 다른 트랜잭션이 쥔 동안 시도형은 기다리지
   * 않고 false, 묶인 대기(1초)는 약 1초 뒤 {@link PlatformLockWaitTimeoutException}, 보유자가 커밋하면 둘 다 잡는다.
   */
  @Test
  void tryAndBoundedXactLockContendWithHolder() throws Exception {
    String k = key("bounded");
    ExecutorService pool = Executors.newFixedThreadPool(1);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        locks.xactLock(k);
                        held.countDown();
                        awaitQuietly(finish);
                      }));
      assertThat(held.await(10, SECONDS)).as("보유자가 잠금을 잡음").isTrue();
      long t0 = System.nanoTime();
      Boolean tried = tx.execute(s -> locks.tryXactLock(k));
      long tryMs = (System.nanoTime() - t0) / 1_000_000;
      assertThat(tried).as("쥔 동안 시도형은 false").isFalse();
      long t1 = System.nanoTime();
      Throwable timedOut =
          catchThrowable(
              () ->
                  tx.executeWithoutResult(s -> locks.xactLock(k, java.time.Duration.ofSeconds(1))));
      long boundedMs = (System.nanoTime() - t1) / 1_000_000;
      assertThat(timedOut).isInstanceOf(PlatformLockWaitTimeoutException.class);
      assertThat(boundedMs).as("묶인 대기는 대략 제한 시간만큼만 기다린다").isBetween(800L, 5_000L);
      finish.countDown();
      holder.get(5, SECONDS);
      Boolean acquired = tx.execute(s -> locks.tryXactLock(k));
      assertThat(acquired).as("보유자 커밋 뒤 시도형은 잡는다").isTrue();
      assertThatCode(
              () ->
                  tx.executeWithoutResult(s -> locks.xactLock(k, java.time.Duration.ofSeconds(1))))
          .doesNotThrowAnyException();
      ev(
          "xact 시도형: 쥔 동안 "
              + tryMs
              + "ms 에 false, 묶인 대기 1초: "
              + boundedMs
              + "ms 에 시간 초과, 커밋 뒤 둘 다 잡음");
    } finally {
      finish.countDown();
      pool.shutdown();
      pool.awaitTermination(10, SECONDS);
    }
  }

  @Test
  void sessionLockTryAndRelease() throws Exception {
    String k = key("session");
    try (Connection c1 = account.dataSource().getConnection();
        Connection c2 = account.dataSource().getConnection()) {
      c1.setAutoCommit(true);
      c2.setAutoCommit(true);
      try {
        boolean first = locks.trySessionLock(c1, k);
        boolean second = locks.trySessionLock(c2, k);
        locks.releaseSessionLock(c1, k);
        boolean afterRelease = locks.trySessionLock(c2, k);
        locks.releaseSessionLock(c2, k);
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(afterRelease).isTrue();
        ev("session c1=" + first + " c2=" + second + " c1해제뒤 c2=" + afterRelease);
      } finally {
        locks.releaseSessionLock(c1, k);
        locks.releaseSessionLock(c2, k);
      }
    }
  }

  @Test
  void sessionLockSurvivesCommit() throws Exception {
    String k = key("survive");
    try (Connection c1 = account.dataSource().getConnection();
        Connection c2 = account.dataSource().getConnection()) {
      c2.setAutoCommit(true);
      c1.setAutoCommit(false);
      try {
        assertThat(locks.trySessionLock(c1, k)).isTrue();
        c1.commit();
        boolean other = locks.trySessionLock(c2, k);
        assertThat(other).isFalse();
        ev("session 커밋 뒤에도 남음: c2 시도=" + other);
      } finally {
        locks.releaseSessionLock(c1, k);
        locks.releaseSessionLock(c2, k);
        c1.setAutoCommit(true);
      }
    }
  }

  @Test
  void releasingUnheldSessionLockIsNoop() throws Exception {
    try (Connection c = account.dataSource().getConnection()) {
      c.setAutoCommit(true);
      String k = key("unheld");
      assertThatCode(() -> locks.releaseSessionLock(c, k)).doesNotThrowAnyException();
    }
    ev("session 안 쥔 키 해제 → 예외 없음");
  }

  @Test
  void longAndStringKeySpacesDoNotCollide() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        locks.xactLock(5L);
                        held.countDown();
                        awaitQuietly(finish);
                      }));
      assertThat(held.await(10, SECONDS)).isTrue();
      try (Connection c = account.dataSource().getConnection()) {
        c.setAutoCommit(true);
        try {
          boolean got = locks.trySessionLock(c, "5");
          assertThat(got).isTrue();
          ev("키 공간 분리: xactLock(5L) 보유 중 trySessionLock(\"5\")=" + got);
        } finally {
          locks.releaseSessionLock(c, "5");
        }
      }
      finish.countDown();
      holder.get(5, SECONDS);
    } finally {
      finish.countDown();
      pool.shutdown();
      pool.awaitTermination(10, SECONDS);
    }
  }

  /** 잠금 id 이름공간: 실서버에서 읽은 현재 스키마가 접속 계정(기본 스키마)과 같다 — SYS_CONTEXT 가 Tibero 에서 동작하는지 본다. */
  @Test
  void currentSchemaIsAccountSchema() throws Exception {
    try (Connection c = account.dataSource().getConnection()) {
      String viaContext;
      try (var st = c.createStatement();
          var rs = st.executeQuery(TiberoPlatformLocks.CURRENT_SCHEMA_SQL)) {
        viaContext = rs.next() ? rs.getString(1) : null;
      }
      String resolved = TiberoPlatformLocks.currentSchema(c);
      assertThat(viaContext).as("SYS_CONTEXT('USERENV','CURRENT_SCHEMA') 결과").isNotBlank();
      assertThat(resolved).isEqualToIgnoringCase(account.name());
      ev(
          "스키마 이름공간: SYS_CONTEXT 동작="
              + (viaContext != null)
              + ", 접속 계정과 일치="
              + resolved.equalsIgnoreCase(account.name()));
    }
  }

  @Test
  void startupCheckPassesWithGrant() {
    assertThatCode(() -> new TiberoPlatformLocks(account.dataSource()).afterPropertiesSet())
        .doesNotThrowAnyException();
    ev("기동 확인: 권한 있는 계정 → 통과");
  }

  @Test
  void startupCheckStopsWithoutGrant() {
    // 풀 1 — 끝나면 바로 닫아 공유 서버 세션을 돌려준다.
    try (TiberoTestAccount noGrant =
        TiberoTestAccount.create("LOCKNOGRANT", Privileges.TABLES, 1)) {
      Throwable thrown =
          catchThrowable(() -> new TiberoPlatformLocks(noGrant.dataSource()).afterPropertiesSet());
      String code =
          thrown != null && thrown.getCause() instanceof SQLException sqle
              ? Integer.toString(sqle.getErrorCode())
              : "없음";
      ev(
          "기동 확인: 권한 없는 계정 → "
              + (thrown == null ? "예외 없음" : thrown.getClass().getSimpleName())
              + " 원인 오류 코드="
              + code);
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(TiberoPlatformLocks.MISSING_GRANT_MESSAGE);
    }
  }

  /**
   * 증분 스텝 잠금 풀({@code IncrementalStepLock})이 붙이는 {@code ApplicationName} 드라이버 속성을 Tibero 드라이버가 거절하지
   * 않는지 기록한다. 거절 여부만 본다 — 받은 이름이 세션 정보에 실리는지는 계약이 아니다.
   */
  @Test
  void tiberoDriverAcceptsApplicationNameProperty() {
    HikariDataSource base = account.dataSource();
    HikariConfig cfg = new HikariConfig();
    cfg.setPoolName("fh-appname-check");
    cfg.setDriverClassName(TiberoDialect.DRIVER_CLASS);
    cfg.setJdbcUrl(base.getJdbcUrl());
    cfg.setUsername(base.getUsername());
    cfg.setPassword(base.getPassword());
    cfg.setMaximumPoolSize(1);
    cfg.setMinimumIdle(0);
    cfg.setConnectionTimeout(15_000);
    cfg.addDataSourceProperty("ApplicationName", "fh-incremental-lock");
    String result;
    boolean accepted;
    try (HikariDataSource ds = new HikariDataSource(cfg);
        Connection c = ds.getConnection()) {
      accepted = c.isValid(5);
      result = "거절하지 않음(getConnection 성공, valid=" + accepted + ")";
    } catch (Exception e) {
      // 메시지에는 접속 정보가 섞일 수 있어 종류와 오류 코드만 남긴다.
      Throwable root = e;
      while (root.getCause() != null && root.getCause() != root) root = root.getCause();
      String code = root instanceof SQLException sqle ? Integer.toString(sqle.getErrorCode()) : "-";
      accepted = false;
      result = "거절함(" + e.getClass().getSimpleName() + ", 오류 코드 " + code + ")";
    }
    ev("ApplicationName 드라이버 속성: " + result);
    // 회귀 감시 — IncrementalStepLock 이 Tibero 에서도 이 속성을 그대로 붙이므로, 드라이버가 거절하기 시작하면 여기서 드러나야 한다.
    // 실패 메시지에는 원인 예외를 싣지 않는다(접속 정보가 섞일 수 있다).
    assertThat(accepted).as("ApplicationName 드라이버 속성: " + result).isTrue();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
