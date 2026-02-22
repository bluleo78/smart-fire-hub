package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Service
public class TriggerSchedulerService {

  private static final Logger log = LoggerFactory.getLogger(TriggerSchedulerService.class);

  private final TaskScheduler taskScheduler;
  private final TriggerRepository triggerRepository;
  private final TriggerEventRepository triggerEventRepository;
  private final TriggerService triggerService;
  private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

  public TriggerSchedulerService(
      TriggerRepository triggerRepository,
      TriggerEventRepository triggerEventRepository,
      @Lazy TriggerService triggerService) {
    this.triggerRepository = triggerRepository;
    this.triggerEventRepository = triggerEventRepository;
    this.triggerService = triggerService;

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("trigger-scheduler-");
    scheduler.initialize();
    this.taskScheduler = scheduler;
  }

  @PostConstruct
  public void reloadAllSchedules() {
    List<TriggerResponse> schedules = triggerRepository.findEnabledByType("SCHEDULE");
    log.info("Reloading {} active schedule triggers", schedules.size());

    for (TriggerResponse trigger : schedules) {
      try {
        registerSchedule(trigger.id(), trigger.config());
        detectMissedFire(trigger);
      } catch (Exception e) {
        log.error("Failed to register schedule trigger {}: {}", trigger.id(), e.getMessage());
      }
    }
  }

  /** Register a cron schedule using ConcurrentHashMap.compute() for atomic registration. */
  public void registerSchedule(Long triggerId, Map<String, Object> config) {
    scheduledTasks.compute(
        triggerId,
        (id, existing) -> {
          if (existing != null) {
            existing.cancel(false);
          }

          String cronExpression = (String) config.get("cron");
          String timezone = (String) config.getOrDefault("timezone", "Asia/Seoul");

          try {
            CronTrigger cronTrigger = new CronTrigger(cronExpression, ZoneId.of(timezone));
            ScheduledFuture<?> future =
                taskScheduler.schedule(
                    () -> triggerService.fireTrigger(triggerId, Map.of()), cronTrigger);
            log.info(
                "Registered schedule trigger {} with cron '{}' timezone '{}'",
                triggerId,
                cronExpression,
                timezone);
            return future;
          } catch (Exception e) {
            log.error(
                "Failed to register cron schedule for trigger {}: {}", triggerId, e.getMessage());
            return null;
          }
        });
  }

  /** Unregister a cron schedule. */
  public void unregisterSchedule(Long triggerId) {
    scheduledTasks.computeIfPresent(
        triggerId,
        (id, future) -> {
          future.cancel(false);
          log.info("Unregistered schedule trigger {}", triggerId);
          return null;
        });
  }

  /**
   * Detect missed fires: if trigger_state.nextFireTime < now() and no FIRED event for that time
   * window, fire immediately and record MISSED event.
   */
  private void detectMissedFire(TriggerResponse trigger) {
    Map<String, Object> state = trigger.triggerState();
    if (state == null || !state.containsKey("nextFireTime")) {
      return;
    }

    try {
      String nextFireTimeStr = state.get("nextFireTime").toString();
      LocalDateTime nextFireTime =
          LocalDateTime.parse(nextFireTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);

      if (nextFireTime.isBefore(LocalDateTime.now())) {
        log.warn(
            "Missed fire detected for trigger {} (nextFireTime: {})",
            trigger.id(),
            nextFireTimeStr);

        // Record MISSED event
        triggerEventRepository.create(
            trigger.id(),
            trigger.pipelineId(),
            null,
            "MISSED",
            Map.of("missedFireTime", nextFireTimeStr));

        // Fire immediately
        triggerService.fireTrigger(trigger.id(), Map.of("missedFire", true));

        // Update nextFireTime in state
        Map<String, Object> updatedState = new HashMap<>(state);
        updatedState.put("lastMissedFireAt", nextFireTimeStr);
        updatedState.remove("nextFireTime");
        triggerRepository.updateTriggerState(trigger.id(), updatedState);
      }
    } catch (Exception e) {
      log.error("Failed to detect missed fire for trigger {}: {}", trigger.id(), e.getMessage());
    }
  }
}
