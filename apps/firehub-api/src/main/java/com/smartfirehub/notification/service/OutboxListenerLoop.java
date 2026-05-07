package com.smartfirehub.notification.service;

import java.sql.Connection;
import java.sql.DriverManager;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Postgres LISTEN outbox_new 루프. NOTIFY 수신 시 {@link NotificationDispatchWorker#onNotify()} 즉시 호출.
 * 실패 시 5초 후 재시도, 30초 타임아웃으로 헬스체크 겸 루프가 죽지 않도록 함.
 *
 * <p>단일 application-scoped daemon thread로 실행. feature flag OFF면 start되지 않음.
 *
 * <p>LISTEN 전용 커넥션은 HikariCP 풀 외부에서 {@link DriverManager#getConnection}으로 직접 획득한다.
 * {@code DataSourceUtils.getConnection()}을 사용하면 루프 실행 내내 풀 커넥션 1개를 점유하여
 * 커넥션 풀 고갈(#174)이 발생하므로, 풀을 거치지 않는 전용 소켓 커넥션을 사용한다.
 */
@Component
public class OutboxListenerLoop {

  private static final Logger log = LoggerFactory.getLogger(OutboxListenerLoop.class);

  /** LISTEN 전용 커넥션 획득에 사용할 JDBC 접속 정보. */
  private final DataSourceProperties dataSourceProperties;
  private final NotificationDispatchWorker worker;
  private final boolean listenEnabled;
  private final boolean outboxEnabled;
  private volatile boolean running = true;

  public OutboxListenerLoop(
      DataSourceProperties dataSourceProperties,
      NotificationDispatchWorker worker,
      @Value("${notification.worker.listen_notify:true}") boolean listenEnabled,
      @Value("${notification.outbox.enabled:false}") boolean outboxEnabled) {
    this.dataSourceProperties = dataSourceProperties;
    this.worker = worker;
    this.listenEnabled = listenEnabled;
    this.outboxEnabled = outboxEnabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    if (!outboxEnabled || !listenEnabled) {
      log.info(
          "OutboxListenerLoop disabled (outboxEnabled={}, listenEnabled={})",
          outboxEnabled,
          listenEnabled);
      return;
    }
    Thread t = new Thread(this::loop, "outbox-listener");
    t.setDaemon(true);
    t.start();
  }

  /** 런타임 중지 훅 (테스트/종료용). */
  public void stop() {
    running = false;
  }

  /**
   * LISTEN 루프 본체. 재시도마다 DriverManager로 풀 외부 전용 커넥션을 획득하고,
   * 루프 종료 또는 예외 발생 시 반드시 커넥션을 close한다.
   */
  private void loop() {
    while (running) {
      // HikariCP 풀을 거치지 않고 DriverManager로 전용 LISTEN 커넥션 직접 획득.
      // 이 커넥션은 풀 대역에 포함되지 않으므로 풀 고갈을 유발하지 않는다.
      Connection conn = null;
      try {
        conn = DriverManager.getConnection(
            dataSourceProperties.getUrl(),
            dataSourceProperties.getUsername(),
            dataSourceProperties.getPassword());
        conn.setAutoCommit(true); // LISTEN은 트랜잭션 불필요; idle 트랜잭션 방지
        PGConnection pg = conn.unwrap(PGConnection.class);
        try (var st = conn.createStatement()) {
          st.execute("LISTEN outbox_new");
        }
        while (running) {
          var notes = pg.getNotifications(30_000);
          if (notes != null && notes.length > 0) {
            worker.onNotify();
          }
        }
      } catch (Exception e) {
        log.warn("outbox listener loop error, retry in 5s", e);
        try {
          Thread.sleep(5_000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
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
}
