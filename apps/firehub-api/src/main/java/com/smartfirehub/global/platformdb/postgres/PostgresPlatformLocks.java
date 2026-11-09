package com.smartfirehub.global.platformdb.postgres;

import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformLockWaitTimeoutException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformLocks} 의 PostgreSQL 구현 — {@code pg_advisory_xact_lock} / {@code
 * pg_try_advisory_xact_lock} / {@code pg_try_advisory_lock}.
 *
 * <p>기존 호출 지점({@code TriggerRepository}, {@code SecurityLevelRepository}, {@code UserRepository},
 * {@code SessionAdvisoryLock})에 흩어져 있던 SQL 을 한 글자도 바꾸지 않고 옮겼다. 테스트가 SQL 텍스트에 기대고 있기 때문이다 — {@code
 * pg_stat_activity.query ilike '%pg_advisory_xact_lock%'} 폴링, {@code hashtextextended(?, 0)} 키 식
 * 공유.
 *
 * <p>트랜잭션 범위 잠금은 주입된 메인 {@link DSLContext} 로 실행하므로 호출자의 Spring 트랜잭션에 합류한다. 문자열 키는 {@code
 * hashtextextended(키, 0)} 식을 쓴다. 이 잠금은 플랫폼 DB 에서 잡힌다 — 데이터 저장소의 {@code
 * PythonReadGrantSync.lockTenantSync} 와는 다른 저장소일 수 있고, 테넌트마다 백엔드가 하나라 서로 겹치지 않는다(WD-493).
 */
