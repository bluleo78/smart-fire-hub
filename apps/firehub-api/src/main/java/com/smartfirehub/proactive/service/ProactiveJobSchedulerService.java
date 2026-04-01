package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ProactiveJobSchedulerService {

  private final TaskScheduler taskScheduler;
  private final ProactiveJobRepository proactiveJobRepository;
  private final ProactiveJobService proactiveJobService;
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledTasks =
      new ConcurrentHashMap<>();

  public ProactiveJobSchedulerService(
      ProactiveJobRepository proactiveJobRepository,
      @Lazy ProactiveJobService proactiveJobService) {
    this.proactiveJobRepository = proactiveJobRepository;
    this.proactiveJobService = proactiveJobService;

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("proactive-");
    scheduler.initialize();
    this.taskScheduler = scheduler;
  }

  @PostConstruct
  public void reloadAllSchedules() {
    List<ProactiveJobResponse> jobs = proactiveJobRepository.findAllEnabled();
    log.info("Reloading {} enabled proactive jobs", jobs.size());

    for (ProactiveJobResponse job : jobs) {
      try {
        if (job.cronExpression() != null && !job.cronExpression().isBlank()) {
          registerSchedule(job.id(), job.cronExpression(), job.timezone());
        }
      } catch (Exception e) {
        log.error("Failed to register proactive job schedule {}: {}", job.id(), e.getMessage());
      }
    }
  }

  public void registerSchedule(Long jobId, String cronExpression, String timezone) {
    scheduledTasks.compute(
        jobId,
        (id, existing) -> {
          if (existing != null) {
            existing.cancel(false);
          }
          String tz = timezone != null && !timezone.isBlank() ? timezone : "Asia/Seoul";
          try {
            CronTrigger cronTrigger = new CronTrigger(cronExpression, ZoneId.of(tz));
            ScheduledFuture<?> future =
                taskScheduler.schedule(
                    () -> {
                      try {
                        // system execution — userId를 job owner로 단일 조회
                        proactiveJobRepository
                            .findById(jobId)
                            .ifPresent(job -> proactiveJobService.executeJob(jobId, job.userId()));
                      } catch (Exception e) {
                        log.error("Scheduled proactive job {} execution failed", jobId, e);
                      }
                    },
                    cronTrigger);
            log.info(
                "Registered proactive job {} with cron '{}' timezone '{}'",
                jobId,
                cronExpression,
                tz);
            return future;
          } catch (Exception e) {
            log.error("Failed to register cron for proactive job {}: {}", jobId, e.getMessage());
            return null;
          }
        });
  }

  public void unregisterSchedule(Long jobId) {
    scheduledTasks.computeIfPresent(
        jobId,
        (id, future) -> {
          future.cancel(false);
          log.info("Unregistered proactive job schedule {}", jobId);
          return null;
        });
  }

  public void rescheduleJob(ProactiveJobResponse job) {
    unregisterSchedule(job.id());
    if (Boolean.TRUE.equals(job.enabled())
        && job.cronExpression() != null
        && !job.cronExpression().isBlank()) {
      registerSchedule(job.id(), job.cronExpression(), job.timezone());
    }
  }
}
