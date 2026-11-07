package com.smartfirehub.global.datastore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 데이터 저장소 세션 잠금(WD-493)의 계약 — 데이터 저장소 풀({@code @DataStoreDb})의 실제 커넥션 2개로 확인한다. 분리 모드에서는 플랫폼 DB 와
 * 다른 DB 다.
 */
class DataStoreSessionLocksTest extends IntegrationTestBase {

  @Autowired private DataStoreSessionLocks locks;
  @Autowired @DataStoreDb private DataSource dataStore;

  private static String newKey() {
    return "wd493-ds-session-" + System.nanoTime();
  }

  /** 한 세션이 잡으면 다른 세션은 못 잡고, 키로 풀면 다시 잡을 수 있다. */
  @Test
  void tryAndReleaseSessionLock() throws Exception {
    try (Connection c1 = dataStore.getConnection();
        Connection c2 = dataStore.getConnection()) {
      c1.setAutoCommit(true);
      c2.setAutoCommit(true);
      String key = newKey();
      assertThat(locks.trySessionLock(c1, key)).isTrue();
      assertThat(locks.trySessionLock(c2, key)).isFalse();
      locks.releaseSessionLock(c1, key);
      assertThat(locks.trySessionLock(c2, key)).isTrue();
      locks.releaseSessionLock(c2, key);
    }
  }

  /**
   * 세션 잠금 키 공간이 {@code OUTPUT_LOCK_SQL}(트랜잭션 범위)과 같은 해시를 쓴다 — 세션 잠금을 쥔 동안 같은 키의 트랜잭션 잠금 시도는 실패한다.
   */
  @Test
  void tryUsesSameKeySpaceAsOutputLockSql() throws Exception {
    try (Connection c1 = dataStore.getConnection();
        Connection c2 = dataStore.getConnection()) {
      c1.setAutoCommit(true);
      String key = newKey();
      assertThat(locks.trySessionLock(c1, key)).isTrue();
      try {
        c2.setAutoCommit(false);
        try (PreparedStatement ps =
            c2.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
          ps.setString(1, key);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isFalse();
          }
        }
        c2.rollback();
      } finally {
        locks.releaseSessionLock(c1, key);
      }
    }
  }

  /** 잡은 적 없는 키를 풀어도 예외 없이 끝난다. */
  @Test
  void releasingUnheldKeyIsNoop() throws Exception {
    try (Connection c = dataStore.getConnection()) {
      c.setAutoCommit(true);
      assertThatCode(() -> locks.releaseSessionLock(c, newKey())).doesNotThrowAnyException();
    }
  }
}
