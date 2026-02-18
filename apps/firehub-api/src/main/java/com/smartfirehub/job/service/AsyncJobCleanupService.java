package com.smartfirehub.job.service;

import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.repository.AsyncJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AsyncJobCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobCleanupService.class);

    private final AsyncJobRepository asyncJobRepository;
    private final AsyncJobService asyncJobService;

    public AsyncJobCleanupService(AsyncJobRepository asyncJobRepository, AsyncJobService asyncJobService) {
        this.asyncJobRepository = asyncJobRepository;
        this.asyncJobService = asyncJobService;
    }

    /**
     * Every 10 minutes: fail jobs that haven't been updated in 30+ minutes.
     */
    @Scheduled(fixedRate = 600_000)
    public void failStaleJobs() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(30);
        List<AsyncJobStatusResponse> staleJobs = asyncJobRepository.findStaleJobs(staleThreshold);

        if (staleJobs.isEmpty()) {
            return;
        }

        log.info("Found {} stale async job(s) to fail", staleJobs.size());
        for (AsyncJobStatusResponse job : staleJobs) {
            log.warn("Failing stale job: jobId={}, jobType={}, stage={}, lastUpdated before {}",
                    job.jobId(), job.jobType(), job.stage(), staleThreshold);
            asyncJobService.failJob(job.jobId(), "Job timed out: no progress for 30 minutes");
        }
    }

    /**
     * Every 10 minutes: delete completed/failed jobs older than 30 days.
     */
    @Scheduled(fixedRate = 600_000)
    public void deleteOldJobs() {
        LocalDateTime retentionThreshold = LocalDateTime.now().minusDays(30);
        int deleted = asyncJobRepository.deleteOlderThan(retentionThreshold);
        if (deleted > 0) {
            log.info("Deleted {} old async job record(s) older than 30 days", deleted);
        }
    }
}
