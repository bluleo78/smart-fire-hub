package com.smartfirehub.notification.service;

import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Postgres LISTEN outbox_new 루프. NOTIFY 수신 시 {@link NotificationDispatchWorker#onNotify()}
 * 즉시 호출. 실패 시 5초 후 재시도, 30초 타임아웃으로 헬스체크 겸 룸프가 죽지 않도록 함.
 *
 * <p>단일 application-scoped daemon thread로 실행. feature flag OFF면 start되지 않음.
 */
@Component
public class OutboxListenerLoop {

    private static final Logger log = LoggerFactory.getLogger(OutboxListenerLoop.class);

    private final DataSource dataSource;
    private final NotificationDispatchWorker worker;
    private final boolean listenEnabled;
    private final boolean outboxEnabled;
    private volatile boolean running = true;

    public OutboxListenerLoop(DataSource dataSource,
                              NotificationDispatchWorker worker,
                              @Value("${notification.worker.listen_notify:true}") boolean listenEnabled,
                              @Value("${notification.outbox.enabled:false}") boolean outboxEnabled) {
        this.dataSource = dataSource;
        this.worker = worker;
        this.listenEnabled = listenEnabled;
        this.outboxEnabled = outboxEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!outboxEnabled || !listenEnabled) {
            log.info("OutboxListenerLoop disabled (outboxEnabled={}, listenEnabled={})",
                    outboxEnabled, listenEnabled);
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

    private void loop() {
        while (running) {
            var conn = DataSourceUtils.getConnection(dataSource);
            try {
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
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }
}
