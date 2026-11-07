package com.smartfirehub.global.jobrunr;

import java.util.concurrent.atomic.AtomicInteger;
import org.jobrunr.server.BackgroundJobServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * JobRunr BackgroundJobServer 감시자(WD-555).
 *
 * <p>왜 필요한가: 저장소 쓰기 실패(커넥션 타임아웃 등)가 나면 JobRunr 의 ServerZooKeeper 가 "unrecoverable error" 로 서버를 스스로
 * 내리고 되살리지 않는다. 그러면 임포트·예약 작업이 PENDING 으로 영구 정지한다(사용자에게 오류도 없다). 이 감시자가 주기적으로 {@link
 * BackgroundJobServer#isRunning()} 을 보고 꺼져 있으면 다시 켠다.
 *
 * <p>JobRunr 서버가 꺼진 환경({@code org.jobrunr.background-job-server.enabled} 가 true 가 아님 — 테스트 등)에서는 빈
 * 자체가 만들어지지 않는다.
 */
@Component
@ConditionalOnProperty(name = "org.jobrunr.background-job-server.enabled", havingValue = "true")
public class JobRunrServerWatchdog {

  private static final Logger log = LoggerFactory.getLogger(JobRunrServerWatchdog.class);

  /** 연속으로 "꺼짐" 을 몇 번 봐야 재시작하는가. 기동·종료 도중의 일시적 false 를 오탐하지 않으려는 값이다. */
  static final int REQUIRED_CONSECUTIVE_DOWN = 2;

  private final BackgroundJobServer server;
  private final AtomicInteger consecutiveDown = new AtomicInteger();
  private volatile boolean closing;

  public JobRunrServerWatchdog(BackgroundJobServer server) {
    this.server = server;
  }

  /** 앱 종료 중에는 JobRunr 도 정상적으로 내려가므로 되살리지 않는다. */
  @EventListener(ContextClosedEvent.class)
  void onContextClosed() {
    closing = true;
  }

  /**
   * 한 번 점검한다. 꺼짐이 연속 {@value #REQUIRED_CONSECUTIVE_DOWN}회 확인되면 stop()(남은 자원 정리, 이미 멈췄으면 no-op) 후
   * start() 한다. 실패하면 로그만 남기고 다음 주기에 다시 시도한다(예외를 밖으로 던지면 스케줄러가 아닌 로그만 남기므로 여기서 삼킨다).
   */
  @Scheduled(
      fixedDelayString = "${firehub.jobrunr.watchdog.interval-ms:30000}",
      initialDelayString = "${firehub.jobrunr.watchdog.interval-ms:30000}")
  public void check() {
    if (closing) {
      return;
    }
    if (server.isRunning()) {
      consecutiveDown.set(0);
      return;
    }
    if (consecutiveDown.incrementAndGet() < REQUIRED_CONSECUTIVE_DOWN) {
      return;
    }
    log.warn("JobRunr BackgroundJobServer 가 꺼져 있다 — 재시작을 시도한다.");
    try {
      server.stop();
      server.start();
      consecutiveDown.set(0);
      log.info("JobRunr BackgroundJobServer 재시작을 요청했다(start 는 비동기라 성공 여부는 다음 점검에서 확인한다).");
    } catch (RuntimeException e) {
      log.error("JobRunr BackgroundJobServer 재시작 실패 — 다음 주기에 다시 시도한다.", e);
    }
  }
}
