package com.smartfirehub.notification.metrics;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Notification Outbox 관측 메트릭 등록·접근 헬퍼.
 *
 * <p>Gauge: 채널별 PENDING 개수(OutboxRepository 조회 기반).
 * Counter/Timer: Worker에서 호출해 주입.
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;
    private final NotificationOutboxRepository outboxRepo;
    private final boolean outboxEnabled;

    public NotificationMetrics(MeterRegistry registry,
                                NotificationOutboxRepository outboxRepo,
                                @Value("${notification.outbox.enabled:false}") boolean outboxEnabled) {
        this.registry = registry;
        this.outboxRepo = outboxRepo;
        this.outboxEnabled = outboxEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerGauges() {
        if (!outboxEnabled) return;
        for (ChannelType ch : ChannelType.values()) {
            registry.gauge(
                    "notification_outbox_pending_count",
                    List.of(Tag.of("channel", ch.name())),
                    outboxRepo,
                    r -> r.countPending(ch));
        }
    }

    /** deliver 결과(SENT/TRANSIENT/PERMANENT_FAILURE) 소요 시간 기록. */
    public void recordDeliveryDuration(ChannelType channel, String statusTag, Duration elapsed) {
        Timer.builder("channel_delivery_duration_seconds")
                .tags(Tags.of("channel", channel.name(), "status", statusTag))
                .register(registry)
                .record(elapsed);
    }

    /** 영구 실패 카운트 증가. */
    public void incrementPermanentFailure(ChannelType channel, String reason) {
        Counter.builder("notification_outbox_permanent_failure_total")
                .tags(Tags.of("channel", channel.name(), "reason", reason))
                .register(registry)
                .increment();
    }

    /** 좀비 회복 카운트 증가. */
    public void incrementZombieRecovered(int delta) {
        Counter.builder("notification_outbox_zombie_recovered_total")
                .register(registry)
                .increment(delta);
    }
}
