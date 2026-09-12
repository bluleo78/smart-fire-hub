package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
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
              // detectMissedFire 를 registerSchedule 보다 먼저 호출한다. registerSchedule 은
              // trigger_state.nextFireTime 을 "지금부터의 다음 실행 시각"으로 즉시 덮어쓰므로,
              // 순서를 바꾸면 재기동 전에 지나가버린(=놓친) nextFireTime 증거가 detectMissedFire가
              // 보기도 전에 사라진다. trigger 는 이 순회 진입 시점에 조회한 스냅샷이라
              // registerSchedule 의 DB 쓰기와 무관하게 재기동 직전 상태를 그대로 들고 있다.
              detectMissedFire(trigger);
              // detectMissedFire 는 이 순회 콜백 안에서 동기 실행되므로 테넌트 ThreadLocal 이 그대로
              // 유효하다. 별도 래핑이 필요 없다(내부 DB 접근은 전부 @Transactional 빈 경유).
              registerSchedule(trigger.id(), trigger.config());
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
   *
   * <p>등록 성공 시 {@code CronTrigger} 기준으로 다음 발화 시각을 계산해 {@code
   * trigger_state.nextFireTime}에 기록한다(#676) — 이 값이 없으면 프론트엔드의 "다음 실행" 표시와
   * {@link #detectMissedFire} 가 둘 다 전제로 삼는 값이 영영 채워지지 않는다. 계산 결과는 {@code
   * compute()} 밖으로 홀더를 통해 전달한 뒤 쓴다 — bin lock 을 잡은 채 DB I/O(트랜잭션)를 하지
   * 않기 위함이다.
   */
  public void registerSchedule(Long triggerId, Map<String, Object> config) {
    final long tenantId = TenantContext.require();
    final AtomicReference<Instant> nextFireHolder = new AtomicReference<>();

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
                            tenantId,
                            () -> {
                              triggerService.fireTrigger(triggerId, Map.of());
                              // 발화 직후 다음 실행 시각을 재계산해 갱신한다 — 그래야 매 발화마다
                              // "다음 실행" 표시와 missed-fire 감지 기준이 최신으로 유지된다.
                              writeNextFireTime(
                                  triggerId, cronTrigger.nextExecution(new SimpleTriggerContext()));
                            }),
                    cronTrigger);
            // 최초 등록 시점의 다음 발화 시각. compute() 밖(락 해제 후)에서 DB에 반영한다.
            nextFireHolder.set(cronTrigger.nextExecution(new SimpleTriggerContext()));
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

    writeNextFireTime(triggerId, nextFireHolder.get());
  }

  /**
   * 다음 발화 시각을 {@code trigger_state.nextFireTime}에 UTC ISO-8601 문자열({@link
   * Instant#toString()})로 기록한다.
   *
   * <p>UTC로 고정하는 이유(#676, #160): 프론트엔드의 날짜 계약은 "오프셋 없는 문자열=UTC"이고
   * (`formatDate`/`parseUtcDate`), {@link #detectMissedFire} 도 같은 값을 다시 읽어 비교해야 한다.
   * 트리거 설정의 timezone(예: Asia/Seoul)으로 저장하면 두 소비자 중 하나는 반드시 잘못
   * 해석하게 되므로, 저장 시점에 UTC Instant로 정규화해 타임존 모호성을 원천 차단한다.
   */
  private void writeNextFireTime(Long triggerId, Instant nextFireInstant) {
    if (nextFireInstant == null) {
      return;
    }
    // REQUIRES_NEW 트랜잭션(TriggerRepository.mergeNextFireTime 참고)으로 위임한다 — 이 메서드가
    // afterCommit() 콜백 안에서 불릴 수 있어, 기본 REQUIRED 로는 이미 커밋된 트랜잭션에 조용히
    // 합류해 반영되지 않는다(#676).
    triggerRepository.mergeNextFireTime(triggerId, nextFireInstant.toString());
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
   * nextFireTime과 현재 시각을 비교하여 missed fire를 감지한다.
   *
   * <p>테스트에서 현재 시각을 주입할 수 있도록 now 파라미터를 받는 package-private 오버로드.
   *
   * <p>nextFireTime은 {@link #writeNextFireTime}이 UTC {@code Instant.toString()} 형식으로 저장한다
   * (#676). 과거에는 config.timezone 기준 LocalDateTime으로 저장한다고 가정하고 여기서 다시
   * ZonedDateTime으로 재해석했는데(#160), 애초에 write 경로가 없어 실행된 적이 없던 코드라 그
   * 재해석 로직 자체도 검증되지 않은 채였다. UTC로 저장을 통일해 timezone 재해석을 아예
   * 없앤다 — JVM 기본 timezone에도 무관하게 올바른 시점 비교가 된다.
   */
  void detectMissedFire(TriggerResponse trigger, Instant now) {
    Map<String, Object> state = trigger.triggerState();
    if (state == null || !state.containsKey("nextFireTime")) {
      return;
    }

    try {
      String nextFireTimeStr = state.get("nextFireTime").toString();
      Instant nextFireInstant = Instant.parse(nextFireTimeStr);

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
