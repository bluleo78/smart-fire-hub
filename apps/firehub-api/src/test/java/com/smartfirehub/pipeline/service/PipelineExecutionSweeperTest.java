package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.smartfirehub.global.execution.ExecutionMessages;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.dto.NotificationEvent;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * 중단 실행 정리기(WD-230) 통합 테스트 — 실제 DB·RLS·리스너로 「주인이 죽은 실행」이 닫히고 알려지는지 본다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 붙이지 않는다: 정리기가 스스로 트랜잭션·테넌트 컨텍스트를 세우는지, 비동기 리스너가 커밋된 데이터를 보는지가
 * 검증 대상이다.
 */
@RecordApplicationEvents
class PipelineExecutionSweeperTest extends IntegrationTestBase {

  @Autowired private PipelineExecutionSweeper sweeper;
  @Autowired private PipelineExecutionRegistry registry;
  @Autowired private PipelineExecutionRepository executionRepository;
  @Autowired private PipelineService pipelineService;
  @Autowired private DSLContext dsl;
  @Autowired private ApplicationEvents applicationEvents;
  @Autowired private ApplicationEventPublisher eventPublisher;

  /** 알림 브로드캐스트만 관찰한다(실제 동작은 유지). */
  @MockitoSpyBean private SseEmitterRegistry sseRegistry;

