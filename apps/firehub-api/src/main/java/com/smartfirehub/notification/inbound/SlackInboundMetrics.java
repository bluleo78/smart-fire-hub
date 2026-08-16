package com.smartfirehub.notification.inbound;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Slack inbound 처리 관측 메트릭.
 *
 * <p>스펙 10장 요구사항: - slack_inbound_received_total: 수신 이벤트 총계 -
 * slack_inbound_processing_duration_seconds: dispatch 처리 소요 시간 - slack_inbound_unmapped_user_total:
 * 미매핑 사용자 차단 카운트 - slack_inbound_tenant_unresolved_total: 테넌트 해석 실패로 조용히 버린 이벤트 수
 */
@Component
public class SlackInboundMetrics {

  private final Counter received;
  private final Timer processingDuration;
  private final Counter unmappedUser;
  private final Counter tenantUnresolved;

  public SlackInboundMetrics(MeterRegistry registry) {
    this.received =
        Counter.builder("slack_inbound_received_total")
            .description("Slack inbound dispatch 호출 횟수")
            .register(registry);
    this.processingDuration =
        Timer.builder("slack_inbound_processing_duration_seconds")
            .description("Slack inbound dispatch 전체 처리 시간")
            .register(registry);
    this.unmappedUser =
        Counter.builder("slack_inbound_unmapped_user_total")
            .description("미매핑 Slack 사용자로부터의 메시지 수")
            .register(registry);
    // 테넌트 해석 실패는 의도적으로 조용한 실패 모드(외부에 team_id 존재 여부를 흘리지 않기 위해)라,
    // 지표가 없으면 "설치가 안 된다" 제보가 왔을 때 해석 단계인지 그 뒤인지 로그 grep 말고는
    // 구분할 수단이 없다. received 와 짝을 이루는 카운터를 둔다.
    this.tenantUnresolved =
        Counter.builder("slack_inbound_tenant_unresolved_total")
            .description("team_id 로 테넌트를 해석하지 못해 조용히 버린 이벤트 수")
            .register(registry);
  }

  public void incrementReceived() {
    received.increment();
  }

  public void recordProcessingDuration(Duration elapsed) {
    processingDuration.record(elapsed);
  }

  public void incrementUnmappedUser() {
    unmappedUser.increment();
  }

  /** 미등록·해지된 team_id 라 테넌트를 해석하지 못하고 이벤트를 폐기했을 때. */
  public void incrementTenantUnresolved() {
    tenantUnresolved.increment();
  }
}
