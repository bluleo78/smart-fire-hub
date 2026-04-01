package com.smartfirehub.job.service;

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

  /** Every 10 minutes: fail jobs that haven't been updated in 30+ minutes. */
  @Scheduled(fixedRate = 600_000)
  public void failStaleJobs() {
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

  /** Every 10 minutes: delete completed/failed jobs older than 30 days. */
  @Scheduled(fixedRate = 600_000)
  public void deleteOldJobs() {
    LocalDateTime retentionThreshold = LocalDateTime.now().minusDays(30);
    int deleted = asyncJobRepository.deleteOlderThan(retentionThreshold);
    if (deleted > 0) {
      log.info("Deleted {} old async job record(s) older than 30 days", deleted);
    }
  }
}
