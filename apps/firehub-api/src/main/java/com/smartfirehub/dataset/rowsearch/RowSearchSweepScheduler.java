package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분 주기로 모든 활성 테넌트의 행 검색 색인을 원본과 맞춘다(설계 Q5=a: 쓰기 경로를 건드리지 않는 스윕).
 *
 * <p>TenantScopedRunner 는 ThreadLocal 만 세우고 트랜잭션은 열지 않는다 — DB 접근은 전부 @Transactional 저장소를
 * 거친다.
 */
@Slf4j
@Component
public class RowSearchSweepScheduler {

  private final TenantScopedRunner tenantRunner;
  private final SearchIndexStateRepository states;
  private final RowSearchSyncService syncService;
  private final boolean enabled;

  /** 스윕 활성 여부는 설정값으로 받는다(테스트·일부 환경에서 배경 스윕을 끈다). */
  public RowSearchSweepScheduler(
      TenantScopedRunner tenantRunner,
      SearchIndexStateRepository states,
      RowSearchSyncService syncService,
      @Value("${row-search.sync.enabled:true}") boolean enabled) {
    this.tenantRunner = tenantRunner;
    this.states = states;
    this.syncService = syncService;
    this.enabled = enabled;
  }

  /** 테넌트마다 동기화 대상 데이터셋을 조회해 하나씩 동기화한다. 비활성 설정이면 아무것도 하지 않는다. */
  @Scheduled(
      initialDelayString = "${row-search.sync.initial-delay-ms:60000}",
      fixedDelayString = "${row-search.sync.interval-ms:60000}")
  public void sweep() {
    if (!enabled) return;
    tenantRunner.forEachActiveTenant(
        tenantId -> {
          for (Long datasetId : states.findDueDatasetIds()) {
            try {
              syncService.sync(datasetId);
            } catch (RuntimeException e) {
              // 한 데이터셋 실패가 같은 테넌트의 나머지를 막지 않게 한다.
              log.error("행 검색 스윕 실패: tenant={}, dataset={}", tenantId, datasetId, e);
            }
          }
        });
  }
}
