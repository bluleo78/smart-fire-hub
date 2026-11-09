package com.smartfirehub.notification.service;

import com.smartfirehub.global.platformdb.OutboxWakeupChannel;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Postgres LISTEN outbox_new 루프. NOTIFY 수신 시 {@link NotificationDispatchWorker#onNotify()} 즉시 호출.
 * 실패 시 5초 후 재시도, 30초 타임아웃으로 헬스체크 겸 루프가 죽지 않도록 함.
 *
 * <p>단일 application-scoped daemon thread로 실행. feature flag OFF면 start되지 않음.
 *
 * <p>LISTEN 전용 커넥션은 HikariCP 풀 외부에서 {@link DriverManager#getConnection}으로 직접 획득한다. {@code
 * DataSourceUtils.getConnection()}을 사용하면 루프 실행 내내 풀 커넥션 1개를 점유하여 커넥션 풀 고갈(#174)이 발생하므로, 풀을 거치지 않는
 * 전용 소켓 커넥션을 사용한다.
 *
 * <p><b>테넌트 배선 제외(P2-f)</b>: 배경 스레드지만 도메인 행을 전혀 읽지 않고(LISTEN 만 한다) 트랜잭션 매니저 경로 자체가 없어 GUC 주입 지점이
 * 존재하지 않는다 — RLS 의 영향을 받지 않으므로 컨텍스트를 붙이지 않는다. 테넌트 스코프는 {@code onNotify} 가 부르는 {@link
 * NotificationDispatchWorker#runOneBatch()} 안에서 열린다.
 */
@Component
public class OutboxListenerLoop {

  private static final Logger log = LoggerFactory.getLogger(OutboxListenerLoop.class);

  /** 신호 구독 채널 — LISTEN 전용 연결은 구현이 풀 밖에서 직접 얻는다. */
  private final OutboxWakeupChannel channel;

  private final NotificationDispatchWorker worker;
  private final boolean listenEnabled;
  private final boolean outboxEnabled;
  private volatile boolean running = true;

  public OutboxListenerLoop(
      OutboxWakeupChannel channel,
      NotificationDispatchWorker worker,
      @Value("${notification.worker.listen_notify:true}") boolean listenEnabled,
      @Value("${notification.outbox.enabled:false}") boolean outboxEnabled) {
    this.channel = channel;
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
   * LISTEN 루프 본체. 채널의 listen 이 연결 오류로 던지면 5초 뒤 재시도한다(연결 획득·close 는 채널 구현이 책임진다). 스레드가 인터럽트되면 멈춘다 —
   * 꺼진 Tibero 채널처럼 인터럽트 플래그를 세운 채 곧바로 돌아오는 구현이 바쁜 루프를 만들지 않게 한다.
   */
  void loop() {
    while (running && !Thread.currentThread().isInterrupted()) {
      try {
        // 연결 수명(LISTEN 전용 소켓 획득·해제)은 채널 구현이 맡는다. 오류는 예외로 올라온다.
        channel.listen(() -> running, Duration.ofMillis(30_000), worker::onNotify);
      } catch (Exception e) {
        log.warn("outbox listener loop error, retry in 5s", e);
        try {
          Thread.sleep(5_000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
