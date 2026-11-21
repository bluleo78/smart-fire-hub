package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import com.smartfirehub.pipeline.dto.TriggerFireResult;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 트리거 발화의 실행 접수 실패가 ERROR 이벤트로 남는지 본다(WD-858).
 *
 * <p>접수({@code PipelineExecutionService.executePipeline}, @Transactional)에서 예외가 프록시를 빠져나가면 발화 트랜잭션이
 * rollback-only 로 표시돼, catch 에서 남긴 ERROR 이벤트가 커밋 때 {@code UnexpectedRollbackException} 으로 함께 사라졌다.
 * 저장점(NESTED)으로 접수만 감싸 저장점 롤백이 표시를 지우는지 확인한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 실제 커밋이 일어나야 rollback-only 가 드러난다. 테넌트 컨텍스트는 {@code
 * IntegrationTestBase} 가 세우고 트랜잭션은 fireTrigger 가 직접 연다(픽스처 트랜잭션으로 감싸면 버그가 가려진다).
 */
class TriggerFireAdmissionErrorTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private TriggerService triggerService;
  /** FIRED 기록 실패를 흉내 내려 스파이한다(WD-858). 스텁하지 않은 호출은 실제 구현으로 간다. */
  @MockitoSpyBean private TriggerEventRepository triggerEventRepository;
  @Autowired private DSLContext dsl;

  /** 비동기 러너를 no-op 으로 대체한다 — 접수 실패만 본다. */
  @MockitoBean private PipelineAsyncRunner asyncRunner;

  /** 서비스 프록시가 아니라 안쪽 저장소를 스파이해야 예외가 @Transactional 프록시를 빠져나온다. */
  @MockitoSpyBean private PipelineExecutionRepository executionRepository;

  private Long userId;
  private Long pipelineId;
  private Long triggerId;

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    String username = "wd858_" + unique;
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-858 User")
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
            .createPipeline(
                new CreatePipelineRequest(
                    "wd858-" + unique,
                    null,
                    // 스텝이 하나 있어야 접수가 실행 행을 만든 뒤 스텝 실행 행 생성에서 실패시킬 수 있다
                    List.of(
                        new PipelineStepRequest(
                            "step1", null, "SQL", "SELECT 1", null, null, null))),
                userId)
            .id();
    triggerId =
        inTenantFixture(
            () ->
                triggerService
                    .createTrigger(
                        pipelineId,
                        new CreateTriggerRequest(
                            "wd858-admission",
                            TriggerType.SCHEDULE,
                            null,
                            Map.of(
                                "cron",
                                "0 0 1 1 *",
                                "timezone",
                                "Asia/Seoul",
                                "concurrencyPolicy",
                                "ALLOW")),
                        userId)
                    .id());
  }

  @AfterEach
  void cleanup() {
    // 스파이 스텁이 다음 시험·정리 단계로 새지 않게 비운다(WD-858)
    reset(executionRepository, triggerEventRepository);
    // 스케줄러 등록이 새지 않도록 트리거는 서비스로 지운다
    inTenantFixture(() -> triggerService.deleteTrigger(triggerId));
    inTenantFixture(() -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute());
    inTenantFixture(() -> dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(userId)).execute());
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
  }

  /** 접수 실패는 예외 없이 ERROR 결과로 돌아오고, ERROR 이벤트가 커밋 뒤에도 남는다(WD-858). */
  @Test
  void admissionFailure_recordsErrorEvent_andReturnsError() {
    // 실행 행 INSERT 뒤에 실패시킨다 — 저장점 롤백이 반쯤 만들어진 실행 행을 지우는지 확인하려는 것이다(WD-858)
    doThrow(new IllegalStateException("WD-858 접수 실패"))
        .when(executionRepository)
        .createStepExecution(any(), any(), any());

    TriggerFireResult result = triggerService.fireTrigger(triggerId, Map.of());

    assertErrorOnly(result, "WD-858 접수 실패");
  }

  /**
   * FIRED 이벤트 기록이 실패해도 접수된 실행은 저장점과 함께 롤백되고 ERROR 이벤트가 남는다(WD-858).
   *
   * <p>저장점이 접수만 감싸면 이 실패가 발화 트랜잭션을 rollback-only 로 만들어 ERROR 기록이 사라진다.
   */
  @Test
  void firedEventFailure_rollsBackExecution_recordsErrorEvent() {
    // FIRED 만 실패시키고 ERROR 기록은 실제로 통과시킨다
    doThrow(new IllegalStateException("WD-858 FIRED 기록 실패"))
        .when(triggerEventRepository)
        .create(anyLong(), anyLong(), any(), eq("FIRED"), any());

    TriggerFireResult result = triggerService.fireTrigger(triggerId, Map.of());

    assertErrorOnly(result, "WD-858 FIRED 기록 실패");
  }

  /**
   * 발화가 예외 없이 ERROR 로 끝났고, 이벤트는 주어진 원문의 ERROR 하나뿐이며(FIRED 없음), 실행·스텝 실행 행이 저장점 롤백으로 하나도 남지
   * 않았음을 단언한다(WD-858).
   */
  private void assertErrorOnly(TriggerFireResult result, String expectedError) {
    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.ERROR);
    assertThat(result.reason()).isEqualTo(TriggerService.REASON_FIRE_ERROR);
    List<TriggerEventResponse> events =
        inTenantFixture(() -> triggerEventRepository.findByTriggerId(triggerId, 10));
    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo("ERROR");
              assertThat(e.detail()).containsEntry("error", expectedError);
            });
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchCount(
                        PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(pipelineId))))
        .isZero();
    assertThat(
            inTenantFixture(
                () ->
                    dsl.fetchCount(
                        PIPELINE_STEP_EXECUTION,
                        PIPELINE_STEP_EXECUTION.STEP_ID.in(
                            dsl.select(PIPELINE_STEP.ID)
                                .from(PIPELINE_STEP)
                                .where(PIPELINE_STEP.PIPELINE_ID.eq(pipelineId))))))
        .isZero();
  }
}
