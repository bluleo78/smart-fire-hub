package com.smartfirehub.dashboard.job;

import static com.smartfirehub.jooq.Tables.PIPELINE_EXECUTION;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

  @Value("${firehub.execution.ttl.days:90}")
  private int retentionDays;

  @Scheduled(cron = "${firehub.execution.ttl.cron:0 0 0 * * *}")
  public void runScheduled() {
    runOnce();
  }

  /** 테스트·수동 호출용. 삭제 행 수 반환. */
  public int runOnce() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
    int deleted =
        dsl.deleteFrom(PIPELINE_EXECUTION)
            .where(PIPELINE_EXECUTION.CREATED_AT.lt(cutoff))
            .and(PIPELINE_EXECUTION.STATUS.eq("COMPLETED"))
            .execute();
    log.info(
        "PipelineExecutionTtl: deleted {} rows older than {} days (cutoff={})",
        deleted,
        retentionDays,
        cutoff);
    return deleted;
  }
}
