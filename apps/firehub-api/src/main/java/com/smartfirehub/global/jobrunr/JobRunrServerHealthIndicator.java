package com.smartfirehub.global.jobrunr;

import org.jobrunr.server.BackgroundJobServer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * JobRunr BackgroundJobServer 상태를 Actuator 헬스({@code jobRunrServer})에 반영한다(WD-555). 꺼져 있으면 DOWN —
 * 서버가 꺼진 채로는 임포트·예약 작업이 조용히 멈추므로 외부에서 알 수 있어야 한다. 서버가 비활성인 환경에서는 빈이 없다.
 */
@Component("jobRunrServerHealthIndicator")
@ConditionalOnProperty(name = "org.jobrunr.background-job-server.enabled", havingValue = "true")
public class JobRunrServerHealthIndicator implements HealthIndicator {

  private final BackgroundJobServer server;

  public JobRunrServerHealthIndicator(BackgroundJobServer server) {
    this.server = server;
  }

  @Override
  public Health health() {
    return server.isRunning()
        ? Health.up().build()
        : Health.down().withDetail("reason", "JobRunr BackgroundJobServer 가 실행 중이 아니다").build();
  }
}
