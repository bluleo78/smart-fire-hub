package com.smartfirehub.proactive.service;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.repository.ProactiveJobRepository;
import com.smartfirehub.proactive.util.ProactiveCron;
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
    boolean registered = doRegister(jobId, cronExpression, timezone);

    // 다음 실행 예정 시각을 DB에 반영한다 (#348).
    // 등록/해제 경로(생성·수정·활성화 토글·부팅 시 reloadAllSchedules)가 모두 이 메서드를 지나므로
    // 여기 한 곳만 채우면 "한 번도 실행되지 않은 잡"까지 포함해 전 행이 값을 갖는다.
    // compute() 람다 밖에서 쓴다 — ConcurrentHashMap 의 bin lock 을 잡은 채 DB I/O 를 하지 않기 위함.
    proactiveJobRepository.updateNextExecuteAt(
        jobId, registered ? ProactiveCron.nextExecuteAtUtc(cronExpression, timezone) : null);
  }

  /**
   * 실제 스케줄 등록. 등록에 성공하면 true.
   *
   * <p>DB 갱신과 분리한 이유는 {@link ConcurrentHashMap#compute} 람다 안에서 I/O 를 피하기 위함이다.
   */
  private boolean doRegister(Long jobId, String cronExpression, String timezone) {
    return scheduledTasks.compute(
        jobId,
        (id, existing) -> {
          if (existing != null) {
            existing.cancel(false);
          }
          String tz = timezone != null && !timezone.isBlank() ? timezone : "Asia/Seoul";
          try {
            // CronTrigger 는 6필드만 수용하는데 DB에는 5필드(Unix 표준)와 6필드가 섞여 있다(#347).
            // 원시 문자열을 그대로 넘기면 5필드 레거시 잡이 등록에 실패해 enabled=true 인 채로
            // 영구 미실행 상태가 된다(#354). 다음 실행 시각 계산(nextExecuteAtUtc)과 같은
            // 정규화 규칙을 쓰게 하여 "표시값 = 실제 발화 시각" 불변식도 유지한다(#348).
            CronTrigger cronTrigger =
                new CronTrigger(ProactiveCron.normalize(cronExpression), ZoneId.of(tz));
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
        })
        != null;
  }

  public void unregisterSchedule(Long jobId) {
    scheduledTasks.computeIfPresent(
        jobId,
        (id, future) -> {
          future.cancel(false);
          log.info("Unregistered proactive job schedule {}", jobId);
          return null;
        });
    // 스케줄이 없어졌으므로 "다음 실행" 표시도 비운다 (#348).
    // 비활성화/삭제 후에도 과거 계산값이 남아 있으면 곧 실행될 것처럼 보인다.
    proactiveJobRepository.updateNextExecuteAt(jobId, null);
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
