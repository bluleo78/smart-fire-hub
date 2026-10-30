package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.proactive.util.ProactiveCron;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
   * <p>원 HTTP 요청이 없어 승계할 테넌트가 없으므로 ACTIVE 테넌트를 명시적으로 순회한다. 순회하지 않으면 RLS 가 {@code pipeline_trigger}
   * 를 전부 차단해 재기동 시 SCHEDULE 트리거가 하나도 재등록되지 않고, 예외도 로그도 없이 조용히 죽는다(0행).
   *
   * <p>{@link TenantScopedRunner} 는 ThreadLocal 만 세우고 트랜잭션은 열지 않는다. 여기서 쓰는 {@link
   * TriggerRepository}·{@link TriggerEventRepository}·{@link TriggerService} 는 모두 클래스 레벨
   * {@code @Transactional} 이라 호출마다 트랜잭션이 열려 GUC 가 주입된다 — 이 메서드가 {@code DSLContext} 를 직접 쓰지 않는 이유다.
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
   * 현재 테넌트의 활성 SCHEDULE 트리거를 전부 (재)등록한다 — 워크스페이스 정지 해제 전용(WD-174).
   *
   * <p>기동 재등록({@link #reloadAllSchedules})은 ACTIVE 테넌트만 돌므로, 정지 중 api 가 재기동되면 그 워크스페이스의 예약은 등록되지 않은
   * 채 남아 정지를 풀어도 다음 재기동까지 돌지 않았다. 이미 등록된 트리거를 다시 불러도 {@link #registerSchedule} 의 {@code compute()}
   * 가 옛 스케줄을 취소하고 바꿔 끼우므로 중복 등록되지 않는다(멱등).
   *
   * <p><b>놓친 발화({@link #detectMissedFire})는 보지 않는다.</b> 정지 중 지나간 발화는 운영자가 의도해 막은 것이라, 해제 순간 MISSED
   * 이벤트를 남기고 따라잡기 실행을 시작하면 안 된다. {@code registerSchedule} 이 다음 실행 시각을 "지금부터"로 덮어써 정지 중 낡은 값도 함께
   * 정리된다.
   *
   * <p><b>호출 규약</b>: 테넌트 컨텍스트가 서 있고, 다른 트랜잭션에 묶이지 않은 스레드에서 불러야 한다 — 기동 재등록과 같이 리포지토리 호출마다 자기 트랜잭션이
   * 열려 그 테넌트 GUC 가 주입된다. 정지 해제 쪽이 커밋 뒤 새 스레드에서 부른다.
   *
   * @return 등록을 시도한 트리거 수
   */
  public int registerTenantSchedules() {
    long tenantId = TenantContext.require("워크스페이스 재개 시 예약 트리거 재등록");
    List<TriggerResponse> schedules = triggerRepository.findEnabledByType("SCHEDULE");
    log.info(
        "Re-registering {} schedule triggers on tenant resume (tenant={})",
        schedules.size(),
        tenantId);
    for (TriggerResponse trigger : schedules) {
      // 한 트리거의 잘못된 값이 나머지 등록을 막지 않게 하나씩 격리한다(실패는 경고 후 "스케줄 없음"으로 정리)
      registerScheduleAfterCommit(trigger.id(), trigger.config());
    }
    return schedules.size();
  }

  /**
   * Register a cron schedule using ConcurrentHashMap.compute() for atomic registration.
   *
   * <p>이 스케줄러 풀({@code ThreadPoolTaskScheduler})에는 {@code TaskDecorator} 가 없다. 발화는 몇 시간 뒤 풀 스레드에서
   * 일어나므로 등록 시점의 테넌트를 람다가 직접 들고 가야 한다 — 그렇지 않으면 재등록은 성공해도 발화 시점에 GUC 가 비어 조용히 0행이 된다.
   *
   * <p>{@code require()} 를 {@code compute()} 람다 <b>밖</b>에서 부르는 이유: 맵의 bin lock 을 잡은 채 던지면 이미 {@code
   * cancel} 된 기존 스케줄이 대체 없이 사라진다. 밖에서 먼저 실패하면 아무것도 건드리지 않는다. 호출 경로(요청 스코프의 afterCommit, 기동 시 테넌트
   * 순회)는 모두 컨텍스트가 있다.
   *
   * <p>등록 성공 시 {@code CronTrigger} 기준으로 다음 발화 시각을 계산해 {@code trigger_state.nextFireTime}에
   * 기록한다(#676) — 이 값이 없으면 프론트엔드의 "다음 실행" 표시와 {@link #detectMissedFire} 가 둘 다 전제로 삼는 값이 영영 채워지지 않는다.
   * 계산 결과는 {@code compute()} 밖으로 홀더를 통해 전달한 뒤 쓴다 — bin lock 을 잡은 채 DB I/O(트랜잭션)를 하지 않기 위함이다.
   *
   * @throws IllegalArgumentException cron·시간대로 스케줄을 만들 수 없을 때. 이때 기존 스케줄과 {@code nextFireTime} 은
   *     그대로 남는다(WD-107). 호출자 대부분이 커밋 후(afterCommit)에 부르므로 이 예외로 DB 를 되돌릴 수는 없다 — 저장 경로는 커밋 전에
   *     {@code TriggerService.validateScheduleConfig} 로 같은 검증을 먼저 거쳐 여기까지 오지 않게 한다.
   */
  public void registerSchedule(Long triggerId, Map<String, Object> config) {
    final long tenantId = TenantContext.require();
    final AtomicReference<Instant> nextFireHolder = new AtomicReference<>();
    // 다음 발화가 없어 스케줄을 걸지 못했는지 compute() 밖으로 전달한다(WD-107).
    final AtomicBoolean noNextFire = new AtomicBoolean(false);

    // 새 CronTrigger 를 compute() 밖에서 먼저 만든다(WD-107). 예전에는 compute() 안에서 기존 스케줄을 먼저 cancel 한 뒤
    // 생성에 실패하면 null 을 돌려줘, 잘못된 cron 으로 수정하는 순간 멀쩡하던 옛 스케줄까지 사라지고 오류는 로그로만 남았다.
    // 여기서 실패하면 맵을 건드리기 전이라 옛 스케줄이 그대로 남고, 예외는 호출자에게 전달돼 성공처럼 보이지 않는다.
    // 5필드 cron 은 정규화해 받아들인다 — 이 결함으로 5필드가 저장된 기존 행도 재기동·토글 시 다시 등록되게 하기 위함이다.
    // 정규화 규칙은 proactive 잡과 같은 ProactiveCron.normalize 를 재사용한다(검증 쪽 validateScheduleConfig 도 같은 것을
    // 쓴다).
    final Object rawCron = config.get("cron");
    final String cronExpression =
        ProactiveCron.normalize(rawCron == null ? null : rawCron.toString());
    final String timezone = String.valueOf(config.getOrDefault("timezone", "Asia/Seoul"));
    // 1분보다 잦은 주기는 걸지 않는다(WD-366) — 저장 검증 전에 매초로 저장된 행이 재기동·토글로 다시 걸려 끝없이 재실행되지 않게.
    // 재기동 경로(reloadAllSchedules)는 트리거마다 예외를 잡아 다른 트리거 등록을 막지 않는다.
    String tooFrequent = ProactiveCron.describeTooFrequent(cronExpression);
    if (tooFrequent != null) {
      throw new IllegalArgumentException(tooFrequent + ": " + cronExpression);
    }
    final CronTrigger cronTrigger;
    try {
      cronTrigger = new CronTrigger(cronExpression, ZoneId.of(timezone));
    } catch (RuntimeException e) {
      log.error(
          "Failed to register cron schedule for trigger {} (cron '{}', timezone '{}'): {}",
          triggerId,
          cronExpression,
          timezone,
          e.getMessage());
      throw new IllegalArgumentException(
          "스케줄을 등록할 수 없습니다. cron 표현식 또는 시간대가 올바르지 않습니다: " + cronExpression + " / " + timezone, e);
    }

    scheduledTasks.compute(
        triggerId,
        (id, existing) -> {
          // 새 스케줄을 먼저 건 뒤에 옛 스케줄을 취소한다 — schedule() 자체가 실패(예: 풀 거부)해도 예외가 compute() 밖으로
          // 나가 맵이 바뀌지 않으므로, 옛 스케줄이 취소된 채 대체 없이 사라지는 일이 없다.
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
          if (existing != null) {
            existing.cancel(false);
          }
          if (future == null) {
            // 문법은 맞지만 다음 발화가 없는 cron(예: 2월 30일)이면 schedule() 이 null 을 돌려준다. 새 설정으로는 돌 스케줄이
            // 없으므로 옛 스케줄은 취소하고 항목을 지운다 — nextFireTime 도 compute() 밖에서 비워 옛 시각이 남지 않게 한다.
            log.warn(
                "Schedule trigger {} has no next execution for cron '{}' timezone '{}' — not scheduled",
                triggerId,
                cronExpression,
                timezone);
            noNextFire.set(true);
            return null;
          }
          // 최초 등록 시점의 다음 발화 시각. compute() 밖(락 해제 후)에서 DB에 반영한다.
          nextFireHolder.set(cronTrigger.nextExecution(new SimpleTriggerContext()));
          log.info(
              "Registered schedule trigger {} with cron '{}' timezone '{}' (tenant={})",
              triggerId,
              cronExpression,
              timezone,
              tenantId);
          return future;
        });

    if (noNextFire.get()) {
      triggerRepository.clearNextFireTime(triggerId);
      return;
    }
    writeNextFireTime(triggerId, nextFireHolder.get());
  }

  /**
   * 이 트리거의 스케줄이 현재 이 인스턴스에 걸려 있는지 알려 준다(WD-107).
   *
   * <p>config 를 바꾸지 않는 수정(이름·설명 변경)에서 이미 돌고 있는 스케줄을 굳이 다시 등록하지 않기 위해 쓴다 — 재등록은 다음 실행 시각 재기록 등 부수
   * 효과가 있고, 저장된 값이 잘못된 옛 행이면 실패할 수도 있다.
   */
  public boolean isScheduled(Long triggerId) {
    return scheduledTasks.containsKey(triggerId);
  }

  /**
   * 커밋 후(afterCommit) 경로 전용 재등록. 실패해도 예외를 던지지 않고 경고 로그와 함께 "스케줄 없음" 상태로 정리한다(WD-107).
   *
   * <p>afterCommit 시점에는 DB 가 이미 커밋돼 예외로 되돌릴 수 없고, 던지면 저장은 됐는데 응답만 오류가 되는 부분 성공이 생긴다. 잘못된 값의 본 방어선은
   * 커밋 전 검증({@code TriggerService.validateScheduleConfig})이므로, 여기까지 온 실패(검증 이전에 저장된 옛 잘못된 행 등)는 DB
   * 의 새 설정과 어긋나는 옛 스케줄을 남기지 않도록 내리고 "다음 실행"도 비운다.
   */
  public void registerScheduleAfterCommit(Long triggerId, Map<String, Object> config) {
    try {
      registerSchedule(triggerId, config);
    } catch (RuntimeException e) {
      log.warn(
          "Schedule trigger {} could not be registered after commit; left unscheduled: {}",
          triggerId,
          e.getMessage());
      unregisterSchedule(triggerId);
      triggerRepository.clearNextFireTime(triggerId);
    }
  }

  /**
   * 다음 발화 시각을 {@code trigger_state.nextFireTime}에 UTC ISO-8601 문자열({@link Instant#toString()})로
   * 기록한다.
   *
   * <p>UTC로 고정하는 이유(#676, #160): 프론트엔드의 날짜 계약은 "오프셋 없는 문자열=UTC"이고 (`formatDate`/`parseUtcDate`),
   * {@link #detectMissedFire} 도 같은 값을 다시 읽어 비교해야 한다. 트리거 설정의 timezone(예: Asia/Seoul)으로 저장하면 두 소비자 중
   * 하나는 반드시 잘못 해석하게 되므로, 저장 시점에 UTC Instant로 정규화해 타임존 모호성을 원천 차단한다.
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
   * <p>nextFireTime은 {@link #writeNextFireTime}이 UTC {@code Instant.toString()} 형식으로 저장한다 (#676).
   * 과거에는 config.timezone 기준 LocalDateTime으로 저장한다고 가정하고 여기서 다시 ZonedDateTime으로 재해석했는데(#160), 애초에
   * write 경로가 없어 실행된 적이 없던 코드라 그 재해석 로직 자체도 검증되지 않은 채였다. UTC로 저장을 통일해 timezone 재해석을 아예 없앤다 — JVM 기본
   * timezone에도 무관하게 올바른 시점 비교가 된다.
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