  private Long userId;
  private Long pipelineId;
  private final List<Long> createdPipelines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "sweep_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Sweep User")
            .set(USER.EMAIL, "sweep_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
    pipelineId = newPipeline("sweep-" + unique);
  }

  @AfterEach
  void cleanup() throws InterruptedException {
    // 비동기 리스너/연쇄 실행이 가라앉길 잠시 기다린 뒤, 파이프라인을 지워 실행·단계·트리거를 CASCADE 로 정리한다.
    Thread.sleep(500);
    inTenantFixture(
        () -> {
          dsl.deleteFrom(table(name("pipeline_trigger")))
              .where(field(name("pipeline_id"), Long.class).in(createdPipelines))
              .execute();
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.in(createdPipelines)).execute();
          dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(userId)).execute();
        });
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
  }

  private Long newPipeline(String pipelineName) {
    Long id =
        pipelineService
            .createPipeline(new CreatePipelineRequest(pipelineName, null, List.of()), userId)
            .id();
    createdPipelines.add(id);
    return id;
  }

  /** 실행 행을 직접 심는다. heartbeat/startedAt 은 null 가능. 단계는 RUNNING 하나 + PENDING 하나. */
  private Long insertExecution(
      Long forPipeline, String status, LocalDateTime heartbeat, LocalDateTime startedAt) {
    return inTenantFixture(
        () -> {
          Long id =
              dsl.insertInto(PIPELINE_EXECUTION)
                  .set(PIPELINE_EXECUTION.PIPELINE_ID, forPipeline)
                  .set(PIPELINE_EXECUTION.STATUS, status)
                  .set(PIPELINE_EXECUTION.EXECUTED_BY, userId)
                  .set(PIPELINE_EXECUTION.TRIGGERED_BY, "MANUAL")
                  .set(field(name("heartbeat_at"), LocalDateTime.class), heartbeat)
                  .set(PIPELINE_EXECUTION.STARTED_AT, startedAt)
                  .returning(PIPELINE_EXECUTION.ID)
                  .fetchOne()
                  .getId();
          Long running = executionRepository.createStepExecution(id, null, "running-step");
          executionRepository.createStepExecution(id, null, "pending-step");
          dsl.update(PIPELINE_STEP_EXECUTION)
              .set(PIPELINE_STEP_EXECUTION.STATUS, "RUNNING")
              .where(PIPELINE_STEP_EXECUTION.ID.eq(running))
              .execute();
          return id;
        });
  }

  private static LocalDateTime minutesAgo(long m) {
    return LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m);
  }

  private String statusOf(Long executionId) {
    return inTenantFixture(
        () ->
            dsl.select(PIPELINE_EXECUTION.STATUS)
                .from(PIPELINE_EXECUTION)
                .where(PIPELINE_EXECUTION.ID.eq(executionId))
                .fetchOne(PIPELINE_EXECUTION.STATUS));
  }

  private List<String> stepStatusesOf(Long executionId) {
    return inTenantFixture(
        () ->
            dsl.select(PIPELINE_STEP_EXECUTION.STATUS)
                .from(PIPELINE_STEP_EXECUTION)
                .where(PIPELINE_STEP_EXECUTION.EXECUTION_ID.eq(executionId))
                .orderBy(PIPELINE_STEP_EXECUTION.ID)
                .fetch(PIPELINE_STEP_EXECUTION.STATUS));
  }

  private List<PipelineCompletedEvent> eventsFor(Long executionId) {
    return applicationEvents.stream(PipelineCompletedEvent.class)
        .filter(e -> executionId.equals(e.executionId()))
        .toList();
  }

  @Test
  void staleRunning_isFailedWithMessage_stepsClosed_andOneInterruptedEvent() {
    Long stale = insertExecution(pipelineId, "RUNNING", minutesAgo(30), minutesAgo(31));

    int closed = sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    assertThat(closed).isGreaterThanOrEqualTo(1);
    assertThat(statusOf(stale)).isEqualTo("FAILED");
    String message =
        inTenantFixture(
            () ->
                dsl.select(PIPELINE_EXECUTION.ERROR_MESSAGE)
                    .from(PIPELINE_EXECUTION)
                    .where(PIPELINE_EXECUTION.ID.eq(stale))
                    .fetchOne(PIPELINE_EXECUTION.ERROR_MESSAGE));
    assertThat(message).isEqualTo(ExecutionMessages.INTERRUPTED);
    // 실행 중이던 단계는 FAILED, 시작 못 한 단계는 SKIPPED.
    assertThat(stepStatusesOf(stale)).containsExactly("FAILED", "SKIPPED");

    List<PipelineCompletedEvent> events = eventsFor(stale);
    assertThat(events).hasSize(1);
    PipelineCompletedEvent e = events.get(0);
    assertThat(e.interrupted()).isTrue();
    assertThat(e.status()).isEqualTo("FAILED");
    assertThat(e.pipelineId()).isEqualTo(pipelineId);
    assertThat(e.createdBy()).isEqualTo(userId);
  }

  @Test
  void recentHeartbeat_isLeftAlone() {
    Long fresh = insertExecution(pipelineId, "RUNNING", minutesAgo(1), minutesAgo(40));

    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    assertThat(statusOf(fresh)).isEqualTo("RUNNING");
    assertThat(eventsFor(fresh)).isEmpty();
  }

  @Test
  void nullHeartbeatWithOldStartedAt_isSwept() {
    Long legacy = insertExecution(pipelineId, "RUNNING", null, minutesAgo(40));

    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    assertThat(statusOf(legacy)).isEqualTo("FAILED");
    assertThat(eventsFor(legacy)).hasSize(1);
  }

  @Test
  void finishedExecution_isLeftAlone() {
    Long done = insertExecution(pipelineId, "COMPLETED", minutesAgo(60), minutesAgo(61));

    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    assertThat(statusOf(done)).isEqualTo("COMPLETED");
    assertThat(eventsFor(done)).isEmpty();
  }

  @Test
  void executionRegisteredOnThisInstance_isSkippedEvenIfHeartbeatIsOld() {
    Long mine = insertExecution(pipelineId, "RUNNING", minutesAgo(30), minutesAgo(31));
    registry.register(mine, DEFAULT_TEST_TENANT_ID);
    try {
      sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);
    } finally {
      registry.unregister(mine);
    }

    assertThat(statusOf(mine)).isEqualTo("RUNNING");
    assertThat(eventsFor(mine)).isEmpty();
  }

  @Test
  void afterSweep_runningGateOpens() {
    insertExecution(pipelineId, "RUNNING", minutesAgo(30), minutesAgo(31));
    assertThat(executionRepository.hasRunningExecution(DEFAULT_TEST_TENANT_ID)).isTrue();

    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    // 고아 staging 정리 게이트가 풀린다.
    assertThat(executionRepository.hasRunningExecution(DEFAULT_TEST_TENANT_ID)).isFalse();
  }

  @Test
  void sweepingTwice_emitsEventOnce() {
    Long stale = insertExecution(pipelineId, "RUNNING", minutesAgo(30), minutesAgo(31));

    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);
    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    assertThat(eventsFor(stale)).hasSize(1);
  }

  @Test
  void interruptedEvent_doesNotFireFailureChain_butNotifiesOwnerOnce() throws Exception {
    // A 가 실패하면 B 가 도는 「완료 시(FAILURE)」 체인.
    Long downstream = newPipeline("sweep-down-" + System.nanoTime());
    inTenantFixture(
        () ->
            dsl.insertInto(table(name("pipeline_trigger")))
                .set(field(name("pipeline_id"), Long.class), downstream)
                .set(field(name("trigger_type"), String.class), "PIPELINE_CHAIN")
                .set(field(name("name"), String.class), "on-failure")
                .set(field(name("is_enabled"), Boolean.class), true)
                .set(
                    field(name("config"), JSONB.class),
                    JSONB.valueOf(
                        "{\"upstreamPipelineId\":" + pipelineId + ",\"condition\":\"FAILURE\"}"))
                .set(field(name("created_by"), Long.class), userId)
                .execute());
    Long stale = insertExecution(pipelineId, "RUNNING", minutesAgo(30), minutesAgo(31));

    sweeper.sweepTenant(DEFAULT_TEST_TENANT_ID);

    // 실제 알림 리스너가 비동기로 받아 소유자에게 「중단」 알림을 한 번 보낸다.
    ArgumentCaptor<NotificationEvent> notice = ArgumentCaptor.forClass(NotificationEvent.class);
    verify(sseRegistry, timeout(10_000).times(1)).broadcast(eq(userId), notice.capture());
    assertThat(notice.getValue().eventType()).isEqualTo("PIPELINE_FAILED");
    assertThat(notice.getValue().description()).contains("중단");

    // 체인은 발화하지 않는다 — 리스너가 이미 처리를 끝낼 시간을 준 뒤 확인한다.
    Thread.sleep(2_000);
    assertThat(executionsOf(downstream)).isZero();

    // 대조군: 같은 이벤트가 interrupted=false 였다면 체인이 발화한다(위 단언이 공허하지 않다는 증거).
    TenantContext.runScoped(
        DEFAULT_TEST_TENANT_ID,
        () ->
            eventPublisher.publishEvent(
                new PipelineCompletedEvent(pipelineId, stale, "FAILED", userId, false)));
    long deadline = System.currentTimeMillis() + 15_000;
    while (executionsOf(downstream) == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    assertThat(executionsOf(downstream)).isGreaterThanOrEqualTo(1);
  }

  private long executionsOf(Long forPipeline) {
    return inTenantFixture(
        () ->
            (long)
                dsl.fetchCount(PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(forPipeline)));
  }
}
