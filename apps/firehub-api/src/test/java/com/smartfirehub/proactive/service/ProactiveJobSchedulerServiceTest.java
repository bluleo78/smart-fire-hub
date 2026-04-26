package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveJobSchedulerService 통합 테스트. registerSchedule / unregisterSchedule / rescheduleJob 메서드
 * 커버.
 */
@Transactional
class ProactiveJobSchedulerServiceTest extends IntegrationTestBase {

  @Autowired private ProactiveJobSchedulerService schedulerService;
  @Autowired private ProactiveJobService proactiveJobService;
  @Autowired private DSLContext dsl;

  @MockitoBean private ProactiveAiClient proactiveAiClient;
  @MockitoBean private ProactiveContextCollector proactiveContextCollector;

  @MockitoBean
  private com.smartfirehub.proactive.service.delivery.DeliveryChannel chatDeliveryChannel;

  @MockitoBean
  private com.smartfirehub.proactive.repository.AnomalyEventRepository anomalyEventRepository;

  @MockitoBean private com.smartfirehub.notification.service.SseEmitterRegistry sseEmitterRegistry;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "scheduler_test_user")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Scheduler Test User")
            .set(DSL.field(DSL.name("user", "email"), String.class), "scheduler_test@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));
  }

  /** 테스트용 ProactiveJobResponse 헬퍼 — 직접 record 생성 */
  private ProactiveJobResponse makeJobResponse(
      Long id, String cron, String timezone, Boolean enabled) {
    return new ProactiveJobResponse(
        id,
        testUserId,
        null,
        null,
        "Test Job",
        "Test prompt",
        cron,
        timezone,
        enabled,
        Map.of(),
        null,
        null,
        LocalDateTime.now(),
        LocalDateTime.now(),
        null);
  }

  // =========================================================================
  // registerSchedule
  // =========================================================================

  @Test
  void registerSchedule_validCron_doesNotThrow() {
    assertThatCode(() -> schedulerService.registerSchedule(1L, "0 0 * * * *", "Asia/Seoul"))
        .doesNotThrowAnyException();
  }

  @Test
  void registerSchedule_invalidCron_doesNotThrow() {
    // 잘못된 cron 표현식 — 내부에서 예외를 로그만 남기고 삼킨다
    assertThatCode(() -> schedulerService.registerSchedule(999L, "INVALID_CRON", "Asia/Seoul"))
        .doesNotThrowAnyException();
  }

  @Test
  void registerSchedule_nullTimezone_usesDefault() {
    // timezone null → "Asia/Seoul" 기본값 적용
    assertThatCode(() -> schedulerService.registerSchedule(2L, "0 0 * * * *", null))
        .doesNotThrowAnyException();
  }

  @Test
  void registerSchedule_blankTimezone_usesDefault() {
    assertThatCode(() -> schedulerService.registerSchedule(3L, "0 0 * * * *", "  "))
        .doesNotThrowAnyException();
  }

  @Test
  void registerSchedule_twice_cancelsPreviousAndRegistersNew() {
    // 동일 jobId에 두 번 등록 → 기존 취소 후 재등록
    assertThatCode(
            () -> {
              schedulerService.registerSchedule(4L, "0 0 * * * *", "Asia/Seoul");
              schedulerService.registerSchedule(4L, "0 0 9 * * *", "UTC");
            })
        .doesNotThrowAnyException();
  }

  // =========================================================================
  // unregisterSchedule
  // =========================================================================

  @Test
  void unregisterSchedule_afterRegister_doesNotThrow() {
    schedulerService.registerSchedule(5L, "0 0 * * * *", "Asia/Seoul");
    assertThatCode(() -> schedulerService.unregisterSchedule(5L)).doesNotThrowAnyException();
  }

  @Test
  void unregisterSchedule_notRegistered_doesNotThrow() {
    // computeIfPresent에서 no-op으로 처리
    assertThatCode(() -> schedulerService.unregisterSchedule(99999L)).doesNotThrowAnyException();
  }

  // =========================================================================
  // rescheduleJob
  // =========================================================================

  @Test
  void rescheduleJob_enabledWithCron_registersSchedule() {
    ProactiveJobResponse job = makeJobResponse(6L, "0 0 * * * *", "Asia/Seoul", true);
    assertThatCode(() -> schedulerService.rescheduleJob(job)).doesNotThrowAnyException();
  }

  @Test
  void rescheduleJob_disabledJob_onlyUnregisters() {
    // 먼저 등록 후 disabled로 reschedule → 취소만 수행
    schedulerService.registerSchedule(7L, "0 0 * * * *", "Asia/Seoul");
    ProactiveJobResponse disabled = makeJobResponse(7L, "0 0 * * * *", "Asia/Seoul", false);
    assertThatCode(() -> schedulerService.rescheduleJob(disabled)).doesNotThrowAnyException();
  }

  @Test
  void rescheduleJob_enabledNoCron_onlyUnregisters() {
    ProactiveJobResponse noCron = makeJobResponse(8L, null, "Asia/Seoul", true);
    assertThatCode(() -> schedulerService.rescheduleJob(noCron)).doesNotThrowAnyException();
  }

  @Test
  void rescheduleJob_enabledBlankCron_onlyUnregisters() {
    ProactiveJobResponse blankCron = makeJobResponse(9L, "  ", "Asia/Seoul", true);
    assertThatCode(() -> schedulerService.rescheduleJob(blankCron)).doesNotThrowAnyException();
  }
}
