package com.smartfirehub.dbexecutor.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 요청 전체 시간 제한 감시자(스펙 §4.3-4). 기한이 지나면 지금 실행 중인 문장을 {@link Statement#cancel()} 해 드라이버가 즉시 오류를 던지게
 * 한다. 호출자는 그 오류를 보고 {@link #expired()} 로 시간 초과를 가려 ROLLBACK 한다.
 *
 * <p>기한은 <b>요청이 컨트롤러에 도착한 시점</b>부터 잰다({@code ExecuteController} 가 만든다). 기술자 조회·풀 대여·실행이 모두 이 기한
 * 안이어야 api 의 호출 제한(/execute/sql 60초, /execute/probe 35초)보다 먼저 끝난다 — 실행 시작부터 재면 앞 단계 시간만큼 늦어져 api 가
 * 먼저 포기해 단계를 FAILED 로 적은 뒤에 실행기가 커밋할 수 있다.
 *
 * <p>문장별 {@code setQueryTimeout} 만으로는 선행 문장이 여러 개일 때 합계가 api 호출 제한(60초)을 넘을 수 있어 따로 둔다. 타이머 스레드는 모든
 * 요청이 공유하는 데몬 스레드 하나다(요청마다 스레드를 만들지 않는다).
 */
public final class Deadline implements AutoCloseable {

  /** 기한이 이미 지나 다음 문장을 시작하지 않는다는 신호. */
  static final class TimedOut extends Exception {
    TimedOut() {
      super(null, null, false, false);
    }
  }

  /** 문장 하나에서 할 일. 기한 감시 등록·해제는 {@link #execute} 가 맡는다. */
  @FunctionalInterface
  interface StatementWork<T> {
    T run(Statement st) throws SQLException;
  }

  private static final ScheduledThreadPoolExecutor TIMER = newTimer();

  /**
   * 공유 타이머. 취소한 작업을 대기열에서 바로 빼도록({@code removeOnCancelPolicy}) 직접 만든다 — 기본값이면 요청마다 건 55초·30초 작업이
   * 취소된 뒤에도 그 시간 동안 대기열에 남아(Deadline 까지 붙잡고) 요청량 × 55초만큼 쌓인다. {@code
   * newSingleThreadScheduledExecutor} 는 이 설정을 바꿀 수 없는 래퍼를 돌려줘 쓰지 않는다.
   */
  private static ScheduledThreadPoolExecutor newTimer() {
    ScheduledThreadPoolExecutor timer =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "db-executor-deadline");
              t.setDaemon(true);
              return t;
            });
    timer.setRemoveOnCancelPolicy(true);
    return timer;
  }

  private final long deadlineNanos;
  private final AtomicBoolean fired = new AtomicBoolean();
  private final AtomicReference<Statement> current = new AtomicReference<>();
  private final ScheduledFuture<?> timer;

  /** 지금부터 {@code totalSeconds} 초 뒤를 기한으로 잡고 타이머를 건다. */
  public Deadline(int totalSeconds) {
    long totalNanos = TimeUnit.SECONDS.toNanos(totalSeconds);
    this.deadlineNanos = System.nanoTime() + totalNanos;
    this.timer = TIMER.schedule(this::fire, totalNanos, TimeUnit.NANOSECONDS);
  }

  /** 기한 도달: 표시를 먼저 세운 뒤 취소한다 — 취소 오류를 받은 쪽이 시간 초과로 판정할 수 있게. */
  private void fire() {
    fired.set(true);
    cancelQuietly(current.get());
  }

  /** 기한이 지났는지(타이머가 아직 안 돌았어도 시계로 판정). */
  public boolean expired() {
    return fired.get() || System.nanoTime() - deadlineNanos >= 0;
  }

  /** 남은 시간(지났으면 0). 기술자 조회처럼 기한 안에 묶을 앞 단계의 대기 상한으로 쓴다. */
  public Duration remaining() {
    return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
  }

  /**
   * 이번 문장의 {@code setQueryTimeout} 값: 문장 제한과 남은 시간(올림, 최소 1초) 중 작은 값. 타이머의 cancel 이 실행 직전 틈에 빗나가도
   * 문장이 기한 근처에서 스스로 끝나게 하는 안전망이다.
   */
  int statementTimeout(int statementSeconds) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    long remaining = Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L);
    return (int) Math.min(statementSeconds, remaining);
  }

  /**
   * 문장 하나를 기한 안에서 실행한다: 새 문장에 {@code setQueryTimeout}({@link #statementTimeout}) → 감시 등록 → {@code
   * work} → 감시 해제(성공·실패 모두). 등록·해제를 여기 한 곳에 두어 해제를 빠뜨려 취소 대상이 남는 일이 없게 한다.
   *
   * @param statementSeconds 이 문장의 제한(초). 남은 시간이 더 짧으면 그 값을 쓴다.
   * @throws TimedOut 이미 기한이 지나 문장을 시작하지 않았을 때
   */
  <T> T execute(Connection c, int statementSeconds, StatementWork<T> work)
      throws SQLException, TimedOut {
    try (Statement st = c.createStatement()) {
      st.setQueryTimeout(statementTimeout(statementSeconds));
      begin(st);
      try {
        return work.run(st);
      } finally {
        end();
      }
    }
  }

  /** 문장 실행 직전에 등록한다. 이미 기한이 지났으면 시작하지 않는다. */
  private void begin(Statement st) throws TimedOut {
    current.set(st);
    if (expired()) {
      current.set(null);
      throw new TimedOut();
    }
  }

  /** 문장 실행이 끝나면(성공·실패 모두) 등록을 푼다. */
  private void end() {
    current.set(null);
  }

  @Override
  public void close() {
    timer.cancel(false);
    current.set(null);
  }

  private static void cancelQuietly(Statement st) {
    if (st == null) {
      return;
    }
    try {
      st.cancel();
    } catch (SQLException | RuntimeException ignored) {
      // 이미 끝난 문장의 취소 실패는 무시한다 — 결과는 expired() 로 판정한다.
    }
  }
}
