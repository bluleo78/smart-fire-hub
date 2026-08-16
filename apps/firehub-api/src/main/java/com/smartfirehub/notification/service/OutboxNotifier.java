package com.smartfirehub.notification.service;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Postgres NOTIFY 발행으로 워커를 즉시 깨움. 실패해도 30초 폴백 폴링이 처리하므로 warn log만 남기고 조용히 실패.
 *
 * <p><b>테넌트 배선 제외(P2-f)</b>: {@code NOTIFY} 만 발행하고 도메인 행을 읽거나 쓰지 않으므로 RLS
 * 의 영향을 받지 않는다. 깨어난 워커가 자기 테넌트 스코프를 스스로 연다.
 */
@Component
public class OutboxNotifier {

  private static final Logger log = LoggerFactory.getLogger(OutboxNotifier.class);

  private final DataSource dataSource;

  public OutboxNotifier(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void notifyOutboxNew() {
    try (var conn = dataSource.getConnection();
        var st = conn.createStatement()) {
      st.execute("NOTIFY outbox_new");
    } catch (Exception e) {
      log.warn("Failed to NOTIFY outbox_new: {}", e.getMessage());
    }
  }
}
