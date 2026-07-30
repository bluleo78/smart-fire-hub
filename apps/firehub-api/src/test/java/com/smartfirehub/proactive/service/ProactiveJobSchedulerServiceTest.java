package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

  // =========================================================================
  // next_execute_at 반영 (#348)
  // 목록의 "다음 실행" 컬럼이 전 작업 상시 '-' 이던 결함 — 값을 채우는 경로가 없었다.
  // 스케줄 등록/해제가 모두 이 서비스를 지나므로, 여기서 DB에 반영되는지 검증한다.
  // =========================================================================

  /** DB에 실제 잡을 만들고 next_execute_at 을 읽어오는 헬퍼 */
  private LocalDateTime readNextExecuteAt(Long jobId) {
    return dsl.select(DSL.field(DSL.name("proactive_job", "next_execute_at"), LocalDateTime.class))
        .from(DSL.table(DSL.name("proactive_job")))
        .where(DSL.field(DSL.name("proactive_job", "id"), Long.class).eq(jobId))
        .fetchOne(0, LocalDateTime.class);
  }

  @Test
  @DisplayName("잡 생성 시 실행 전이라도 next_execute_at 이 채워진다 (#348)")
  void createJob_populatesNextExecuteAt() {
    // 한 번도 실행되지 않은 잡도 "다음 실행"을 표시할 수 있어야 한다
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "다음 실행 검증 잡", "프롬프트", null, "0 0 9 * * *", "Asia/Seoul", true, Map.of()),
            testUserId);

    LocalDateTime next = readNextExecuteAt(created.id());
    assertThat(next).isNotNull();
    // UTC 벽시계로 저장되며 항상 미래여야 한다
    assertThat(next).isAfter(LocalDateTime.now(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("cron 을 수정하면 next_execute_at 이 재계산된다 (#348)")
  void updateJob_changingCron_recomputesNextExecuteAt() {
    // 사용자가 스케줄을 바꾸면 "다음 실행"도 따라 움직여야 한다 — 가장 흔한 실사용 경로
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "cron 수정 검증 잡", "프롬프트", null, "0 0 9 * * *", "Asia/Seoul", true, Map.of()),
            testUserId);
    LocalDateTime before = readNextExecuteAt(created.id());
    assertThat(before).isNotNull();

    proactiveJobService.updateJob(
        created.id(),
        new UpdateProactiveJobRequest(null, null, null, "0 0 21 * * *", null, null, null),
        testUserId);

    LocalDateTime after = readNextExecuteAt(created.id());
    assertThat(after).isNotNull().isNotEqualTo(before);
  }

  @Test
  @DisplayName("스케줄 등록에 실패하면 next_execute_at 을 채우지 않는다 — 실행되지 않을 잡에 미래 시각을 보이면 안 된다")
  void registerSchedule_whenCronCannotRegister_leavesNextExecuteAtNull() {
    // 파싱 불가능한 cron 은 Spring CronTrigger 가 거부해 스케줄 자체가 등록되지 않는다.
    // 이 경우 "다음 실행"을 채우면 실제로는 영영 돌지 않는 잡이 곧 실행될 것처럼 보인다.
    // (#354 이전에는 5필드 cron 이 이 케이스였으나, 이제 정규화되어 정상 등록된다.)
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "등록 실패 검증 잡", "프롬프트", null, "0 0 9 * * *", "Asia/Seoul", true, Map.of()),
            testUserId);
    assertThat(readNextExecuteAt(created.id())).isNotNull();

    schedulerService.registerSchedule(created.id(), "not a cron at all", "Asia/Seoul");
    assertThat(readNextExecuteAt(created.id())).isNull();
  }

  @Test
  @DisplayName("5필드 cron 도 정규화되어 스케줄에 등록된다 (#354)")
  void registerSchedule_withFiveFieldCron_registersAndPopulatesNextExecuteAt() {
    // 검증 도입(#221) 이전에 만들어진 레거시 잡들은 5필드 cron('0 9 * * *')으로 저장돼 있다.
    // 등록 경로가 원시 문자열을 CronTrigger 에 그대로 넘기던 때는 부팅 시 ERROR 만 남기고
    // enabled=true 인 채 영구 미실행 상태가 됐다(#354). 정규화 후에는 정상 등록돼야 한다.
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "5필드 cron 잡", "프롬프트", null, "0 0 9 * * *", "Asia/Seoul", true, Map.of()),
            testUserId);

    schedulerService.registerSchedule(created.id(), "0 9 * * *", "Asia/Seoul");

    // 등록에 성공해야만 next_execute_at 이 채워진다 — 등록 성공의 관측 가능한 증거
    LocalDateTime next = readNextExecuteAt(created.id());
    assertThat(next).isNotNull();
    assertThat(next).isAfter(LocalDateTime.now(ZoneOffset.UTC));
    // 6필드로 쓴 동일 스케줄과 같은 시각이어야 한다 — 정규화가 의미를 바꾸지 않음을 확인
    assertThat(next)
        .isEqualTo(
            com.smartfirehub.proactive.util.ProactiveCron.nextExecuteAtUtc(
                "0 9 * * *", "Asia/Seoul"));
  }

  @Test
  @DisplayName("UI 프리셋이 보내는 5필드 cron 으로도 잡을 생성할 수 있다 (#354)")
  void createJob_withFiveFieldCron_succeeds() {
    // 작업 편집 폼의 CRON_PRESETS 는 5필드('0 9 * * *')를 보내는데, 사전 검증이 원시 문자열을
    // 파싱하던 때는 UI 프리셋으로 만든 잡이 400 으로 막혔다. 등록 경로와 같은 규칙을 써야 한다.
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "5필드 프리셋 잡", "프롬프트", null, "0 9 * * *", "Asia/Seoul", true, Map.of()),
            testUserId);

    // 사용자가 입력한 원문은 그대로 보존하고, 해석 시점에만 정규화한다
    assertThat(created.cronExpression()).isEqualTo("0 9 * * *");
    assertThat(readNextExecuteAt(created.id())).isNotNull();
  }

  @Test
  @DisplayName("잡을 비활성화하면 next_execute_at 이 비워진다 (#348)")
  void toggleJobOff_clearsNextExecuteAt() {
    ProactiveJobResponse created =
        proactiveJobService.createJob(
            new CreateProactiveJobRequest(
                "토글 검증 잡", "프롬프트", null, "0 0 9 * * *", "Asia/Seoul", true, Map.of()),
            testUserId);
    assertThat(readNextExecuteAt(created.id())).isNotNull();

    // 비활성 잡에 과거 계산값이 남아 있으면 곧 실행될 것처럼 보인다
    proactiveJobService.toggleJob(created.id(), testUserId, false);
    assertThat(readNextExecuteAt(created.id())).isNull();

    // 다시 켜면 재계산되어야 한다
    proactiveJobService.toggleJob(created.id(), testUserId, true);
    assertThat(readNextExecuteAt(created.id())).isNotNull();
  }
}
