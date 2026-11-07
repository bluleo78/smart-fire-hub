package com.smartfirehub.global.platformdb.postgres;

import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformLocks;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformLocks} 의 PostgreSQL 구현 — {@code pg_advisory_xact_lock} / {@code
 * pg_try_advisory_lock}.
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

  /** 문자열 키 트랜잭션 잠금 — 키의 64비트 해시로 잠근다. */
  static final String XACT_LOCK_STRING_SQL =
      "SELECT pg_advisory_xact_lock(hashtextextended({0}, 0))";

  /** 문자열 키 트랜잭션 잠금 시도(기다리지 않음) — {@link #XACT_LOCK_STRING_SQL} 과 같은 키 식. */
  static final String TRY_XACT_LOCK_STRING_SQL =
      "SELECT pg_try_advisory_xact_lock(hashtextextended({0}, 0))";

  /** 시도형 트랜잭션 잠금의 재시도 간격. */
  private static final long TRY_XACT_LOCK_POLL_MILLIS = 50;

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
    dsl.execute(XACT_LOCK_STRING_SQL, val(key));
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
   * <p>기다리는 문장 + lock_timeout 을 쓰지 않는 이유: 시간 초과된 문장은 호출자 트랜잭션 전체를 실패 상태로 만든다.
   *
   * @return 잡았으면 true(트랜잭션 끝까지 유지), 시간 안에 못 잡았거나 인터럽트되면 false
   */
  public static boolean tryXactLock(DSLContext ctx, String key, Duration maxWait) {
    long deadline = System.nanoTime() + maxWait.toNanos();
    while (true) {
      if (Boolean.TRUE.equals(ctx.fetchValue(TRY_XACT_LOCK_STRING_SQL, val(key)))) {
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
