package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TriggerSchedulerService {

  private final TaskScheduler taskScheduler;
  private final TriggerRepository triggerRepository;
  private final TriggerEventRepository triggerEventRepository;
  private final TriggerService triggerService;
  private final TenantScopedRunner tenantScopedRunner;
  private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

  public TriggerSchedulerService(
      TriggerRepository triggerRepository,
      TriggerEventRepository triggerEventRepository,
      @Lazy TriggerService triggerService,
      TenantScopedRunner tenantScopedRunner) {
    this.triggerRepository = triggerRepository;
    this.triggerEventRepository = triggerEventRepository;
    this.triggerService = triggerService;
    this.tenantScopedRunner = tenantScopedRunner;

    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("trigger-scheduler-");
    scheduler.initialize();
    this.taskScheduler = scheduler;
  }

  /**
   * 기동 시 활성 SCHEDULE 트리거를 전부 재등록한다.
   *
   * <p>원 HTTP 요청이 없어 승계할 테넌트가 없으므로 ACTIVE 테넌트를 명시적으로 순회한다. 순회하지
   * 않으면 RLS 가 {@code pipeline_trigger} 를 전부 차단해 재기동 시 SCHEDULE 트리거가 하나도
   * 재등록되지 않고, 예외도 로그도 없이 조용히 죽는다(0행).
   *
   * <p>{@link TenantScopedRunner} 는 ThreadLocal 만 세우고 트랜잭션은 열지 않는다. 여기서 쓰는
   * {@link TriggerRepository}·{@link TriggerEventRepository}·{@link TriggerService} 는 모두 클래스
   * 레벨 {@code @Transactional} 이라 호출마다 트랜잭션이 열려 GUC 가 주입된다 — 이 메서드가
   * {@code DSLContext} 를 직접 쓰지 않는 이유다.
   */
  @PostConstruct
  public void reloadAllSchedules() {
    tenantScopedRunner.forEachActiveTenant(
        tenantId -> {
          List<TriggerResponse> schedules = triggerRepository.findEnabledByType("SCHEDULE");
          log.info("Reloading {} active schedule triggers (tenant={})", schedules.size(), tenantId);

          for (TriggerResponse trigger : schedules) {
            try {
              registerSchedule(trigger.id(), trigger.config());
              // detectMissedFire 는 이 순회 콜백 안에서 동기 실행되므로 테넌트 ThreadLocal 이 그대로
              // 유효하다. 별도 래핑이 필요 없다(내부 DB 접근은 전부 @Transactional 빈 경유).
              detectMissedFire(trigger);
            } catch (Exception e) {
              log.error(
                  "Failed to register schedule trigger {} (tenant={}): {}",
                  trigger.id(),
                  tenantId,
                  e.getMessage());
            }
          }
        });
  }

  /**
   * Register a cron schedule using ConcurrentHashMap.compute() for atomic registration.
   *
   * <p>이 스케줄러 풀({@code ThreadPoolTaskScheduler})에는 {@code TaskDecorator} 가 없다. 발화는 몇
   * 시간 뒤 풀 스레드에서 일어나므로 등록 시점의 테넌트를 람다가 직접 들고 가야 한다 — 그렇지 않으면
   * 재등록은 성공해도 발화 시점에 GUC 가 비어 조용히 0행이 된다.
   *
   * <p>{@code require()} 를 {@code compute()} 람다 <b>밖</b>에서 부르는 이유: 맵의 bin lock 을 잡은
   * 채 던지면 이미 {@code cancel} 된 기존 스케줄이 대체 없이 사라진다. 밖에서 먼저 실패하면 아무것도
   * 건드리지 않는다. 호출 경로(요청 스코프의 afterCommit, 기동 시 테넌트 순회)는 모두 컨텍스트가 있다.
   */
  public void registerSchedule(Long triggerId, Map<String, Object> config) {
    final long tenantId = TenantContext.require();

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
                    () ->
                        TenantContext.runScoped(
                            tenantId, () -> triggerService.fireTrigger(triggerId, Map.of())),
                    cronTrigger);
            log.info(
                "Registered schedule trigger {} with cron '{}' timezone '{}' (tenant={})",
                triggerId,
                cronExpression,
                timezone,
                tenantId);
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
    detectMissedFire(trigger, Instant.now());
  }

  /**
   * nextFireTime과 현재 시각을 timezone-aware하게 비교하여 missed fire를 감지한다.
   *
   * <p>테스트에서 현재 시각을 주입할 수 있도록 now 파라미터를 받는 package-private 오버로드.
   *
   * <p>nextFireTime은 trigger config의 timezone 기준 LocalDateTime으로 저장되어 있으므로, config.timezone을 적용해
   * ZonedDateTime으로 해석한 뒤 Instant로 변환하여 비교한다. 이렇게 하면 JVM 기본 timezone에 무관하게 올바른 시점 비교가 가능하다.
   */
  void detectMissedFire(TriggerResponse trigger, Instant now) {
    Map<String, Object> state = trigger.triggerState();
    if (state == null || !state.containsKey("nextFireTime")) {
      return;
    }

    try {
      String nextFireTimeStr = state.get("nextFireTime").toString();

      // config.timezone 기준으로 nextFireTime을 ZonedDateTime으로 해석 (registerSchedule과 동일 기준)
      String timezone = (String) trigger.config().getOrDefault("timezone", "Asia/Seoul");
      ZoneId zoneId = ZoneId.of(timezone);
      LocalDateTime localNextFireTime =
          LocalDateTime.parse(nextFireTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      Instant nextFireInstant = ZonedDateTime.of(localNextFireTime, zoneId).toInstant();

      if (nextFireInstant.isBefore(now)) {
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
