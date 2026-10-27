package com.smartfirehub.proactive.service;

import com.smartfirehub.global.execution.TenantHeartbeat;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 러너가 오래 막혀도 살아 있는 프로액티브 실행의 하트비트를 별도 스케줄러 스레드에서 갱신한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProactiveHeartbeatService {

  private final ProactiveExecutionRegistry registry;
  private final ProactiveJobExecutionRepository executionRepository;

  /** 테넌트별 RLS 컨텍스트에서 활성 실행만 갱신한다. */
  @Scheduled(
      fixedDelayString = "${app.proactive.heartbeat-interval:PT1M}",
      initialDelayString = "${app.proactive.heartbeat-interval:PT1M}")
  public void beat() {
    TenantHeartbeat.beat(
        registry,
        executionRepository::touchHeartbeat,
        (tenantId, ids, e) ->
            log.warn("프로액티브 하트비트 갱신 실패 (tenant={}, executions={})", tenantId, ids, e));
  }
}
