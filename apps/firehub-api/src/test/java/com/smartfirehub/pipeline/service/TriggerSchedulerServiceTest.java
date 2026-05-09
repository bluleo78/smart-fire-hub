package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TriggerSchedulerServiceTest extends IntegrationTestBase {

  @Autowired private TriggerService triggerService;

  @Autowired private TriggerSchedulerService schedulerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private TriggerEventRepository triggerEventRepository;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long pipelineId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "scheduler_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Scheduler Test User")
            .set(USER.EMAIL, "scheduler_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Scheduler Test Pipeline", "Description", List.of()),
            testUserId);
    pipelineId = pipeline.id();
  }

  @Test
  void registerSchedule_validCron_succeeds() {
    Map<String, Object> config =
        Map.of(
            "cron", "0 0 * * *",
            "timezone", "Asia/Seoul",
            "concurrencyPolicy", "SKIP");

    // Should not throw
    schedulerService.registerSchedule(999L, config);

    // Cleanup
    schedulerService.unregisterSchedule(999L);
  }

  @Test
  void unregisterSchedule_cancelsExistingTask() {
    Map<String, Object> config =
        Map.of(
            "cron", "0 0 * * *",
            "timezone", "Asia/Seoul");

    schedulerService.registerSchedule(998L, config);
    // Should not throw
    schedulerService.unregisterSchedule(998L);
    // Second unregister should also not throw
    schedulerService.unregisterSchedule(998L);
  }

  @Test
  void createScheduleTrigger_withSkipPolicy_setsConfigCorrectly() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "SKIP Policy Test",
            TriggerType.SCHEDULE,
            "Test SKIP concurrency",
            Map.of("cron", "0 9 * * *", "concurrencyPolicy", "SKIP"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.config().get("concurrencyPolicy")).isEqualTo("SKIP");
    assertThat(response.config().get("timezone")).isEqualTo("Asia/Seoul"); // default
  }

  @Test
  void createScheduleTrigger_withAllowPolicy_setsConfigCorrectly() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "ALLOW Policy Test",
            TriggerType.SCHEDULE,
            "Test ALLOW concurrency",
            Map.of("cron", "0 9 * * *", "concurrencyPolicy", "ALLOW"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.config().get("concurrencyPolicy")).isEqualTo("ALLOW");
  }

  // ─────────────────────────────────────────────────────────────
  // detectMissedFire timezone-aware 비교 검증 (#160)
  // ─────────────────────────────────────────────────────────────

  /**
   * 시나리오: nextFireTime = "2026-05-07T09:00:00" (KST 기준 저장). config.timezone = "Asia/Seoul" → 실제
   * Instant = 2026-05-07T00:00:00Z. now = 2026-05-07T00:30:00Z (서버 UTC 기준 00:30) → 이미 지남 → missed
   * fire 감지해야 함.
   *
   * <p>수정 전 버그: now를 UTC LocalDateTime.now()로 해석하면 nextFireTime "09:00:00 UTC"와 비교 → 아직 미래라고 판단하여
   * missed fire를 놓친다.
   */
  @Test
  void detectMissedFire_withSeoulTimezone_detectsMissedFireCorrectly() {
    // KST 09:00 → nextFireTime 문자열 (timezone 없이 저장된 형태)
    String nextFireTimeStr = "2026-05-07T09:00:00";
    // KST 09:00 = UTC 00:00
    Instant expectedFireInstant =
        ZonedDateTime.of(LocalDateTime.parse(nextFireTimeStr), ZoneId.of("Asia/Seoul")).toInstant();
    // now = UTC 00:30 → KST 09:30, 이미 09:00 KST를 지남
    Instant now = expectedFireInstant.plusSeconds(1800);

    // 트리거 생성 (DB에 저장되어 fireTrigger 호출 가능하도록)
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "MissedFire TZ Test",
                TriggerType.SCHEDULE,
                "Timezone missed fire test",
                Map.of("cron", "0 9 * * *", "timezone", "Asia/Seoul")),
            testUserId);

    // triggerState에 nextFireTime 주입
    Map<String, Object> stateWithNextFire = new HashMap<>();
    stateWithNextFire.put("nextFireTime", nextFireTimeStr);
    TriggerResponse triggerWithState =
        new TriggerResponse(
            trigger.id(),
            trigger.pipelineId(),
            trigger.triggerType(),
            trigger.name(),
            trigger.description(),
            trigger.isEnabled(),
            trigger.config(),
            stateWithNextFire,
            trigger.createdBy(),
            trigger.createdAt());

    // 실행 (now를 KST 09:30 기준 UTC로 주입)
    schedulerService.detectMissedFire(triggerWithState, now);

    // MISSED 이벤트가 생성되었는지 확인
    var events = triggerEventRepository.findByTriggerId(trigger.id(), 10);
    assertThat(events).anySatisfy(e -> assertThat(e.eventType()).isEqualTo("MISSED"));
  }

  /**
   * 시나리오: nextFireTime = "2026-05-07T09:00:00" (KST 기준 저장). config.timezone = "Asia/Seoul" → 실제
   * Instant = 2026-05-07T00:00:00Z. now = 2026-05-06T23:30:00Z (서버 UTC 기준, 아직 KST 09:00 이전) → 미래 →
   * missed fire 없어야 함.
   *
   * <p>수정 전 버그: LocalDateTime.now()를 UTC 기준으로 사용하면 "09:00 UTC"와 비교하여 동일한 시각을 "이미 지남"으로 오탐한다.
   */
  @Test
  void detectMissedFire_withSeoulTimezone_doesNotFireWhenStillFuture() {
    String nextFireTimeStr = "2026-05-07T09:00:00";
    // KST 09:00 = UTC 00:00
    Instant expectedFireInstant =
        ZonedDateTime.of(LocalDateTime.parse(nextFireTimeStr), ZoneId.of("Asia/Seoul")).toInstant();
    // now = UTC 23:30 전날 → KST 08:30, 아직 09:00 KST 이전
    Instant now = expectedFireInstant.minusSeconds(1800);

    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "MissedFire FutureCheck Test",
                TriggerType.SCHEDULE,
                "Timezone future check test",
                Map.of("cron", "0 9 * * *", "timezone", "Asia/Seoul")),
            testUserId);

    Map<String, Object> stateWithNextFire = new HashMap<>();
    stateWithNextFire.put("nextFireTime", nextFireTimeStr);
    TriggerResponse triggerWithState =
        new TriggerResponse(
            trigger.id(),
            trigger.pipelineId(),
            trigger.triggerType(),
            trigger.name(),
            trigger.description(),
            trigger.isEnabled(),
            trigger.config(),
            stateWithNextFire,
            trigger.createdBy(),
            trigger.createdAt());

    // 실행 (아직 미래)
    schedulerService.detectMissedFire(triggerWithState, now);

    // MISSED 이벤트가 생성되지 않아야 함
    var events = triggerEventRepository.findByTriggerId(trigger.id(), 10);
    assertThat(events).noneMatch(e -> "MISSED".equals(e.eventType()));
  }

  /**
   * 시나리오: nextFireTime = "2026-05-07T09:00:00" (UTC 기준 저장, config.timezone = "UTC"). now =
   * 2026-05-07T09:30:00Z → 이미 지남 → missed fire 감지.
   */
  @Test
  void detectMissedFire_withUtcTimezone_detectsMissedFireCorrectly() {
    String nextFireTimeStr = "2026-05-07T09:00:00";
    Instant expectedFireInstant =
        ZonedDateTime.of(LocalDateTime.parse(nextFireTimeStr), ZoneId.of("UTC")).toInstant();
    Instant now = expectedFireInstant.plusSeconds(1800);

    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "MissedFire UTC Test",
                TriggerType.SCHEDULE,
                "UTC timezone missed fire test",
                Map.of("cron", "0 9 * * *", "timezone", "UTC")),
            testUserId);

    Map<String, Object> stateWithNextFire = new HashMap<>();
    stateWithNextFire.put("nextFireTime", nextFireTimeStr);
    TriggerResponse triggerWithState =
        new TriggerResponse(
            trigger.id(),
            trigger.pipelineId(),
            trigger.triggerType(),
            trigger.name(),
            trigger.description(),
            trigger.isEnabled(),
            trigger.config(),
            stateWithNextFire,
            trigger.createdBy(),
            trigger.createdAt());

    schedulerService.detectMissedFire(triggerWithState, now);

    var events = triggerEventRepository.findByTriggerId(trigger.id(), 10);
    assertThat(events).anySatisfy(e -> assertThat(e.eventType()).isEqualTo("MISSED"));
  }
}
