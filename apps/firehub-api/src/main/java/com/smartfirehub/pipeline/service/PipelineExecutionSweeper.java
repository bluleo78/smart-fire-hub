package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.execution.ExecutionMessages;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository.StaleExecution;
import com.smartfirehub.pipeline.repository.PipelineRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하트비트가 끊긴(주인 api 가 죽은) 파이프라인 실행을 「중단됨」으로 닫는다(WD-230).
 *
 * <p>닫지 않으면 RUNNING 행이 영구히 남아 고아 스테이징 정리 게이트({@code hasRunningExecution})를 막는다. 닫을 때는 실행을 FAILED 로,
 * 단계를 마감하고, 완료 이벤트를 {@code interrupted=true} 로 한 번 낸다 — 체인 트리거는 발화하지 않고 알림만 「중단」 문구로 나간다.
 */
@Slf4j
@Component
public class PipelineExecutionSweeper {

  private final PipelineExecutionRepository executionRepository;
  private final PipelineRepository pipelineRepository;
  private final PipelineExecutionRegistry registry;
  private final TenantScopedRunner tenantScopedRunner;
  private final ApplicationEventPublisher eventPublisher;
  private final Duration staleAfter;

  public PipelineExecutionSweeper(
      PipelineExecutionRepository executionRepository,
      PipelineRepository pipelineRepository,
      PipelineExecutionRegistry registry,
      TenantScopedRunner tenantScopedRunner,
      ApplicationEventPublisher eventPublisher,
      @Value("${app.pipeline.stale-after:PT5M}") Duration staleAfter) {
    this.executionRepository = executionRepository;
    this.pipelineRepository = pipelineRepository;
    this.registry = registry;
    this.tenantScopedRunner = tenantScopedRunner;
    this.eventPublisher = eventPublisher;
    this.staleAfter = staleAfter;
  }

  /**
   * 활성 테넌트마다 오래된 실행을 정리한다. 스케줄 스레드에서 돈다(러너 스레드 아님, 스펙 §5.1). 초기 지연을 간격과 같게 둬 기동 직후 즉시 돌지 않게 한다(재시작
   * 직후 살아 있는 다른 인스턴스의 실행을 하트비트 갱신 전에 건드리지 않는다).
   */
  @Scheduled(
      fixedDelayString = "${app.pipeline.stale-sweep-interval:PT1M}",
      initialDelayString = "${app.pipeline.stale-sweep-interval:PT1M}")
  public void sweep() {
    tenantScopedRunner.forEachActiveTenant(this::sweepTenant);
  }

  /**
   * 한 테넌트의 오래된 실행을 정리하고 정리한 수를 돌려준다. 호출자는 그 테넌트 컨텍스트 안에 있어야 한다(RLS) — 이벤트도 같은 컨텍스트에서 발행해, 비동기 리스너가
   * 제출 시점의 테넌트를 승계한다.
   */
  public int sweepTenant(long tenantId) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    LocalDateTime threshold = now.minus(staleAfter);
    int closed = 0;
    for (StaleExecution stale : executionRepository.findStaleExecutions(tenantId, threshold)) {
      // 이 인스턴스가 쥔 실행은 하트비트가 밀렸을 뿐 살아 있다 — 건드리지 않는다.
      if (registry.isRegistered(stale.executionId())) {
        continue;
      }
      // 실행 FAILED + 단계 닫기를 한 트랜잭션으로 — 조건부 쓰기라 그 사이 마감·취소됐거나 다른 인스턴스 정리기가 먼저 닫았으면 false 이고 아무것도 바꾸지
      // 않으며 이벤트도 내지 않는다. 이벤트 발행은 커밋 뒤(이 호출이 돌아온 뒤)다.
      if (!executionRepository.failInterruptedAndCloseSteps(
          stale.executionId(), now, ExecutionMessages.INTERRUPTED)) {
        continue;
      }
      Long createdBy = pipelineRepository.findCreatedByIdById(stale.pipelineId()).orElse(null);
      TenantContext.runScoped(
          tenantId,
          () ->
              eventPublisher.publishEvent(
                  new PipelineCompletedEvent(
                      stale.pipelineId(), stale.executionId(), "FAILED", createdBy, true)));
      log.warn(
          "하트비트가 끊긴 파이프라인 실행을 중단 처리했습니다: execution={}, pipeline={}, tenant={}",
          stale.executionId(),
          stale.pipelineId(),
          tenantId);
      closed++;
    }
    return closed;
  }
}
