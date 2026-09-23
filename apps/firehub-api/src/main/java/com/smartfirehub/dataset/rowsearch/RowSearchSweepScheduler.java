package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private final long maxContinuousNanos;

  /**
   * 스윕 활성 여부와 연속 실행 상한은 설정값으로 받는다(테스트·일부 환경에서 배경 스윕을 끈다).
   *
   * <p>연속 실행 상한: 남은 행이 있는 데이터셋이 있으면 주기 대기 없이 라운드를 이어 돌되, 이 시간을 넘기면 멈추고
   * 다음 주기로 넘긴다. 스케줄러 풀(4스레드)을 공유하는 다른 잡을 오래 막지 않기 위해서다.
   */
  public RowSearchSweepScheduler(
      TenantScopedRunner tenantRunner,
      SearchIndexStateRepository states,
      RowSearchSyncService syncService,
      @Value("${row-search.sync.enabled:true}") boolean enabled,
      @Value("${row-search.sync.max-continuous-ms:600000}") long maxContinuousMs) {
    this.tenantRunner = tenantRunner;
    this.states = states;
    this.syncService = syncService;
    this.enabled = enabled;
    this.maxContinuousNanos = Duration.ofMillis(maxContinuousMs).toNanos();
  }

  /**
   * 테넌트마다 동기화 대상 데이터셋을 조회해 하나씩 동기화한다. 비활성 설정이면 아무것도 하지 않는다.
   *
   * <p>남은 행이 있는(PARTIAL) 데이터셋이 있으면 주기 대기 없이 연속 상한 안에서 다음 라운드를 돈다(#715). 라운드
   * 단위인 이유는 공정성이다 — 한 데이터셋을 끝까지 몰지 않고 모든 대상에 한 회차씩 돌려준다. 라운드마다 대상을 다시
   * 조회해 그 사이 켜진 데이터셋도 끼우되, 이번 스윕에서 이미 결론 난(PARTIAL 외) 데이터셋은 건너뛴다 — 완료된
   * 데이터셋을 다시 돌면 변경이 없어도 COUNT·삭제분 정리 스캔을 매 라운드 반복한다. SKIPPED·FAILED 는 곧장 다시
   * 돌아도 헛돌기만 하므로 연장 사유가 아니다. 종료 중 인터럽트되면 라운드를 더 잇지 않는다.
   */
  @Scheduled(
      initialDelayString = "${row-search.sync.initial-delay-ms:60000}",
      fixedDelayString = "${row-search.sync.interval-ms:60000}")
  public void sweep() {
    if (!enabled) return;
    long startedAt = System.nanoTime();
    Set<Long> settled = new HashSet<>();
    boolean hasMore;
    do {
      hasMore = sweepRound(settled);
    } while (hasMore
        && System.nanoTime() - startedAt < maxContinuousNanos
        && !Thread.currentThread().isInterrupted());
  }

  /**
   * 모든 활성 테넌트의 대상 데이터셋을 한 회차씩 동기화한다. {@code settled} 에 든 데이터셋은 건너뛰고, PARTIAL 이
   * 아닌 결과(완료·건너뜀·실패·예외)는 거기에 더한다. 남은 행이 있는 데이터셋이 있었으면 true.
   */
  private boolean sweepRound(Set<Long> settled) {
    AtomicBoolean hasMore = new AtomicBoolean(false);
    tenantRunner.forEachActiveTenant(
        tenantId -> {
          for (Long datasetId : states.findDueDatasetIds()) {
            if (settled.contains(datasetId)) continue;
            try {
              if (syncService.sync(datasetId) == RowSearchSyncService.Outcome.PARTIAL) {
                hasMore.set(true);
                continue;
              }
            } catch (RuntimeException e) {
              // 한 데이터셋 실패가 같은 테넌트의 나머지를 막지 않게 한다.
              log.error("행 검색 스윕 실패: tenant={}, dataset={}", tenantId, datasetId, e);
            }
            settled.add(datasetId);
          }
        });
    return hasMore.get();
  }
}
