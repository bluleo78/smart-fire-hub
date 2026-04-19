package com.smartfirehub.notification.inbound;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Slack inbound 처리 관측 메트릭.
 *
 * <p>스펙 10장 요구사항:
 * - slack_inbound_received_total: 수신 이벤트 총계
 * - slack_inbound_processing_duration_seconds: dispatch 처리 소요 시간
 * - slack_inbound_unmapped_user_total: 미매핑 사용자 차단 카운트
 */
@Component
public class SlackInboundMetrics {

    private final Counter received;
    private final Timer processingDuration;
    private final Counter unmappedUser;

    public SlackInboundMetrics(MeterRegistry registry) {
        this.received = Counter.builder("slack_inbound_received_total")
                .description("Slack inbound dispatch 호출 횟수")
                .register(registry);
        this.processingDuration = Timer.builder("slack_inbound_processing_duration_seconds")
                .description("Slack inbound dispatch 전체 처리 시간")
                .register(registry);
        this.unmappedUser = Counter.builder("slack_inbound_unmapped_user_total")
                .description("미매핑 Slack 사용자로부터의 메시지 수")
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
}
