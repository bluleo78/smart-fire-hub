package com.smartfirehub.proactive.service;

import com.smartfirehub.global.execution.ExecutionMessages;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 하트비트가 끊긴 프로액티브 실행을 FAILED 로 닫아 상세 화면의 무한 폴링을 끝낸다(WD-258). */
@Component
@Slf4j
public class ProactiveExecutionSweeper {

  private final ProactiveJobExecutionRepository executionRepository;
  private final ProactiveExecutionRegistry registry;
  private final TenantScopedRunner tenantScopedRunner;
  private final Duration staleAfter;

  public ProactiveExecutionSweeper(
      ProactiveJobExecutionRepository executionRepository,
      ProactiveExecutionRegistry registry,
      TenantScopedRunner tenantScopedRunner,
      @Value("${app.proactive.stale-after:PT5M}") Duration staleAfter) {
    this.executionRepository = executionRepository;
    this.registry = registry;
    this.tenantScopedRunner = tenantScopedRunner;
    this.staleAfter = staleAfter;
  }

  /**
   * 활성 실행이 있는 테넌트만 순회한다(V149 definer 함수 — 실행 표에서 유도하므로 정지된 워크스페이스의 고아 실행도 빠지지 않는다). 매분 모든 테넌트를 돌지
   * 않는다. 순회기가 실패를 격리한다.
   */
  @Scheduled(
      fixedDelayString = "${app.proactive.stale-sweep-interval:PT1M}",
      initialDelayString = "${app.proactive.stale-sweep-interval:PT1M}")
  public void sweep() {
    tenantScopedRunner.forEachTenant(
        executionRepository.tenantIdsWithActiveExecutions(), this::sweepTenant);
  }

  /** 호출자가 테넌트 컨텍스트를 세운 상태에서 오래된 활성 실행을 조건부로 닫는다. */
  public int sweepTenant(long tenantId) {
    LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minus(staleAfter);
    int closed = 0;
    for (Long executionId : executionRepository.findStaleExecutions(tenantId, threshold)) {
      // 하트비트 쓰기가 지연됐어도 이 JVM에서 돌아가는 실행은 중단으로 분류하지 않는다.
      if (registry.isRegistered(executionId)) continue;
      // 다른 정리기 또는 러너가 먼저 닫았으면 갱신 0행이므로 중복 기록하지 않는다.
      if (!executionRepository.updateError(executionId, ExecutionMessages.INTERRUPTED)) {
        continue;
      }
      log.warn("하트비트가 끊긴 프로액티브 실행을 중단 처리했습니다: execution={}, tenant={}", executionId, tenantId);
      closed++;
    }
    return closed;
  }
}
