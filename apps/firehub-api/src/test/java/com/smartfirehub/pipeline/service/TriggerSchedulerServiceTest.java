package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Instant;
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
  // detectMissedFire: nextFireTime UTC 저장 계약 검증 (#676, #160)
  //
  // registerSchedule()이 trigger_state.nextFireTime을 UTC Instant.toString() 형식으로 쓰기
  // 시작했으므로(#676), detectMissedFire도 같은 형식을 그대로 Instant.parse로 읽어 비교한다.
  // 트리거의 config.timezone(스케줄 자체가 언제 발화하는지 계산하는 데만 쓰임)과는 무관하게
  // 저장·비교 모두 UTC로 고정되어 있는지가 이 테스트들의 핵심 단언이다.
  // ─────────────────────────────────────────────────────────────

  /**
   * 시나리오: trigger.config.timezone = "Asia/Seoul" 인 트리거라도, nextFireTime은 UTC Instant
   * 문자열로 저장되어 있다. now가 그 시각보다 뒤라면(=이미 지남) missed fire를 감지해야 한다.
   */
  @Test
  void detectMissedFire_withSeoulTimezone_detectsMissedFireCorrectly() {
    Instant expectedFireInstant = Instant.parse("2026-05-07T00:00:00Z");
    String nextFireTimeStr = expectedFireInstant.toString();
    // now = 30분 뒤 → 이미 지남
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

    // triggerState에 nextFireTime 주입 (UTC Instant 문자열)
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
            nextFireTimeStr,
            trigger.createdBy(),
            trigger.createdAt());

    // 실행 (이미 지난 시각으로 now 주입)
    schedulerService.detectMissedFire(triggerWithState, now);

    // MISSED 이벤트가 생성되었는지 확인
    var events = triggerEventRepository.findByTriggerId(trigger.id(), 10);
    assertThat(events).anySatisfy(e -> assertThat(e.eventType()).isEqualTo("MISSED"));
  }

  /** 시나리오: 위와 동일 저장 형식이지만 now가 nextFireTime보다 앞선 경우(아직 미래) → missed fire 없어야 함. */
  @Test
  void detectMissedFire_withSeoulTimezone_doesNotFireWhenStillFuture() {
    Instant expectedFireInstant = Instant.parse("2026-05-07T00:00:00Z");
    String nextFireTimeStr = expectedFireInstant.toString();
    // now = 30분 전 → 아직 미래
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
            nextFireTimeStr,
            trigger.createdBy(),
            trigger.createdAt());

    // 실행 (아직 미래)
    schedulerService.detectMissedFire(triggerWithState, now);

    // MISSED 이벤트가 생성되지 않아야 함
    var events = triggerEventRepository.findByTriggerId(trigger.id(), 10);
    assertThat(events).noneMatch(e -> "MISSED".equals(e.eventType()));
  }

  /** 시나리오: config.timezone = "UTC" 인 트리거에서도 동일하게 동작해야 한다(스토리지 형식은 config.timezone과 무관). */
  @Test
  void detectMissedFire_withUtcTimezone_detectsMissedFireCorrectly() {
    Instant expectedFireInstant = Instant.parse("2026-05-07T09:00:00Z");
    String nextFireTimeStr = expectedFireInstant.toString();
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
            nextFireTimeStr,
            trigger.createdBy(),
            trigger.createdAt());

    schedulerService.detectMissedFire(triggerWithState, now);

    var events = triggerEventRepository.findByTriggerId(trigger.id(), 10);
    assertThat(events).anySatisfy(e -> assertThat(e.eventType()).isEqualTo("MISSED"));
  }

  // registerSchedule()의 nextFireTime write 경로(REQUIRES_NEW) 자체는 이 클래스 레벨
  // @Transactional(롤백 전용) 통합 테스트로는 검증할 수 없다 — REQUIRES_NEW가 여는 새 물리
  // 트랜잭션은 아직 커밋되지 않은(=이 테스트 트랜잭션 안에만 있는) 트리거 행을 볼 수 없다.
  // 별도의 순수 단위 테스트(TriggerSchedulerServiceUnitTest)에서 Mockito로 검증한다(#676).
}
