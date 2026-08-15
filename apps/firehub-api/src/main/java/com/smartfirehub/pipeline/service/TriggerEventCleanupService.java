package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerEventCleanupService {

  private final TriggerEventRepository triggerEventRepository;
  private final TenantScopedRunner tenantScopedRunner;

  /**
   * 90일 이상 지난 트리거 이벤트 정리. 매일 03시 실행.
   *
   * <p><b>테넌트 순회(P2-b)</b>: 스케줄러에는 원 HTTP 요청이 없어 승계할 테넌트가 없다 — ACTIVE
   * 테넌트를 순회한다. 순회하지 않으면 RLS 가 trigger_event 를 전부 차단해 정리가 무동작이 된다.
   *
   * <p><b>트랜잭션</b>: {@code TriggerEventRepository} 는 클래스 레벨 {@code @Transactional} 이라
   * (P2-b Task 1) 호출마다 자기 트랜잭션을 열고 그 시점에 GUC 가 주입된다 — 여기서 {@code
   * TransactionTemplate} 을 덧붙일 필요가 없다(확인함).
   */
  // cron 을 프로퍼티로 뺀다 — 값을 하드코딩하면 라이브 검증에서 이 잡만 실행시킬 방법이 없다.
  // 기본값은 기존과 동일하므로 운영 동작은 바뀌지 않는다.
  @Scheduled(cron = "${firehub.trigger-event.cleanup.cron:0 0 3 * * *}")
  public void cleanupOldEvents() {
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> {
          int deleted = triggerEventRepository.deleteOlderThan(90);
          log.info("Cleaned up {} trigger events older than 90 days (tenant={})", deleted, tenantId);
        });
  }
}
