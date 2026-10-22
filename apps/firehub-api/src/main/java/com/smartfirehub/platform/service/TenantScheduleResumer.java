package com.smartfirehub.platform.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.TriggerSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 워크스페이스(테넌트) 정지 해제 시 그 워크스페이스의 예약(파이프라인 SCHEDULE 트리거·스마트 작업 크론)을 다시 등록한다(WD-174).
 *
 * <p><b>왜 필요한가</b>: 두 스케줄러의 기동 재등록({@code reloadAllSchedules})은 ACTIVE 테넌트만 돈다. 정지 중 api 가 재기동되면 그
 * 워크스페이스의 예약은 메모리에 하나도 걸리지 않은 채 남고, 운영자가 정지를 풀어도 다음 재기동까지 예약 실행이 돌지 않았다. 정지 쪽은 등록을 건드리지 않는다 — 스케줄
 * 맵이 트리거·작업 id 만 키로 들고 있어 테넌트 단위로 걷어 낼 근거가 없고, 발화 시점 관문(TriggerService.fireTrigger·
 * ProactiveJobSchedulerService.runScheduledJob)이 정지 워크스페이스의 실행을 막는다. 그래서 해제 쪽만 "다시 등록"으로 맞춘다. 이미
 * 등록돼 있으면(정지 중 재기동이 없었으면) 각 스케줄러의 {@code compute()} 가 옛 스케줄을 취소하고 바꿔 끼워 중복 등록되지 않는다.
 *
 * <p><b>왜 커밋 뒤 + 새 스레드인가</b>: 상태 변경이 롤백되면 ACTIVE 가 아닌 워크스페이스에 예약을 걸게 되므로 커밋 뒤에 등록한다. 그런데 커밋 뒤
 * 콜백(afterCommit) 스레드에는 이미 커밋된 운영자 트랜잭션의 커넥션이 아직 묶여 있다 — 기본 REQUIRED 로 리포지토리를 부르면 그 트랜잭션에 조용히
 * 합류하고(#676), 거기에는 대상 테넌트의 GUC 가 없어 RLS 가 걸린 {@code pipeline_trigger}·{@code proactive_job} 조회가 예외
 * 없이 0행이 된다. REQUIRES_NEW 로 감싸면 GUC 는 맞지만 묶인 커넥션 + 새 트랜잭션 + 다음 실행 시각 기록(REQUIRES_NEW)이 커넥션을 세 개까지
 * 잡아, 작은 풀에서는 고갈로 실패한다(실측: 테스트 풀 2개에서 "Could not open JDBC Connection"). 그래서 묶인 자원이 없는 새 스레드에서 기동
 * 재등록과 똑같은 방식(대상 테넌트 컨텍스트 + 리포지토리 호출마다 자기 트랜잭션)으로 돌리고, 끝날 때까지 기다린다 — 해제 응답이 돌아온 시점에는 예약이 이미 걸려 있다.
 */
@Slf4j
@Component
public class TenantScheduleResumer {

  private final TriggerSchedulerService triggerSchedulerService;
  private final ProactiveJobSchedulerService proactiveJobSchedulerService;
  // 호출마다 새 스레드 — 커밋 뒤 콜백 스레드에 묶인 운영자 트랜잭션 자원을 물려받지 않는다. 드문 운영자 조작이라 풀이 필요 없다
  private final SimpleAsyncTaskExecutor resumeExecutor =
      new SimpleAsyncTaskExecutor("tenant-resume-");

  public TenantScheduleResumer(
      TriggerSchedulerService triggerSchedulerService,
      ProactiveJobSchedulerService proactiveJobSchedulerService) {
    this.triggerSchedulerService = triggerSchedulerService;
    this.proactiveJobSchedulerService = proactiveJobSchedulerService;
  }

  /**
   * 현재 트랜잭션이 커밋된 뒤 이 테넌트의 예약을 다시 등록한다. 트랜잭션 밖에서 부르면 즉시 등록한다.
   *
   * <p>등록 실패는 로그로만 남긴다 — 커밋 뒤라 상태 변경을 되돌릴 수 없고, 던지면 "정지 해제는 됐는데 응답은 오류"인 부분 성공이 된다. 실패해도 다음 재기동의 기동
   * 재등록이 회복한다.
   */
  public void resumeAfterCommit(long tenantId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              resumeNow(tenantId);
            }
          });
    } else {
      resumeNow(tenantId);
    }
  }

  /** 새 스레드에서 대상 테넌트 컨텍스트를 세우고 두 스케줄러의 테넌트 단위 재등록을 부른 뒤, 끝날 때까지 기다린다. */
  void resumeNow(long tenantId) {
    try {
      CompletableFuture.runAsync(
              () ->
                  TenantContext.runScoped(
                      tenantId,
                      () -> {
                        int triggers = triggerSchedulerService.registerTenantSchedules();
                        int jobs = proactiveJobSchedulerService.registerTenantSchedules(tenantId);
                        log.info(
                            "Tenant {} resumed: re-registered {} schedule triggers and {} proactive jobs",
                            tenantId,
                            triggers,
                            jobs);
                      }),
              resumeExecutor)
          .join();
    } catch (RuntimeException e) {
      log.error(
          "Tenant {} resumed but schedules could not be re-registered (next restart will recover): {}",
          tenantId,
          e.getMessage(),
          e);
    }
  }
}
