package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이 인스턴스가 쥔 실행의 하트비트를 주기적으로 갱신한다(WD-230).
 *
 * <p>{@code @Scheduled} 스레드에서 돈다 — 러너 스레드가 아니다. Python·API 수집 한 호출이 최대 약 1시간 러너 스레드를 막을 수 있어(스펙
 * §5.1), 러너가 직접 하트비트를 찍으면 멀쩡히 도는 실행이 정리 대상으로 오인된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineHeartbeatService {

  private final PipelineExecutionRegistry registry;
  private final PipelineExecutionRepository executionRepository;

  /**
   * 등록부를 테넌트별로 묶어 각 테넌트 컨텍스트 안에서 하트비트를 갱신한다. {@code pipeline_execution} 은 RLS 대상이라 서로 다른 테넌트를 한 번에
   * 갱신하면 조용히 0행이 갱신된다. 한 테넌트가 실패해도 다음 테넌트는 계속한다.
   *
   * <p>초기 지연을 간격과 같게 둔다 — 기동 직후 즉시 도는 것을 막고(테스트는 간격을 길게 줘 스케줄러가 끼어들지 않게 한다), 등록부가 아직 빈 시점의 헛 실행을
   * 피한다.
   */
  @Scheduled(
      fixedDelayString = "${app.pipeline.heartbeat-interval:PT1M}",
      initialDelayString = "${app.pipeline.heartbeat-interval:PT1M}")
  public void beat() {
    Map<Long, Long> snapshot = registry.snapshot();
    if (snapshot.isEmpty()) {
      return;
    }
    Map<Long, List<Long>> byTenant = new TreeMap<>();
    snapshot.forEach(
        (executionId, tenantId) ->
            byTenant.computeIfAbsent(tenantId, k -> new ArrayList<>()).add(executionId));
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    byTenant.forEach(
        (tenantId, ids) -> {
          try {
            TenantContext.runScoped(tenantId, () -> executionRepository.touchHeartbeat(ids, now));
          } catch (Exception e) {
            log.warn("파이프라인 하트비트 갱신 실패 (tenant={}, executions={})", tenantId, ids, e);
          }
        });
  }
}
