package com.smartfirehub.notification.service;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 좀비 회복 스위퍼. SENDING 상태로 N분 이상 묶인 행을 PENDING으로 되돌려 다른 워커가 재claim
 * 하도록 한다. 워커 프로세스 크래시·타임아웃으로 lease가 갱신되지 않은 행을 주기적으로 풀어준다.
 *
 * <p>여러 인스턴스에서 동시에 실행돼도 SKIP LOCKED semantics 덕에 문제없지만 중복 작업이므로
 * 5분에 한 번만 실행. Micrometer 카운터는 Task 13에서 추가.
 */
@Component
public class OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

    private final NotificationOutboxRepository outboxRepo;
    private final Duration zombieAge;
    private final boolean enabled;

    public OutboxSweeper(NotificationOutboxRepository outboxRepo,
                         @Value("${notification.worker.zombie_age_minutes:5}") int zombieAgeMin,
                         @Value("${notification.outbox.enabled:false}") boolean enabled) {
        this.outboxRepo = outboxRepo;
        this.zombieAge = Duration.ofMinutes(zombieAgeMin);
        this.enabled = enabled;
    }

    /** 5분 주기로 실행 (fixedDelay). feature flag OFF면 no-op. */
    @Scheduled(fixedDelayString = "${notification.worker.sweeper_interval_ms:300000}")
    public void sweep() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minus(zombieAge);
        int recovered = outboxRepo.reclaimZombies(cutoff);
        if (recovered > 0) {
            log.warn("OutboxSweeper recovered {} zombie rows older than {}", recovered, cutoff);
        }
    }
}
