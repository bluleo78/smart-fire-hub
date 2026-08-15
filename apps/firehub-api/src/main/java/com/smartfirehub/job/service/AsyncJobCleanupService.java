package com.smartfirehub.job.service;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncJobCleanupService {

  private final AsyncJobRepository asyncJobRepository;
  private final AsyncJobService asyncJobService;
  private final TenantScopedRunner tenantScopedRunner;

  /**
   * 10분마다: 30분 이상 진행이 없는 잡을 실패 처리한다.
   *
   * <p><b>테넌트 순회(P2-b)</b>: 스케줄러에는 원 HTTP 요청이 없어 승계할 테넌트가 없다 — ACTIVE
   * 테넌트를 순회한다. 순회하지 않으면 RLS 가 async_job 을 전부 차단해 좀비 잡이 영구히 진행 중으로
   * 남는다.
   *
   * <p><b>트랜잭션</b>: {@code AsyncJobRepository} 는 클래스 레벨 {@code @Transactional} 이라(Task 1)
   * 호출마다 자기 트랜잭션을 열고 그 시점에 GUC 가 주입된다 — {@code TransactionTemplate} 을 덧붙일
   * 필요가 없다(확인함).
   */
  @Scheduled(fixedRate = 600_000)
  public void failStaleJobs() {
    tenantScopedRunner.forEachActiveTenant(tenantId -> failStaleJobsForTenant());
  }

  /** 한 테넌트 범위의 좀비 잡 실패 처리. 호출 시점에 TenantContext 가 설정돼 있어야 한다. */
  private void failStaleJobsForTenant() {
    LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(30);
    List<AsyncJobStatusResponse> staleJobs = asyncJobRepository.findStaleJobs(staleThreshold);

    if (staleJobs.isEmpty()) {
      return;
    }

    log.info("Found {} stale async job(s) to fail", staleJobs.size());
    for (AsyncJobStatusResponse job : staleJobs) {
      log.warn(
          "Failing stale job: jobId={}, jobType={}, stage={}, lastUpdated before {}",
          job.jobId(),
          job.jobType(),
          job.stage(),
          staleThreshold);
      asyncJobService.failJob(job.jobId(), "Job timed out: no progress for 30 minutes");
    }
  }

  /**
   * 10분마다: 30일 이상 지난 완료/실패 잡 레코드를 삭제한다.
   *
   * <p>순회·트랜잭션 근거는 {@link #failStaleJobs()} 주석과 같다(리포지토리 경유라 순회만 필요).
   */
  @Scheduled(fixedRate = 600_000)
  public void deleteOldJobs() {
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> {
          LocalDateTime retentionThreshold = LocalDateTime.now().minusDays(30);
          int deleted = asyncJobRepository.deleteOlderThan(retentionThreshold);
          if (deleted > 0) {
            log.info(
                "Deleted {} old async job record(s) older than 30 days (tenant={})",
                deleted,
                tenantId);
          }
        });
  }
}
