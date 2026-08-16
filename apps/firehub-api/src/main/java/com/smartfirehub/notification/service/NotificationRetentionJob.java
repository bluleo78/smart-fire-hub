package com.smartfirehub.notification.service;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.notification.repository.OAuthStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 알림 관련 테이블의 일일 retention 잡.
 *
 * <p>기본 정책 (스펙 10장): - SENT: 90일 경과 삭제 - PERMANENT_FAILURE: 180일 경과 삭제 - oauth_state: 만료분 즉시 삭제
 *
 * <p>매일 04:30(서버 로컬 시간)에 실행. feature flag OFF면 no-op.
 */
@Component
public class NotificationRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(NotificationRetentionJob.class);

  private final NotificationOutboxRepository outboxRepo;
  private final OAuthStateRepository oauthStateRepo;
  private final TenantScopedRunner tenantRunner;
  private final boolean enabled;
  private final int sentRetentionDays;
  private final int permanentFailureRetentionDays;

  public NotificationRetentionJob(
      NotificationOutboxRepository outboxRepo,
      OAuthStateRepository oauthStateRepo,
      TenantScopedRunner tenantRunner,
      @Value("${notification.outbox.enabled:false}") boolean enabled,
      @Value("${notification.retention.sent_days:90}") int sentRetentionDays,
      @Value("${notification.retention.permanent_failure_days:180}")
          int permanentFailureRetentionDays) {
    this.outboxRepo = outboxRepo;
    this.oauthStateRepo = oauthStateRepo;
    this.tenantRunner = tenantRunner;
    this.enabled = enabled;
    this.sentRetentionDays = sentRetentionDays;
    this.permanentFailureRetentionDays = permanentFailureRetentionDays;
  }

  /**
   * <b>테넌트 배선(P2-f)</b>: outbox 보존 삭제는 테넌트 술어 없는 DELETE 라, 정책(V107) 이후
   * 컨텍스트 없이 돌면 <b>에러 없이 0행</b>을 지우고 {@code sent=0, permanentFailure=0} 만 남긴다 —
   * 보존 정책이 조용히 죽는다. 그래서 {@code outbox_tenant_ids('{SENT,PERMANENT_FAILURE}')} 로
   * 지울 것이 있는 테넌트만 얻어 테넌트마다 스코프를 연다.
   *
   * <p>순회·격리·로깅은 {@code TenantScopedRunner.forEachTenant} 가 맡고 <b>목록만 여기서 정한다</b>.
   * {@code forEachActiveTenant} 를 쓰지 않는 이유(R4): ACTIVE 테넌트만 돌면 <b>비활성·정지
   * 테넌트의 행이 영원히 삭제되지 않는 누수</b>가 생긴다. 보존 정책에서 이 누수는 특히 나쁘다 —
   * 해지된 테넌트의 알림 페이로드가 무기한 남는다. R4 가 배제한 것은 출처이지 루프가 아니다.
   *
   * <p>{@code oauth_state} 삭제는 <b>순회 밖</b>에 그대로 둔다. R7 에 따라 이 테이블에는 RLS 가
   * 없고(콜백이 컨텍스트 없이 {@code consume} 해야 테넌트를 되찾을 수 있다), 만료 삭제는 테넌트와
   * 무관한 TTL 정리라 순회에 넣으면 테넌트 수만큼 같은 전역 DELETE 를 반복할 뿐이다.
   */
  @Scheduled(cron = "${notification.retention.cron:0 30 4 * * *}")
  public void cleanup() {
    if (!enabled) return;
    Instant now = Instant.now();
    Instant sentCutoff = now.minus(Duration.ofDays(sentRetentionDays));
    Instant failCutoff = now.minus(Duration.ofDays(permanentFailureRetentionDays));

    // 순회·격리·로깅은 러너가 소유한다(출처만 여기서 정한다 — R4). 한 테넌트의 실패로 나머지
    // 테넌트의 보존 정책이 하루 더 밀리지 않는다.
    AtomicInteger sentDeleted = new AtomicInteger();
    AtomicInteger failDeleted = new AtomicInteger();
    tenantRunner.forEachTenant(
        outboxRepo.tenantIdsWithStatus("SENT", "PERMANENT_FAILURE"),
        tenantId -> {
          // 두 삭제를 한 스코프 안에서 처리한다 — 같은 테넌트에 두 번 컨텍스트를 열 이유가 없다.
          // OutboxSweeper 와 마찬가지로 테넌트 술어는 명시하지 않는다(멱등 집합 연산).
          sentDeleted.addAndGet(outboxRepo.deleteSentOlderThan(sentCutoff));
          failDeleted.addAndGet(outboxRepo.deletePermanentFailureOlderThan(failCutoff));
        });
    int oauthDeleted = oauthStateRepo.deleteExpired();

    log.info(
        "NotificationRetentionJob cleanup: sent={}, permanentFailure={}, oauthState={}",
        sentDeleted.get(),
        failDeleted.get(),
        oauthDeleted);
  }
}
