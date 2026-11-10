package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.platformdb.PlatformLockWaitTimeoutException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.LockMode;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.SyncLockOps;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커밋 뒤 동기화의 실패 분류(WD-644 리뷰) 단위 검증 — DB 없이 본다.
 *
 * <ul>
 *   <li>묶인 대기에서 "바쁨"(빈 값)은 <b>잠금 획득 문장</b>의 55P03 뿐이고, 잠금을 잡은 뒤 동기화가 던진 55P03 은 그대로 던진다(무한 BUSY
 *       방지).
 *   <li>일시 실패는 정해진 예외 종류·SQLState 뿐이고, 나머지 DB 예외는 영구 실패다.
 * </ul>
 */
class PythonReadGrantBackendTest {

  /** 트랜잭션 없이 콜백을 그대로 부르는 템플릿 — 잠금·예외 분류만 보려고. */
  private static final TransactionTemplate DIRECT =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(null);
        }
      };

  /** lock_timeout 초과(55P03)를 품은 예외 — jOOQ·Spring 이 SQLException 을 감싸는 모양. */
  private static RuntimeException lockTimeout() {
    return new RuntimeException(
        "감싼 예외", new SQLException("canceling statement due to lock timeout", "55P03"));
  }

  /** 묶인 대기의 잠금 획득이 55P03 으로 실패하면 바쁨(빈 값)이고 work 는 돌지 않는다. */
  @Test
  void boundedWait_lockAcquisitionTimeout_isBusy() {
    AtomicBoolean workRan = new AtomicBoolean();
    Optional<Boolean> r =
        TenantContext.runScopedGet(
            1L,
            () ->
                PythonReadGrantBackend.withTenantSyncLock(
                    DIRECT,
                    ops(true),
                    LockMode.WAIT_BOUNDED,
                    Duration.ofMillis(10),
                    () -> {
                      workRan.set(true);
                      return true;
                    }));
    assertThat(r).isEmpty();
    assertThat(workRan.get()).isFalse();
  }

  /**
   * 회귀(WD-644 리뷰): 잠금을 잡은 뒤 work 가 던진 55P03(예: 테이블 잠금 시간 초과)은 바쁨이 아니다 — 그대로 던져 실패로 분류된다. 예전에는 원인
   * 사슬의 55P03 만 보고 BUSY 로 바꿔 영원히 재시도했다.
   */
  @Test
  void boundedWait_lockTimeoutFromWork_isRethrownNotBusy() {
    RuntimeException fromWork = lockTimeout();
    assertThatThrownBy(
            () ->
                TenantContext.runScopedGet(
                    1L,
                    () ->
                        PythonReadGrantBackend.withTenantSyncLock(
                            DIRECT,
                            ops(false),
                            LockMode.WAIT_BOUNDED,
                            Duration.ofMillis(10),
                            () -> {
                              throw fromWork;
                            })))
        .isSameAs(fromWork);
  }

  /**
   * WD-644 × WD-493: SQLState 가 없는 저장소(Tibero DBMS_LOCK 반환값 1)의 묶인 대기 시간 초과({@link
   * PlatformLockWaitTimeoutException})도 잠금 획득에서 나면 바쁨(빈 값)이고 work 는 돌지 않는다. 55P03 만 보던 판정이면 그대로 던져져
   * 일시 실패(FAILED)로 세어지고 5번 뒤 회수가 버려진다(변이).
   */
  @Test
  void boundedWait_platformLockWaitTimeout_isBusy() {
    AtomicBoolean workRan = new AtomicBoolean();
    SyncLockOps tiberoLike =
        new SyncLockOps() {
          @Override
          public void lock(String key) {}

          @Override
          public boolean tryLock(String key) {
            return true;
          }

          @Override
          public void lock(String key, Duration timeout) {
            throw new PlatformLockWaitTimeoutException("Tibero 트랜잭션 잠금 대기 시간 초과 흉내");
          }
        };
    Optional<Boolean> r =
        TenantContext.runScopedGet(
            1L,
            () ->
                PythonReadGrantBackend.withTenantSyncLock(
                    DIRECT,
                    tiberoLike,
                    LockMode.WAIT_BOUNDED,
                    Duration.ofSeconds(1),
                    () -> {
                      workRan.set(true);
                      return true;
                    }));
    assertThat(r).isEmpty();
    assertThat(workRan.get()).isFalse();
  }

  /**
   * WD-644 × WD-493: 잠금을 잡은 뒤 work 가 던진 {@link PlatformLockWaitTimeoutException} 은 바쁨이 아니다 — 판정은 잠금
   * 획득 문장에서만 한다(55P03 과 같은 규칙).
   */
  @Test
  void boundedWait_platformLockWaitTimeoutFromWork_isRethrownNotBusy() {
    RuntimeException fromWork = new PlatformLockWaitTimeoutException("작업 안의 다른 잠금 시간 초과 흉내");
    assertThatThrownBy(
            () ->
                TenantContext.runScopedGet(
                    1L,
                    () ->
                        PythonReadGrantBackend.withTenantSyncLock(
                            DIRECT,
                            ops(false),
                            LockMode.WAIT_BOUNDED,
                            Duration.ofMillis(10),
                            () -> {
                              throw fromWork;
                            })))
        .isSameAs(fromWork);
  }

  /** 잠금 획득이 55P03 이 아닌 오류로 실패하면 바쁨이 아니라 그대로 던진다. */
  @Test
  void boundedWait_otherLockError_isRethrown() {
    RuntimeException boom = new IllegalStateException("연결 끊김 흉내");
    SyncLockOps failing =
        new SyncLockOps() {
          @Override
          public void lock(String key) {}

          @Override
          public boolean tryLock(String key) {
            return true;
          }

          @Override
          public void lock(String key, Duration timeout) {
            throw boom;
          }
        };
    assertThatThrownBy(
            () ->
                TenantContext.runScopedGet(
                    1L,
                    () ->
                        PythonReadGrantBackend.withTenantSyncLock(
                            DIRECT,
                            failing,
                            LockMode.WAIT_BOUNDED,
                            Duration.ofMillis(10),
                            () -> true)))
        .isSameAs(boom);
  }

  /**
   * WD-694: 대기형(WAIT) 결과 풀기 — 값이 있으면 그대로 돌려주고, 빈 값이면 {@code NoSuchElementException} 대신 불변식(대기형은 빈
   * 값을 내지 않는다)이 깨졌다는 안내의 {@link IllegalStateException} 을 던진다.
   */
  @Test
  void requireWaitModeResult_unwrapsValue_andRejectsEmptyAsInvariantBreach() {
    assertThat(PythonReadGrantBackend.requireWaitModeResult(Optional.of("ok"))).isEqualTo("ok");
    assertThatThrownBy(() -> PythonReadGrantBackend.requireWaitModeResult(Optional.empty()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WAIT");
  }

  /** 대기형으로 잡으면 실제 결과가 빈 값이 아니다 — lockedSync 가 기대는 불변식(WD-694). */
  @Test
  void waitMode_neverReturnsEmpty() {
    Optional<Boolean> r =
        TenantContext.runScopedGet(
            1L,
            () ->
                PythonReadGrantBackend.withTenantSyncLock(
                    DIRECT, ops(false), LockMode.WAIT, null, () -> true));
    assertThat(PythonReadGrantBackend.requireWaitModeResult(r)).isTrue();
  }

  /** 실행기의 잠금 대기 → 방식: 0 이면 시도형, 양수면 묶인 대기. */
  @Test
  void lockModeForExecutorWait() {
    assertThat(LockMode.forExecutorWait(Duration.ZERO)).isEqualTo(LockMode.TRY);
    assertThat(LockMode.forExecutorWait(Duration.ofMillis(1))).isEqualTo(LockMode.WAIT_BOUNDED);
  }

  /** 일시 실패로 보는 것 — 정해진 예외 종류와 SQLState(08·40001·40P01·57P01·53) 뿐. */
  @Test
  void isTransient_acceptsOnlyRetryableKinds() {
    assertThat(PythonReadGrantBackend.isTransient(new CannotAcquireLockException("잠금")))
        .as("Spring TransientDataAccessException")
        .isTrue();
    assertThat(
            PythonReadGrantBackend.isTransient(
                new CannotCreateTransactionException(
                    "풀 시간 초과", new SQLTransientConnectionException("timeout"))))
        .as("원인 사슬의 SQLTransientException(Hikari 시간 초과)")
        .isTrue();
    for (String state : new String[] {"08006", "40001", "40P01", "57P01", "53300"}) {
      assertThat(
              PythonReadGrantBackend.isTransient(
                  new RuntimeException(new SQLException("x", state))))
          .as("SQLState " + state)
          .isTrue();
    }
    assertThat(
            PythonReadGrantBackend.isTransient(
                new PythonReadGrantBackend.SessionKillFailed(
                    "회수 뒤 끊기 실패", new IllegalStateException("영구처럼 보이는 원인"))))
        .as("회수 뒤 세션 끊기 실패는 원인과 무관하게 일시 실패(WD-664 1b)")
        .isTrue();
  }

  /**
   * 회귀(WD-644 리뷰): 그 밖의 DB 예외는 영구 실패다 — 예전에는 DataAccessException·SQLException 이면 무엇이든 일시 실패로 보아
   * 문법·권한 오류도 재시도 횟수만큼 헛돌았다.
   */
  @Test
  void isTransient_rejectsPermanentDbErrors() {
    assertThat(
            PythonReadGrantBackend.isTransient(
                new BadSqlGrammarException("t", "select", new SQLException("x", "42601"))))
        .isFalse();
    assertThat(PythonReadGrantBackend.isTransient(new DataIntegrityViolationException("dup")))
        .isFalse();
    assertThat(PythonReadGrantBackend.isTransient(new DataRetrievalFailureException("x")))
        .isFalse();
    assertThat(
            PythonReadGrantBackend.isTransient(
                new RuntimeException(new SQLException("permission denied", "42501"))))
        .isFalse();
    assertThat(PythonReadGrantBackend.isTransient(new SQLException("no state"))).isFalse();
    assertThat(PythonReadGrantBackend.isTransient(new IllegalStateException("버그"))).isFalse();
  }

  /** 묶인 대기는 {@code lockTimesOut} 이면 55P03 으로 실패하고 아니면 잡는 잠금 원시 동작. */
  private static SyncLockOps ops(boolean lockTimesOut) {
    return new SyncLockOps() {
      @Override
      public void lock(String key) {}

      @Override
      public boolean tryLock(String key) {
        return true;
      }

      @Override
      public void lock(String key, Duration timeout) {
        if (lockTimesOut) {
          throw lockTimeout();
        }
      }
    };
  }
}
