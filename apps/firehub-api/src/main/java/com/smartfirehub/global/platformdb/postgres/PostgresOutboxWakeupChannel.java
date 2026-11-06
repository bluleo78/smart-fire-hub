package com.smartfirehub.global.platformdb.postgres;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.OutboxWakeupChannel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxWakeupChannel} 의 PostgreSQL 구현 — {@code NOTIFY outbox_new} / {@code LISTEN
 * outbox_new}.
 *
 * <p>기존 {@code OutboxNotifier}(발행)와 {@code OutboxListenerLoop}(구독 연결 수명)의 본문을 그대로 옮겼다.
 *
 * <p>발행은 풀 커넥션({@link DataSource})을 잠깐 빌려 쓴다. 구독은 HikariCP 풀 밖에서 {@link
 * DriverManager#getConnection} 으로 전용 소켓을 직접 얻는다 — 루프 내내 풀 커넥션 1개를 점유하면 풀 고갈(#174)이 나기 때문이다.
 *
 * <p><b>테넌트 배선 제외(P2-f)</b>: NOTIFY/LISTEN 만 하고 도메인 행을 읽거나 쓰지 않아 RLS 의 영향을 받지 않는다.
 */
@Component
@ConditionalOnPostgresPlatformDb
public class PostgresOutboxWakeupChannel implements OutboxWakeupChannel {

  private static final Logger log = LoggerFactory.getLogger(PostgresOutboxWakeupChannel.class);

  /** 발행용 풀 커넥션 원천. */
  private final DataSource dataSource;

  /** LISTEN 전용 커넥션 획득에 사용할 JDBC 접속 정보. */
  private final DataSourceProperties dataSourceProperties;

  public PostgresOutboxWakeupChannel(
      DataSource dataSource, DataSourceProperties dataSourceProperties) {
    this.dataSource = dataSource;
    this.dataSourceProperties = dataSourceProperties;
  }

  /** NOTIFY 발행. 실패해도 폴백 폴링이 처리하므로 warn 로그만 남기고 삼킨다. */
  @Override
  public void publish() {
    try (var conn = dataSource.getConnection();
        var st = conn.createStatement()) {
      st.execute("NOTIFY outbox_new");
    } catch (Exception e) {
      log.warn("Failed to NOTIFY outbox_new: {}", e.getMessage());
    }
  }

  /** 전용 연결로 LISTEN 후 신호를 기다린다. 연결 오류는 던지고, 어떤 경우든 연결은 finally 에서 닫는다. */
  @Override
  public void listen(BooleanSupplier running, Duration pollTimeout, Runnable onSignal)
      throws Exception {
    Connection conn = null;
    try {
      // HikariCP 풀을 거치지 않고 DriverManager 로 전용 LISTEN 커넥션 직접 획득.
      conn =
          DriverManager.getConnection(
              dataSourceProperties.getUrl(),
              dataSourceProperties.getUsername(),
              dataSourceProperties.getPassword());
      conn.setAutoCommit(true); // LISTEN은 트랜잭션 불필요; idle 트랜잭션 방지
      PGConnection pg = conn.unwrap(PGConnection.class);
      try (var st = conn.createStatement()) {
        st.execute("LISTEN outbox_new");
      }
      while (running.getAsBoolean()) {
        var notes = pg.getNotifications((int) pollTimeout.toMillis());
        if (notes != null && notes.length > 0) {
          onSignal.run();
        }
      }
    } finally {
      // 전용 커넥션을 명시적으로 닫아 TCP 소켓을 해제한다.
      if (conn != null) {
        try {
          conn.close();
        } catch (Exception closeEx) {
          log.debug("outbox listener connection close error (ignored)", closeEx);
        }
      }
    }
  }
}
