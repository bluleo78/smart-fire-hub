package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.proactive.util.ProactiveCron;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SCHEDULE 트리거 수정 경로의 cron 검증·정규화와 커밋 후 재등록 통합 테스트(WD-107).
 *
 * <p>결함: 수정 경로가 생성과 달리 {@code validateScheduleConfig} 를 건너뛰어 5필드 cron(웹 프리셋 "매일 09:00" 등)·잘못된 cron
 * 이 그대로 저장됐다. 커밋 후 등록은 기존 스케줄을 먼저 취소한 뒤 실패해, 204 "수정되었습니다" 뒤로 스케줄이 사라지고 화면엔 옛 "다음 실행"이 남았다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 쓰지 않는다.</b> 재등록과 {@code nextFireTime} 기록은 커밋
 * 후(afterCommit)에만 일어나므로, 롤백 전용 테스트 트랜잭션 안에서는 그 경로가 아예 실행되지 않아 단언이 공허하게 통과한다. 서비스 호출마다 실제로 커밋하고
 * {@link #cleanup()} 에서 직접 정리한다. 직접 DB 를 읽지 않고 {@code @Transactional} 서비스로 읽는 이유는 RLS GUC 가 트랜잭션
 * 안에서만 주입되기 때문이다.
 */
class TriggerScheduleUpdateTest extends IntegrationTestBase {

  @Autowired private TriggerService triggerService;
  @Autowired private TriggerSchedulerService schedulerService;
  @Autowired private PipelineService pipelineService;
  @Autowired private DSLContext dsl;
  @Autowired private TriggerRepository triggerRepository;

  private Long userId;
  private Long pipelineId;
  private final List<Long> createdTriggerIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "sched_update_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Schedule Update User")
            .set(USER.EMAIL, "sched_update_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("sched-update-" + unique, "WD-107", List.of()), userId)
            .id();
  }

  /** 컨텍스트가 캐시돼 다음 테스트와 공유되므로, 등록한 스케줄을 반드시 내려 이후 테스트에서 발화하지 않게 한다. */
  @AfterEach
  void cleanup() {
    for (Long id : createdTriggerIds) {
      schedulerService.unregisterSchedule(id);
      try {
        triggerService.deleteTrigger(id);
      } catch (Exception ignored) {
        // 이미 지워졌으면 무시한다.
      }
    }
    pipelineService.deletePipelineUnchecked(pipelineId);
    awaitSecurityAudit();
    // 파이프라인·트리거 변경 감사(WD-462) — 행위자 행은 "user" FK 라 사용자보다 먼저, 행위자 없는 정리 경로(deleteTrigger(id))가 남긴
    // system 행은 이 테스트 트리거 것만 지운다(RLS 대상이라 테넌트 트랜잭션 안에서).
    inTenantFixture(
        () -> {
          dsl.execute("delete from audit_log where user_id = ?", userId);
          for (Long id : createdTriggerIds) {
            dsl.execute(
                "delete from audit_log where user_id is null and resource = 'pipeline_trigger'"
                    + " and resource_id = ?",
                String.valueOf(id));
          }
        });
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  /** UTC·ALLOW 로 스케줄 트리거를 만든다 — 기본값(Asia/Seoul·SKIP)과 달라야 부분 수정 시 보존 여부를 구분할 수 있다. */
  private TriggerResponse createSchedule(String cron) {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "매일새벽",
                TriggerType.SCHEDULE,
                null,
                Map.of("cron", cron, "timezone", "UTC", "concurrencyPolicy", "ALLOW")),
            userId);
    createdTriggerIds.add(created.id());
    return created;
  }

  @SuppressWarnings("unchecked")
  private Future<?> scheduledFuture(Long triggerId) {
    Map<Long, Future<?>> tasks =
        (Map<Long, Future<?>>) ReflectionTestUtils.getField(schedulerService, "scheduledTasks");
    return tasks.get(triggerId);
  }

  private static ZonedDateTime inZone(String isoInstant, String zone) {
    return Instant.parse(isoInstant).atZone(ZoneId.of(zone));
  }

  /**
   * 재현 시나리오: 웹 프리셋이 보내는 5필드 cron 으로 수정하면 6필드로 정규화해 저장하고, 새 cron 으로 다시 등록해 "다음 실행"도 새 시각으로 바뀌어야 한다.
   * 요청이 cron 만 보내도 기존 timezone·concurrencyPolicy 는 지워지지 않아야 한다.
   */
  @Test
  void updateWithFiveFieldCron_normalizesReRegistersAndRefreshesNextFireTime() {
    TriggerResponse created = createSchedule("0 3 * * *");
    TriggerResponse before = triggerService.getTriggerById(created.id());
    assertThat(inZone(before.nextFireTime(), "UTC").getHour()).isEqualTo(3);
    Future<?> oldFuture = scheduledFuture(created.id());
    assertThat(oldFuture).isNotNull();

    triggerService.updateTrigger(
        created.id(),
        new UpdateTriggerRequest("매일새벽", null, null, Map.of("cron", "0 9 * * *")),
        userId);

    TriggerResponse after = triggerService.getTriggerById(created.id());
    assertThat(after.config())
        .containsEntry("cron", "0 0 9 * * *")
        .containsEntry("timezone", "UTC")
        .containsEntry("concurrencyPolicy", "ALLOW");
    // 다음 실행이 새 cron(09:00, 보존된 UTC 기준)으로 갱신돼야 한다 — 옛 03:00 이 남던 것이 결함의 화면 증상이다.
    ZonedDateTime next = inZone(after.nextFireTime(), "UTC");
    assertThat(next.getHour()).isEqualTo(9);
    assertThat(next.getMinute()).isZero();
    // 새 스케줄로 교체되고 옛 스케줄은 취소돼야 한다.
    Future<?> newFuture = scheduledFuture(created.id());
    assertThat(newFuture).isNotNull().isNotSameAs(oldFuture);
    assertThat(newFuture.isCancelled()).isFalse();
    assertThat(oldFuture.isCancelled()).isTrue();
  }

  /** 잘못된 cron 으로 수정하면 생성과 같은 문구로 거부하고(→400), 저장값·스케줄·다음 실행은 옛 값 그대로여야 한다. */
  @Test
  void updateWithInvalidCron_isRejectedAndKeepsOldConfigAndSchedule() {
    TriggerResponse created = createSchedule("0 3 * * *");
    TriggerResponse before = triggerService.getTriggerById(created.id());
    Future<?> oldFuture = scheduledFuture(created.id());

    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(),
                    new UpdateTriggerRequest(
                        "바뀐이름", null, null, Map.of("cron", "99 * * * *", "timezone", "UTC")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("유효하지 않은 cron 표현식입니다: 99 * * * *");

    TriggerResponse after = triggerService.getTriggerById(created.id());
    assertThat(after.name()).isEqualTo("매일새벽");
    assertThat(after.config()).isEqualTo(before.config());
    assertThat(after.nextFireTime()).isEqualTo(before.nextFireTime());
    assertThat(scheduledFuture(created.id())).isSameAs(oldFuture);
    assertThat(oldFuture.isCancelled()).isFalse();
  }

  /** 잘못된 시간대도 커밋 전에 거부해야 한다 — 그렇지 않으면 저장은 성공하고 커밋 후 등록만 실패한다. */
  @Test
  void updateWithInvalidTimezone_isRejectedBeforeSave() {
    TriggerResponse created = createSchedule("0 3 * * *");
    TriggerResponse before = triggerService.getTriggerById(created.id());

    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(),
                    new UpdateTriggerRequest(
                        "매일새벽", null, null, Map.of("cron", "0 9 * * *", "timezone", "Mars/Base")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("유효하지 않은 시간대입니다");

    assertThat(triggerService.getTriggerById(created.id()).config()).isEqualTo(before.config());
  }

  /** config 없이 이름만 바꾸는 수정은 검증 없이 통과하고 기존 config·스케줄을 그대로 둔다. */
  @Test
  void renameOnly_keepsConfigAndSchedule() {
    TriggerResponse created = createSchedule("0 3 * * *");
    TriggerResponse before = triggerService.getTriggerById(created.id());
    Future<?> oldFuture = scheduledFuture(created.id());

    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("새이름", null, null, null), userId);

    TriggerResponse after = triggerService.getTriggerById(created.id());
    assertThat(after.name()).isEqualTo("새이름");
    assertThat(after.config()).isEqualTo(before.config());
    assertThat(inZone(after.nextFireTime(), "UTC").getHour()).isEqualTo(3);
    // config 를 바꾸지 않은 수정은 이미 돌고 있는 스케줄을 다시 등록하지 않는다 — 같은 future 가 취소되지 않고 남아야 한다.
    assertThat(scheduledFuture(created.id())).isSameAs(oldFuture);
    assertThat(oldFuture.isCancelled()).isFalse();
  }

  /**
   * 검증 이전에 잘못된 cron 이 저장된 옛 행을 흉내 낸다 — 검증을 거치지 않는 리포지토리로 config 를 직접 덮고, 재기동 때 등록에 실패한 것처럼 스케줄을
   * 내린다.
   */
  private Long storedInvalidRow() {
    TriggerResponse created = createSchedule("0 3 * * *");
    triggerRepository.update(
        created.id(),
        new UpdateTriggerRequest(
            "매일새벽",
            null,
            null,
            Map.of("cron", "99 * * * *", "timezone", "UTC", "concurrencyPolicy", "ALLOW")),
        userId);
    schedulerService.unregisterSchedule(created.id());
    return created.id();
  }

  /**
   * 잘못된 cron 이 저장된 옛 행의 이름만 바꾸면 성공(컨트롤러 204)해야 한다(WD-107 리뷰). 커밋 후 재등록이 실패해도 예외로 응답을 깨지 않고, 스케줄
   * 없음·"다음 실행" 비움 상태로 정리한다.
   */
  @Test
  void renameOnly_onStoredInvalidRow_succeedsAndLeavesUnscheduled() {
    Long id = storedInvalidRow();

    triggerService.updateTrigger(id, new UpdateTriggerRequest("새이름", null, null, null), userId);

    TriggerResponse after = triggerService.getTriggerById(id);
    assertThat(after.name()).isEqualTo("새이름");
    assertThat(after.config()).containsEntry("cron", "99 * * * *");
    assertThat(schedulerService.isScheduled(id)).isFalse();
    assertThat(after.nextFireTime()).isNull();
  }

  /** 잘못된 cron 이 저장된 옛 행은 끌 수는 있지만, 켜기(토글·수정)는 커밋 전에 400 으로 막혀 꺼진 채 남아야 한다. */
  @Test
  void enable_onStoredInvalidRow_isRejectedBeforeCommit_butDisableWorks() {
    Long id = storedInvalidRow();

    triggerService.toggleTrigger(id, false);
    assertThat(triggerService.getTriggerById(id).isEnabled()).isFalse();

    assertThatThrownBy(() -> triggerService.toggleTrigger(id, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("유효하지 않은 cron 표현식입니다: 99 * * * *");
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    id, new UpdateTriggerRequest("매일새벽", true, null, null), userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("유효하지 않은 cron 표현식입니다: 99 * * * *");

    assertThat(triggerService.getTriggerById(id).isEnabled()).isFalse();
    assertThat(schedulerService.isScheduled(id)).isFalse();
  }

  /** WD-366 — 1분보다 잦은 cron(매초)은 생성·수정 모두 400 이고, 수정이면 옛 값·스케줄이 그대로 남아야 한다. */
  @Test
  void tooFrequentCron_isRejectedOnCreateAndUpdate() {
    String expected = ProactiveCron.TOO_FREQUENT_MESSAGE + ": * * * * * *";
    assertThatThrownBy(
            () ->
                triggerService.createTrigger(
                    pipelineId,
                    new CreateTriggerRequest(
                        "매초", TriggerType.SCHEDULE, null, Map.of("cron", "* * * * * *")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expected);

    TriggerResponse created = createSchedule("0 3 * * *");
    TriggerResponse before = triggerService.getTriggerById(created.id());
    Future<?> oldFuture = scheduledFuture(created.id());

    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(),
                    new UpdateTriggerRequest("매초", null, null, Map.of("cron", "* * * * * *")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expected);

    assertThat(triggerService.getTriggerById(created.id()).config()).isEqualTo(before.config());
    assertThat(scheduledFuture(created.id())).isSameAs(oldFuture);

    // 매분(5필드 "* * * * *" → 초 0)은 최소 간격과 같아 저장된다
    assertThat(createSchedule("* * * * *").config().get("cron")).isEqualTo("0 * * * * *");
  }

  /**
   * WD-366 — 검증 전에 매초로 저장된 옛 행은 재기동·토글로 다시 걸리지 않는다. 스케줄 등록 자체가 거부하고(재기동은 트리거마다 예외를 잡는다),
   * 켜기는 커밋 전에 400 이다.
   */
  @Test
  void storedTooFrequentRow_isNotRegisteredAndCannotBeEnabled() {
    TriggerResponse created = createSchedule("0 3 * * *");
    Map<String, Object> perSecond =
        Map.of("cron", "* * * * * *", "timezone", "UTC", "concurrencyPolicy", "ALLOW");
    triggerRepository.update(
        created.id(), new UpdateTriggerRequest("매일새벽", null, null, perSecond), userId);
    schedulerService.unregisterSchedule(created.id());

    assertThatThrownBy(() -> schedulerService.registerSchedule(created.id(), perSecond))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(ProactiveCron.TOO_FREQUENT_MESSAGE);
    assertThat(schedulerService.isScheduled(created.id())).isFalse();

    triggerService.toggleTrigger(created.id(), false);
    assertThatThrownBy(() -> triggerService.toggleTrigger(created.id(), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(ProactiveCron.TOO_FREQUENT_MESSAGE);
    assertThat(schedulerService.isScheduled(created.id())).isFalse();
  }

  /** 문법은 맞지만 다음 실행 시각이 없는 cron(2월 30일)은 생성·수정 모두 400 이고, 수정이면 옛 값·스케줄이 그대로 남아야 한다. */
  @Test
  void cronWithoutNextExecution_isRejectedOnCreateAndUpdate() {
    assertThatThrownBy(
            () ->
                triggerService.createTrigger(
                    pipelineId,
                    new CreateTriggerRequest(
                        "없는날", TriggerType.SCHEDULE, null, Map.of("cron", "0 0 0 30 2 *")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("다음 실행 시각이 없는 cron 표현식입니다: 0 0 0 30 2 *");

    TriggerResponse created = createSchedule("0 3 * * *");
    TriggerResponse before = triggerService.getTriggerById(created.id());
    Future<?> oldFuture = scheduledFuture(created.id());

    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(),
                    new UpdateTriggerRequest("매일새벽", null, null, Map.of("cron", "0 0 0 30 2 *")),
                    userId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("다음 실행 시각이 없는 cron 표현식입니다: 0 0 0 30 2 *");

    TriggerResponse after = triggerService.getTriggerById(created.id());
    assertThat(after.config()).isEqualTo(before.config());
    assertThat(after.nextFireTime()).isEqualTo(before.nextFireTime());
    assertThat(scheduledFuture(created.id())).isSameAs(oldFuture);
    assertThat(oldFuture.isCancelled()).isFalse();
  }

  /**
   * 비활성 트리거는 실행되지 않으므로 응답에 "다음 실행"이 없어야 한다(WD-114). 끄는 경로(토글·수정)와 목록·단건 조회 모두 같고, 다시 켜면 새로 등록된 다음
   * 실행 시각이 돌아와야 한다 — 숨김이 켜진 트리거까지 지우면 안 된다.
   */
  @Test
  void disabledTrigger_hidesNextFireTime_andReEnableRestoresIt() {
    TriggerResponse created = createSchedule("0 3 * * *");
    assertThat(triggerService.getTriggerById(created.id()).nextFireTime()).isNotNull();

    triggerService.toggleTrigger(created.id(), false);
    TriggerResponse disabled = triggerService.getTriggerById(created.id());
    assertThat(disabled.isEnabled()).isFalse();
    assertThat(disabled.nextFireTime()).isNull();
    // 원본 triggerState 를 그대로 읽는 소비자(AI 에이전트 list_triggers)에게도 옛 시각이 새지 않아야 한다.
    assertThat(disabled.triggerState()).doesNotContainKey("nextFireTime");
    assertThat(schedulerService.isScheduled(created.id())).isFalse();
    assertThat(triggerService.getTriggers(pipelineId))
        .filteredOn(t -> t.id().equals(created.id()))
        .singleElement()
        .satisfies(t -> assertThat(t.nextFireTime()).isNull());

    triggerService.toggleTrigger(created.id(), true);
    assertThat(inZone(triggerService.getTriggerById(created.id()).nextFireTime(), "UTC").getHour())
        .isEqualTo(3);

    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("매일새벽", false, null, null), userId);
    assertThat(triggerService.getTriggerById(created.id()).nextFireTime()).isNull();
    assertThat(schedulerService.isScheduled(created.id())).isFalse();
  }
}
