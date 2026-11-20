package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import com.smartfirehub.pipeline.dto.TriggerFireResult;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.dto.UpdatePipelineRequest;
import com.smartfirehub.pipeline.exception.PipelineAlreadyRunningException;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 수동 실행과 일정 트리거의 「건너뛰기」 판정 경쟁(WD-827).
 *
 * <p>수동 실행이 커밋 전일 때 SKIP 트리거가 잠금 없이 판정하면 진행 중 실행을 못 보고 겹쳐 실행한다. SKIP 판정이 수동 실행 가드와 같은 파이프라인 행 잠금
 * 뒤에서 일어나는지, ALLOW 는 진행 중 실행을 판정하지 않아 그대로 겹치는지 본다.
 *
 * <p>WD-853: 파이프라인 비활성화가 커밋 전일 때 트리거(SKIP·ALLOW 모두)가 잠금 뒤에서 활성 여부를 읽어, 커밋을 기다렸다가 비활성으로 건너뛰는지 본다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 커밋돼야 행 잠금 경쟁이 재현된다. 러너는 no-op 대역이라 접수된 실행이 PENDING 에
 * 머문다.
 */
class TriggerManualRunRaceTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private TriggerService triggerService;
  @Autowired private TriggerEventRepository triggerEventRepository;
  @Autowired private DSLContext dsl;

  /** WD-827: 비동기 러너를 no-op 으로 대체해 접수된 실행이 PENDING 에 고정되게 한다. */
  @MockitoBean private PipelineAsyncRunner asyncRunner;

  private Long userId;
  private Long pipelineId;
  private String unique;
  private final List<Long> triggers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    unique = String.valueOf(System.nanoTime());
    String username = "wd827_" + unique;
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-827 User")
            .set(USER.EMAIL, username + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
    pipelineId =
        pipelineService
            .createPipeline(new CreatePipelineRequest("wd827-" + unique, null, List.of()), userId)
            .id();
  }

  @AfterEach
  void cleanup() {
    // 스케줄러 등록이 새지 않도록 트리거는 서비스로 지운다
    for (Long t : triggers) {
      inTenantFixture(() -> triggerService.deleteTrigger(t));
    }
    inTenantFixture(() -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute());
    inTenantFixture(() -> dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(userId)).execute());
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
  }

  /** 먼 미래 cron 의 SCHEDULE 트리거를 만든다. */
  private Long createScheduleTrigger(String policy) {
    Long id =
        inTenantFixture(
            () ->
                triggerService
                    .createTrigger(
                        pipelineId,
                        new CreateTriggerRequest(
                            "wd827-" + policy,
                            TriggerType.SCHEDULE,
                            null,
                            Map.of(
                                "cron",
                                "0 0 1 1 *",
                                "timezone",
                                "Asia/Seoul",
                                "concurrencyPolicy",
                                policy)),
                        userId)
                    .id());
    triggers.add(id);
    return id;
  }

  private int executionCount() {
    return inTenantFixture(
        () -> dsl.fetchCount(PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(pipelineId)));
  }

  /** 커밋 전 수동 실행이 있으면 SKIP 트리거는 잠금에서 기다렸다가 진행 중 실행을 보고 건너뛴다. */
  @Test
  void skipTrigger_waitsForManualRun_thenSkipped() throws Exception {
    Long triggerId = createScheduleTrigger("SKIP");

    var outcome =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> pipelineService.executePipeline(pipelineId, userId),
            () -> triggerService.fireTrigger(triggerId, Map.of()));

    // 블로킹 여부만으로는 수정 전후를 구분할 수 없다. tx2 가 경합 구간을 지났다는 것만 확인하며,
    // 수정 전에도 트리거는 실행 행 FK 검사(FOR KEY SHARE 대 수동 실행의 FOR UPDATE)에서 막힌 뒤 FIRED 되었다.
    // 따라서 수정 전후를 가르는 것은 SKIPPED 결과와 실행 횟수 1 이다 (WD-827).
    assertThat(outcome.secondBlocked()).isTrue();
    assertThat(outcome.secondError()).isNull();
    assertThat(outcome.secondResult().outcome()).isEqualTo(TriggerFireResult.Outcome.SKIPPED);
    assertThat(outcome.secondResult().reason()).isEqualTo(TriggerService.REASON_CONCURRENT_SKIP);
    assertThat(executionCount()).isEqualTo(1);
    // 건너뜀은 트리거 이벤트로도 남아야 사용자가 이력에서 이유를 본다 — 실행 없이 SKIPPED·동시 실행 사유 하나만 (WD-827 리뷰 후속)
    List<TriggerEventResponse> events =
        inTenantFixture(() -> triggerEventRepository.findByTriggerId(triggerId, 10));
    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo("SKIPPED");
              assertThat(e.executionId()).isNull();
              assertThat(e.detail()).containsEntry("reason", TriggerService.REASON_CONCURRENT_SKIP);
            });
  }

  /**
   * 커밋 전 SKIP 트리거 실행이 있으면 수동 실행은 기다렸다가 409 로 거부된다.
   *
   * <p>반대 방향(트리거가 먼저)도 하나만 시작됨을 고정하는 시험이다. 이 방향은 WD-827 수정 전에도 통과한다 — 트리거의 실행 행 INSERT 가 FK 검사로
   * 파이프라인 행을 잡아 수동 실행의 잠금이 그 커밋을 기다리고, 이어 수동 가드(WD-825)가 PENDING 을 보기 때문이다. 수정을 가리는 시험은 {@link
   * #skipTrigger_waitsForManualRun_thenSkipped} 다.
   */
  @Test
  void manualRun_waitsForSkipTrigger_thenRejected() throws Exception {
    Long triggerId = createScheduleTrigger("SKIP");

    var outcome =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> triggerService.fireTrigger(triggerId, Map.of()),
            () -> pipelineService.executePipeline(pipelineId, userId));

    assertThat(outcome.secondBlocked()).isTrue();
    assertThat(outcome.secondError()).isInstanceOf(PipelineAlreadyRunningException.class);
    assertThat(executionCount()).isEqualTo(1);
  }

  /**
   * ALLOW 정책은 판정에서 잠그지 않으므로 진행 중 실행이 있어도 건너뛰지 않고 겹쳐 실행된다. 실행 행 INSERT 의 FK 검사(FOR KEY SHARE)가 수동
   * 실행의 FOR UPDATE 와 충돌해 커밋까지 기다리는 것은 WD-437 의 의도된 동작이라 대기 여부는 단언하지 않는다.
   */
  @Test
  void allowTrigger_notSkipped_overlaps() throws Exception {
    Long triggerId = createScheduleTrigger("ALLOW");

    var outcome =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> pipelineService.executePipeline(pipelineId, userId),
            () -> triggerService.fireTrigger(triggerId, Map.of()));

    assertThat(outcome.secondError()).isNull();
    assertThat(outcome.secondResult().outcome()).isEqualTo(TriggerFireResult.Outcome.FIRED);
    assertThat(executionCount()).isEqualTo(2);
  }

  /** 커밋 전 비활성화를 트리거 발화가 잠금에서 기다린 뒤 비활성으로 건너뛰는지 확인한다(WD-853). */
  private void assertWaitsForDeactivation(String policy) throws Exception {
    Long triggerId = createScheduleTrigger(policy);

    // 실제 사용자 경로(PipelineService.updatePipeline)로 비활성화한다 — 현재 값은 일시정지 트랜잭션 밖에서 읽어 둔다.
    var current = inTenantFixture(() -> pipelineService.getPipelineById(pipelineId));
    var deactivate =
        new UpdatePipelineRequest(
            current.name(), current.description(), false, List.of(), current.updatedAt());

    var outcome =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> pipelineService.updatePipeline(pipelineId, deactivate, userId),
            () -> triggerService.fireTrigger(triggerId, Map.of()));

    assertThat(outcome.secondBlocked()).isTrue();
    assertThat(outcome.secondError()).isNull();
    assertThat(outcome.secondResult().outcome()).isEqualTo(TriggerFireResult.Outcome.SKIPPED);
    assertThat(outcome.secondResult().reason()).isEqualTo(TriggerService.REASON_PIPELINE_INACTIVE);
    assertThat(executionCount()).isZero();
    var event = inTenantFixture(() -> triggerEventRepository.findByTriggerId(triggerId, 1)).get(0);
    assertThat(event.eventType()).isEqualTo("SKIPPED");
    assertThat(event.detail()).containsEntry("reason", TriggerService.REASON_PIPELINE_INACTIVE);
  }

  @Test
  void skipTrigger_waitsForUncommittedDeactivation_thenSkippedInactive() throws Exception {
    assertWaitsForDeactivation("SKIP");
  }

  @Test
  void allowTrigger_waitsForUncommittedDeactivation_thenSkippedInactive() throws Exception {
    assertWaitsForDeactivation("ALLOW");
  }
}
