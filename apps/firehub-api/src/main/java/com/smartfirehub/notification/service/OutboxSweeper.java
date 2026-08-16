package com.smartfirehub.notification.service;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 좀비 회복 스위퍼. SENDING 상태로 N분 이상 묶인 행을 PENDING으로 되돌려 다른 워커가 재claim 하도록 한다. 워커 프로세스 크래시·타임아웃으로
 * lease가 갱신되지 않은 행을 주기적으로 풀어준다.
 *
 * <p>여러 인스턴스에서 동시에 실행돼도 SKIP LOCKED semantics 덕에 문제없지만 중복 작업이므로 5분에 한 번만 실행. Micrometer 카운터는 Task
 * 13에서 추가.
 */
@Component
public class OutboxSweeper {

  private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

  private final NotificationOutboxRepository outboxRepo;
  private final TenantScopedRunner tenantRunner;
  private final Duration zombieAge;
  private final boolean enabled;

  public OutboxSweeper(
      NotificationOutboxRepository outboxRepo,
      TenantScopedRunner tenantRunner,
      @Value("${notification.worker.zombie_age_minutes:5}") int zombieAgeMin,
      @Value("${notification.outbox.enabled:false}") boolean enabled) {
    this.outboxRepo = outboxRepo;
    this.tenantRunner = tenantRunner;
    this.zombieAge = Duration.ofMinutes(zombieAgeMin);
    this.enabled = enabled;
  }

  /**
   * 5분 주기로 실행 (fixedDelay). feature flag OFF면 no-op.
   *
   * <p><b>테넌트 배선(P2-f)</b>: {@code reclaimZombies} 는 테넌트 술어 없는 UPDATE 라, 정책(V107)
   * 이후 컨텍스트 없이 돌면 <b>예외도 로그도 없이 0행</b>을 회수하고 좀비가 영원히 갇힌다. 그래서
   * {@code outbox_tenant_ids('{SENDING}')} 로 회수할 것이 있는 테넌트만 얻어 테넌트마다 스코프를
   * 연다.
   *
   * <p>순회·격리·로깅은 {@code TenantScopedRunner.forEachTenant} 가 맡고 <b>목록만 여기서 정한다</b>.
   * {@code forEachActiveTenant} 를 쓰지 않는 이유(R4): 그쪽은 ACTIVE 테넌트만 돌아 <b>비활성·정지
   * 테넌트의 좀비 행이 영원히 회수되지 않는 누수</b>가 생긴다. R4 가 배제한 것은 출처이지 루프가
   * 아니다 — 이 목록은 테이블에 실제로 존재하는 테넌트에서 유도하므로 그 구멍이 없다.
   *
   * <p>{@code claimDue} 와 달리 여기에는 테넌트 술어를 <b>명시하지 않는다</b>. 이 연산은 결과가
   * 특정 테넌트에 귀속되지 않는 멱등 집합 연산이라, 정책 이전에 첫 순회가 전부 회수하고 나머지
   * 순회가 0을 반환해도 최종 상태와 합계가 같다. 정책 이후에는 정책이 자동으로 좁힌다.
   */
  @Scheduled(
      // 기동 직후 1회 실행이 기본(0). 노브 사유는 NotificationDispatchWorker.pollOnce 주석 참조.
      initialDelayString = "${notification.scheduler.initial_delay_ms:0}",
      fixedDelayString = "${notification.worker.sweeper_interval_ms:300000}")
  public void sweep() {
    if (!enabled) return;
    Instant cutoff = Instant.now().minus(zombieAge);
    // 순회·격리·로깅은 러너가 소유한다(출처만 여기서 정한다 — R4). 합계는 콜백 밖 누산기로.
    // 한 테넌트의 실패는 러너가 삼키고 다음 테넌트로 간다(스케줄러는 5분 뒤에야 재시도).
    AtomicInteger recovered = new AtomicInteger();
    tenantRunner.forEachTenant(
        outboxRepo.tenantIdsWithStatus("SENDING"),
        tenantId -> recovered.addAndGet(outboxRepo.reclaimZombies(cutoff)));
    if (recovered.get() > 0) {
      log.warn("OutboxSweeper recovered {} zombie rows older than {}", recovered.get(), cutoff);
    }
  }
}
