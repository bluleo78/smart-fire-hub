package com.smartfirehub.global.tenant;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.ProxyConnection;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 세션 값이 남았을 수 있는 커넥션을 풀로 돌려보내지 않고 버린다(WD-492) — {@link TenantAwareTransactionManager} 의 정리 경로가 쓴다.
 *
 * <p>Hikari 풀이면 그 프록시 커넥션을 {@code evictConnection}(풀에서 빼고 물리 연결을 닫는다), 아니면 {@code abort} 로 물리 연결을
 * 끊는다. 정리 경로이므로 어떤 실패도 경고만 남기고 던지지 않는다. 사용 중인 커넥션을 evict 한 뒤 이어지는 반납에서 Hikari {@code ConcurrentBag}
 * 이 남기는 "not borrowed or reserved" WARN 은 예상된 로그다.
 */
final class PooledConnectionDiscard {

  private static final Logger log = LoggerFactory.getLogger(PooledConnectionDiscard.class);

  private PooledConnectionDiscard() {}

  /** 커넥션을 버린다 — Hikari 프록시(래퍼 안쪽 포함)면 evict, 아니면 abort. 예외는 던지지 않는다. */
  static void discard(DataSource dataSource, Connection connection) {
    try {
      HikariDataSource hikari = asHikari(dataSource);
      Connection proxy = hikari == null ? null : hikariProxyOf(connection);
      if (proxy != null) {
        hikari.evictConnection(proxy);
      } else {
        connection.abort(Runnable::run);
      }
    } catch (SQLException | RuntimeException e) {
      log.warn("테넌트 값이 남았을 수 있는 커넥션을 버리지 못했다", e);
    }
  }

  /**
   * 커넥션 자신 또는 표준 {@code unwrap} 으로 꺼낸 안쪽의 Hikari 프록시를 돌려준다. 없거나 꺼내다 실패하면 null — 호출자가 {@code abort}
   * 로 넘어간다. 래퍼째 evict 하면 Hikari 가 조용히 무시해 커넥션이 풀로 돌아가므로 안쪽 프록시를 찾는다. 프록시 판정은 Hikari evictConnection
   * 과 같다 ({@code com.zaxxer.hikari} 패키지 클래스).
   */
  private static Connection hikariProxyOf(Connection connection) {
    Connection candidate = connection;
    try {
      if (!isHikari(candidate) && connection.isWrapperFor(ProxyConnection.class)) {
        candidate = connection.unwrap(ProxyConnection.class);
      }
    } catch (SQLException | RuntimeException e) {
      log.debug("래퍼 커넥션에서 Hikari 프록시를 꺼내지 못해 끊기로 버린다", e);
      return null;
    }
    return candidate != null && isHikari(candidate) ? candidate : null;
  }

  private static boolean isHikari(Connection connection) {
    return connection.getClass().getName().startsWith("com.zaxxer.hikari");
  }

  /** DataSource 가 Hikari 풀이거나 Hikari 로 풀리는 래퍼면 그 풀을, 아니면 null. */
  private static HikariDataSource asHikari(DataSource dataSource) throws SQLException {
    if (dataSource instanceof HikariDataSource hikari) {
      return hikari;
    }
    return dataSource.isWrapperFor(HikariDataSource.class)
        ? dataSource.unwrap(HikariDataSource.class)
        : null;
  }
}
