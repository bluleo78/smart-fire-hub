package com.smartfirehub.dashboard.job;

import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * pipeline_execution 90일 이상 누적된 COMPLETED 행을 자동 정리.
 *
 * <p>- 매일 자정 (KST) 실행. 둘 다 env override 가능. - 정책: created_at < (now - retentionDays) AND status =
 * 'COMPLETED'. - FAILED 는 보존 — 디버깅·재시도 단서. - 자식 trigger_event 는 FK ON DELETE CASCADE (V59) 로 자동 정리.
 * - 운영자가 손으로 DELETE 치는 작업을 제거 (#223).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineExecutionTtlJob {

  private final DSLContext dsl;
  private final TenantScopedRunner tenantScopedRunner;
  private final TransactionTemplate transactionTemplate;

  @Value("${firehub.execution.ttl.days:90}")
  private int retentionDays;

  /**
   * 스케줄 진입점 — ACTIVE 테넌트를 순회한다.
   *
   * <p>스케줄러에는 원 HTTP 요청이 없어 승계할 테넌트가 없다. 순회하지 않으면 RLS 가
   * pipeline_execution 을 전부 차단해 TTL 정리가 예외도 로그도 없이 영구히 무동작이 된다.
   */
  @Scheduled(cron = "${firehub.execution.ttl.cron:0 0 0 * * *}")
  public void runScheduled() {
    tenantScopedRunner.forEachActiveTenant(tenantId -> runOnce());
  }

  /**
   * 한 테넌트 범위의 TTL 정리. 테스트·수동 호출용이며 삭제 행 수를 반환한다. 호출 시점에 TenantContext
   * 가 설정돼 있어야 한다.
   *
   * <p>이 잡은 리포지토리를 거치지 않고 {@code DSLContext} 를 직접 쓰므로 순회만으로는 GUC 가
   * 주입되지 않는다 — DELETE 를 {@code TransactionTemplate} 으로 감싸야 {@code
   * TenantAwareTransactionManager.doBegin} 이 app.tenant_id 를 심는다.
   */
  public int runOnce() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
    Integer result =
        transactionTemplate.execute(
            status ->
                dsl.deleteFrom(PIPELINE_EXECUTION)
                    .where(PIPELINE_EXECUTION.CREATED_AT.lt(cutoff))
                    .and(PIPELINE_EXECUTION.STATUS.eq("COMPLETED"))
                    .execute());
    int deleted = result == null ? 0 : result;
    log.info(
        "PipelineExecutionTtl: deleted {} rows older than {} days (cutoff={})",
        deleted,
        retentionDays,
        cutoff);
    return deleted;
  }
}
