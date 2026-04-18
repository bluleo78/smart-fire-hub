package com.smartfirehub.notification.service;

import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 알림 관련 테이블의 일일 retention 잡.
 *
 * <p>기본 정책 (스펙 10장):
 * - SENT: 90일 경과 삭제
 * - PERMANENT_FAILURE: 180일 경과 삭제
 * - oauth_state: 만료분 즉시 삭제
 *
 * <p>매일 04:30(서버 로컬 시간)에 실행. feature flag OFF면 no-op.
 */
@Component
public class NotificationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionJob.class);

    private final NotificationOutboxRepository outboxRepo;
    private final OAuthStateRepository oauthStateRepo;
    private final boolean enabled;
    private final int sentRetentionDays;
    private final int permanentFailureRetentionDays;

    public NotificationRetentionJob(NotificationOutboxRepository outboxRepo,
                                     OAuthStateRepository oauthStateRepo,
                                     @Value("${notification.outbox.enabled:false}") boolean enabled,
                                     @Value("${notification.retention.sent_days:90}") int sentRetentionDays,
                                     @Value("${notification.retention.permanent_failure_days:180}") int permanentFailureRetentionDays) {
        this.outboxRepo = outboxRepo;
        this.oauthStateRepo = oauthStateRepo;
        this.enabled = enabled;
        this.sentRetentionDays = sentRetentionDays;
        this.permanentFailureRetentionDays = permanentFailureRetentionDays;
    }

    @Scheduled(cron = "${notification.retention.cron:0 30 4 * * *}")
    public void cleanup() {
        if (!enabled) return;
        Instant now = Instant.now();

        int sentDeleted = outboxRepo.deleteSentOlderThan(
                now.minus(Duration.ofDays(sentRetentionDays)));
        int failDeleted = outboxRepo.deletePermanentFailureOlderThan(
                now.minus(Duration.ofDays(permanentFailureRetentionDays)));
        int oauthDeleted = oauthStateRepo.deleteExpired();

        log.info("NotificationRetentionJob cleanup: sent={}, permanentFailure={}, oauthState={}",
                sentDeleted, failDeleted, oauthDeleted);
    }
}