@Component
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class PostgresPlatformLocks implements PlatformLocks {

  /**
   * 문자열 키 트랜잭션 잠금 — 키의 64비트 해시로 잠근다. 같은 키 문자열이면 어느 DSL(플랫폼·데이터 저장소)에서 잡든 같은 DB 안에서는 같은 잠금이다 — 아래 정적
   * 도우미를 {@code PythonReadGrantSync} 가 데이터 저장소 DSL 로 그대로 쓴다(키 식을 한곳에 둔다, WD-644).
   */
  public static final String XACT_LOCK_STRING_SQL =
      "SELECT pg_advisory_xact_lock(hashtextextended({0}, 0))";

  /** 문자열 키 트랜잭션 잠금 시도 — {@link #XACT_LOCK_STRING_SQL} 과 같은 키 식이라 같은 잠금을 두고 겨룬다(기다리지 않음, WD-644). */
  public static final String TRY_XACT_LOCK_STRING_SQL =
      "SELECT pg_try_advisory_xact_lock(hashtextextended({0}, 0))";

  /**
   * 상한 있는 시도형({@link #tryXactLock(DSLContext, String, Duration)}, WD-624)의 재시도 간격. WD-644 의 묶인 대기와
   * 달리 기다리는 문장을 쓰지 않으므로 시간을 넘겨도 호출자 트랜잭션이 깨지지 않는다 — 커밋 직전 좁히기처럼 사용자 트랜잭션을 이어 써야 하는 곳 전용이다.
   */
  private static final long TRY_XACT_LOCK_POLL_MILLIS = 50;

  /**
   * 묶인 대기 1단계(WD-644 리뷰) — 지금 lock_timeout 을 읽어 돌려주고, 현재 트랜잭션에만(SET LOCAL 과 같음) {0} 으로 건다. 읽기가 반드시
   * 먼저 일어나도록 MATERIALIZED CTE 로 순서를 못 박는다(바깥 행의 set_config 는 CTE 행이 나온 뒤에야 계산된다) — 한 왕복으로 줄인다.
   */
  static final String SAVE_AND_SET_LOCK_TIMEOUT_SQL =
      "WITH prev AS MATERIALIZED (SELECT current_setting('lock_timeout') AS v)"
          + " SELECT v, set_config('lock_timeout', {0}, true) FROM prev";

  /**
   * 묶인 대기 2단계 — 잠금을 잡은 <b>뒤</b> lock_timeout 을 {1}(원래 값)로 되돌린다. 잠금이 MATERIALIZED CTE 안이라 되돌리기는 잠금을
   * 잡은 다음에만 계산되고, 시간을 넘기면 문장 전체가 55P03 으로 실패한다(트랜잭션 중단 — 되돌릴 필요도 없다). 문장에 {@code
   * pg_advisory_xact_lock} 이 그대로 있어 pg_stat_activity 폴링 테스트도 이 문장을 잡는다.
   */
  static final String LOCK_THEN_RESTORE_LOCK_TIMEOUT_SQL =
      "WITH l AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtextextended({0}, 0)))"
          + " SELECT set_config('lock_timeout', {1}, true) FROM l";

  /** lock_timeout 초과 SQLState(lock_not_available) — 묶인 대기의 시간 초과 판정(WD-694). */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /** 정수 키 트랜잭션 잠금 — 키를 그대로 잠금 id 로 쓴다. */
  static final String XACT_LOCK_LONG_SQL = "SELECT pg_advisory_xact_lock({0})";

  /** 세션 잠금 획득 시도 문장 — {@link PostgresSessionLockSql#TRY_ACQUIRE_SQL} 의 별칭(기존 테스트·호출처 호환). */
  public static final String TRY_ACQUIRE_SQL = PostgresSessionLockSql.TRY_ACQUIRE_SQL;

  /** 세션 잠금 해제 문장 — {@link PostgresSessionLockSql#RELEASE_SQL} 의 별칭(기존 테스트·호출처 호환). */
  public static final String RELEASE_SQL = PostgresSessionLockSql.RELEASE_SQL;

  /** 메인 DSL — 호출자의 Spring 트랜잭션에 합류해 트랜잭션 범위 잠금을 잡는다. */
  private final DSLContext dsl;

  @Override
  public void xactLock(String key) {
    xactLock(dsl, key);
  }

  @Override
  public boolean tryXactLock(String key) {
    return tryXactLock(dsl, key);
  }

  @Override
  public void xactLock(String key, Duration timeout) {
    xactLock(dsl, key, timeout);
  }

  /** {@link #xactLock(String)} 를 주어진 DSL 의 현재 트랜잭션에서 — 데이터 저장소 DSL 로 잠그는 호출자용 정적 형태(WD-644). */
  public static void xactLock(DSLContext ctx, String key) {
    ctx.execute(XACT_LOCK_STRING_SQL, val(key));
  }

  /** {@link #tryXactLock(String)} 의 정적 형태. */
  public static boolean tryXactLock(DSLContext ctx, String key) {
    return Boolean.TRUE.equals(ctx.fetchValue(TRY_XACT_LOCK_STRING_SQL, val(key)));
  }

  /**
   * {@link #xactLock(String, Duration)} 의 정적 형태 — 두 왕복(WD-644 리뷰, 예전 네 왕복: 읽기·걸기·잠금·되돌리기).
   *
   * <p><b>왜 되돌리기가 필요한가.</b> 호출자(커밋 뒤 동기화)는 새 REQUIRES_NEW 트랜잭션에서 잠그므로 SET LOCAL 이 트랜잭션 밖으로 새지는 않는다.
   * 하지만 잠금을 잡은 <b>같은 트랜잭션</b>에서 동기화(카탈로그 읽기·GRANT/REVOKE)가 이어지므로, 되돌리지 않으면 그 문장들에까지 수 초 제한이 남아 엉뚱한
   * 곳에서 55P03 으로 실패한다(그건 "바쁨" 이 아니라 동기화 실패다). 그래서 "SET LOCAL 하고 잠금" 만으로 끝내지 않고, 원래 값 읽기를 걸기와, 되돌리기를
   * 잠금과 한 문장으로 묶는다.
   *
   * <p><b>시간 초과(WD-694).</b> 잠금 문장의 lock_not_available(SQLState {@code 55P03})은 Tibero 판과 같은 {@link
   * PlatformLockWaitTimeoutException} 으로 감싸 던진다(원래 예외는 원인으로 남긴다). 이때 PG 트랜잭션은 이미 중단됐으므로 호출자는 롤백으로
   * 끝내야 한다({@link PlatformLocks#xactLock(String, Duration)} 계약). 그 밖의 예외는 그대로 던진다.
   */
  public static void xactLock(DSLContext ctx, String key, Duration timeout) {
    Object previous =
        ctx.fetchOne(SAVE_AND_SET_LOCK_TIMEOUT_SQL, val(Math.max(1, timeout.toMillis()) + "ms"))
            .get(0);
    try {
      ctx.fetch(LOCK_THEN_RESTORE_LOCK_TIMEOUT_SQL, val(key), val(String.valueOf(previous)));
    } catch (RuntimeException e) {
      // 잠금 대기 시간 초과만 저장소 무관한 표시 예외로 바꾼다 — 메시지에 키는 싣지 않는다(테넌트 id·내부 잠금 이름).
      if (hasLockNotAvailable(e)) {
        throw new PlatformLockWaitTimeoutException("PG 트랜잭션 잠금 대기 시간 초과(제한 " + timeout + ")", e);
      }
      throw e;
    }
  }

  /** 원인 사슬에 lock_not_available(SQLState {@code 55P03}) SQLException 이 있는가. */
  private static boolean hasLockNotAvailable(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void xactLock(long key) {
    dsl.execute(XACT_LOCK_LONG_SQL, val(key));
  }

  /** 시도형 트랜잭션 잠금 — {@link #tryXactLock(DSLContext, String, Duration)} 를 메인 DSL(호출자 트랜잭션)로 부른다. */
  @Override
  public boolean tryXactLock(String key, Duration maxWait) {
    return tryXactLock(dsl, key, maxWait);
  }

  /**
   * 주어진 DSL 의 현재 트랜잭션에서 문자열 키 트랜잭션 잠금을 최대 {@code maxWait} 동안 시도한다 — {@code
   * pg_try_advisory_xact_lock} 을 짧은 간격으로 되풀이한다. 키 식은 {@link #XACT_LOCK_STRING_SQL} 과 같다(같은 잠금).
   * PYTHON 읽기 동기화처럼 메인 DSL 이 아닌 연결(분리 설치의 데이터 저장소)에서 같은 잠금을 시도하는 곳도 이 한 구현을 쓴다(WD-624).
   *
   * <p>기다리는 문장 + lock_timeout 을 쓰지 않는 이유: 시간 초과된 문장은 호출자 트랜잭션 전체를 실패 상태로 만든다. 그래서 새 트랜잭션에서 잠그고 시간
   * 초과 시 트랜잭션을 버려도 되는 커밋 뒤 실행기 경로는 {@link #xactLock(DSLContext, String, Duration)}(묶인 대기, WD-644)를,
   * 사용자 트랜잭션을 이어 써야 하는 커밋 직전 좁히기는 이 폴링형을 쓴다.
   *
   * @return 잡았으면 true(트랜잭션 끝까지 유지), 시간 안에 못 잡았거나 인터럽트되면 false
   */
  public static boolean tryXactLock(DSLContext ctx, String key, Duration maxWait) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    while (true) {
      // 한 번의 시도는 기다리지 않는 정적 형태(WD-644)와 같은 문장이다 — 시도 SQL 을 한곳에 둔다.
      if (tryXactLock(ctx, key)) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(TRY_XACT_LOCK_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /** 플랫폼 DB 커넥션에서 세션 잠금을 시도한다 — 본문은 데이터 저장소 구현과 공유하는 {@link PostgresSessionLockSql} 에 있다. */
  @Override
  public boolean trySessionLock(Connection connection, String key) throws SQLException {
    return PostgresSessionLockSql.tryAcquire(connection, key);
  }

  /**
   * 키와 무관하게 이 세션의 세션 잠금을 전부 푼다({@code unlock_all}, 사유는 {@link PostgresSessionLockSql#releaseAll}).
   */
  @Override
  public void releaseSessionLock(Connection connection, String key) throws SQLException {
    PostgresSessionLockSql.releaseAll(connection);
  }
}
