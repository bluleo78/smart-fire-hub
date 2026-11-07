package com.smartfirehub.global.datastore;

import com.smartfirehub.global.platformdb.SessionLocks;
import com.smartfirehub.global.platformdb.postgres.PostgresSessionLockSql;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/**
 * 데이터 저장소(PG)의 세션 advisory 잠금(WD-493) — {@code pg_try_advisory_lock} / {@code
 * pg_advisory_unlock_all}.
 *
 * <p>왜 있나: 출력 테이블 세션 잠금({@code OutputTableSessionLock})의 짝인 {@code
 * SqlScriptExecutor.OUTPUT_LOCK_SQL}(트랜잭션 범위)은 데이터 저장소 연결에서 잡힌다. 둘이 서로를 막으려면 같은 DB·같은 해시 식이어야 하므로,
 * 이 구현은 호출자가 넘기는 {@code @DataStoreDb} 풀의 커넥션에서 실행된다. 분리 모드에서 데이터 저장소는 플랫폼 DB 와 다른 PG 다.
 *
 * <p>SQL·JDBC 본문은 {@link PostgresSessionLockSql} 한곳에 두고 플랫폼 구현({@code PostgresPlatformLocks})과 공유한다
 * — 두 벌로 두면 한쪽만 고쳐 키 식이 {@code SqlScriptExecutor.OUTPUT_LOCK_SQL} 과 갈라질 수 있다. 어느 DB 에서 잡히는지는 호출자가
 * 넘기는 커넥션이 정하므로, 이 클래스의 몫은 "데이터 저장소 쪽 {@link SessionLocks} 빈" 이라는 배선뿐이다. 상태가 없다.
 */
@Component
public class DataStoreSessionLocks implements SessionLocks {

  /** 세션 잠금 획득 시도 문장 — {@link PostgresSessionLockSql#TRY_ACQUIRE_SQL} 의 별칭(기존 테스트 호환). */
  public static final String TRY_ACQUIRE_SQL = PostgresSessionLockSql.TRY_ACQUIRE_SQL;

  /** 세션 잠금 해제 문장 — {@link PostgresSessionLockSql#RELEASE_SQL} 의 별칭(기존 테스트 호환). */
  public static final String RELEASE_SQL = PostgresSessionLockSql.RELEASE_SQL;

  /** 데이터 저장소 커넥션에서 세션 잠금을 시도한다(기다리지 않음). */
  @Override
  public boolean trySessionLock(Connection connection, String key) throws SQLException {
    return PostgresSessionLockSql.tryAcquire(connection, key);
  }

  /** 키와 무관하게 이 세션의 세션 잠금을 전부 푼다 — 잠금 전용 커넥션은 이 잠금 하나만 쥐므로 전부 풀어도 안전하고, 획득이 끊긴 모호한 경우까지 덮는다. */
  @Override
  public void releaseSessionLock(Connection connection, String key) throws SQLException {
    PostgresSessionLockSql.releaseAll(connection);
  }
}
