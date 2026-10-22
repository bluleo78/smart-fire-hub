package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.TriggerSchedulerService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.platform.service.PlatformTenantService;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WD-174: 정지를 풀면 그 워크스페이스의 예약(파이프라인 SCHEDULE 트리거·스마트 작업 크론)이 곧바로 다시 걸린다 — 정지 중 api 재기동이 있었어도.
 *
 * <p>예전에는 기동 재등록이 ACTIVE 테넌트만 돌고 해제({@code PlatformTenantService.activate})는 아무것도 다시 걸지 않아, 정지 중
 * 재배포 뒤 정지를 풀면 다음 재기동까지 예약 실행이 돌지 않았다.
 *
 * <p>해제는 운영 경로 그대로 부른다(테넌트 컨텍스트 없음, 테스트 트랜잭션 없음) — 커밋 뒤 재등록이 대상 테넌트 GUC 아래에서 실제로 행을 읽는지가 이 테스트의
 * 요점이다. 스케줄러 메서드를 직접 부르면 그 배선을 건너뛴다.
 *
 * <p>{@link TriggerService}·{@link ProactiveJobService} 는 목이다 — 재기동 흉내({@code reloadAllSchedules})는
 * 공유 테스트 DB 의 모든 테넌트를 훑어, 실 서비스면 다른 테스트가 심어 둔 트리거의 놓친 발화를 실제로 실행한다(선례 TriggerSchedulerTenantTest).
 * 두 스케줄러의 풀은 목으로 바꾸고 맵을 통째로 스냅샷·복원한다.
 */
class TenantResumeScheduleReloadTest extends IntegrationTestBase {

  private static final String CRON = "0 0 3 * * *";
  private static final String TZ = "Asia/Seoul";

  @Autowired private DSLContext dsl;
  @Autowired private TriggerSchedulerService triggerSchedulerService;
  @Autowired private ProactiveJobSchedulerService proactiveSchedulerService;
  @Autowired private PlatformTenantService platformTenantService;

  @MockitoBean private TriggerService triggerService;
  @MockitoBean private ProactiveJobService proactiveJobService;

  private TaskScheduler originalTriggerScheduler;
  private TaskScheduler originalProactiveScheduler;
  private Map<Long, ScheduledFuture<?>> preexistingTriggerSchedules;
  private Map<Long, ScheduledFuture<?>> preexistingProactiveSchedules;

  private long suspendedTenant;
  private long controlTenant;
  private Long operator;
  private Long owner;
  private Long pipelineT;
  private Long pipelineU;
  private Long triggerT;
  private Long triggerU;
  private Long jobT;
  private Long jobU;

  @BeforeEach
  void setUp() {
    suspendedTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd174-resume");
    controlTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd174-ctrl");
    operator = TenantRlsTestSupport.insertUser(dsl, "wd174r_op_");
    owner = TenantRlsTestSupport.insertUser(dsl, "wd174r_o_");

    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        suspendedTenant,
        () -> {
          pipelineT = insertPipeline("wd174-resume");
          // 정지 전에 지나간 다음 실행 시각 — 해제가 놓친 발화 따라잡기를 하면 이 값 때문에 즉시 발화한다(하면 안 된다)
          triggerT =
              insertScheduleTrigger(pipelineT, "{\"nextFireTime\":\"2020-01-01T00:00:00Z\"}");
          jobT = insertJob("wd174-resume");
        });
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        controlTenant,
        () -> {
          pipelineU = insertPipeline("wd174-ctrl");
          triggerU = insertScheduleTrigger(pipelineU, "{}");
          jobU = insertJob("wd174-ctrl");
        });

    originalTriggerScheduler =
        (TaskScheduler) ReflectionTestUtils.getField(triggerSchedulerService, "taskScheduler");
    originalProactiveScheduler =
        (TaskScheduler) ReflectionTestUtils.getField(proactiveSchedulerService, "taskScheduler");
    ReflectionTestUtils.setField(triggerSchedulerService, "taskScheduler", mockScheduler());
    ReflectionTestUtils.setField(proactiveSchedulerService, "taskScheduler", mockScheduler());
    preexistingTriggerSchedules = new HashMap<>(triggerTasks());
    preexistingProactiveSchedules = new HashMap<>(proactiveTasks());
  }

  @AfterEach
  void tearDown() {
    triggerTasks().clear();
    triggerTasks().putAll(preexistingTriggerSchedules);
    proactiveTasks().clear();
    proactiveTasks().putAll(preexistingProactiveSchedules);
    ReflectionTestUtils.setField(
        triggerSchedulerService, "taskScheduler", originalTriggerScheduler);
    ReflectionTestUtils.setField(
        proactiveSchedulerService, "taskScheduler", originalProactiveScheduler);

    TenantRlsTestSupport.cleanupAll(
        () ->
            TenantRlsTestSupport.runInTenantTransaction(
                fixtureTransactionTemplate,
                null,
                () ->
                    dsl.execute(
                        "delete from audit_log where resource = 'tenant' and resource_id in (?, ?)",
                        String.valueOf(suspendedTenant),
                        String.valueOf(controlTenant))),
        () -> deleteFixtures(suspendedTenant, pipelineT, jobT),
        () -> deleteFixtures(controlTenant, pipelineU, jobU),
        () -> TenantRlsTestSupport.deleteUser(dsl, owner),
        () -> TenantRlsTestSupport.deleteUser(dsl, operator),
        () -> TenantRlsTestSupport.deleteTenants(dsl, suspendedTenant, controlTenant));
    TenantContext.clear();
  }

  /**
   * 정지 중 재기동 → 정지 워크스페이스의 예약은 걸리지 않는다 → 해제하면 그 워크스페이스의 예약이 걸린다. 다음 실행 시각도 대상 테넌트 GUC 아래에서 실제로
   * 기록되고(0행 조용한 실패가 아님), 정지 중 지나간 발화를 따라잡지 않는다(MISSED 이벤트·발화 없음).
   */
  @Test
  void restartWhileSuspended_thenActivate_registersThatTenantsSchedules() {
    suspend(suspendedTenant);

    // 재기동 흉내: 메모리의 예약을 전부 비운 뒤 기동 재등록(컨텍스트 없음 — @PostConstruct 조건)
    triggerTasks().clear();
    proactiveTasks().clear();
    TenantContext.clear();
    triggerSchedulerService.reloadAllSchedules();
    proactiveSchedulerService.reloadAllSchedules();

    // 대조군(ACTIVE)은 걸리고 정지 워크스페이스는 안 걸린다 — 대조군이 있어야 "아무것도 안 걸림"으로 통과하지 않는다
    assertThat(triggerTasks()).containsKey(triggerU).doesNotContainKey(triggerT);
    assertThat(proactiveTasks()).containsKey(jobU).doesNotContainKey(jobT);

    TenantContext.clear();
    platformTenantService.activate(suspendedTenant, operator);

    assertThat(triggerSchedulerService.isScheduled(triggerT)).as("해제 직후 예약 트리거가 걸려 있다").isTrue();
    assertThat(proactiveSchedulerService.isScheduled(jobT)).as("해제 직후 스마트 작업이 걸려 있다").isTrue();
    // 다음 실행 시각이 "지금부터"로 다시 기록됐다 — 대상 테넌트 GUC 없이 돌았다면 0행 갱신으로 옛 값이 남는다
    assertThat(nextFireTimeOf(triggerT)).isNotNull().isNotEqualTo("2020-01-01T00:00:00Z");
    assertThat(nextExecuteAtOf(jobT)).isNotNull();
    // 정지 중 지나간 발화는 따라잡지 않는다
    verify(triggerService, never()).fireTrigger(eq(triggerT), any());
    assertThat(missedEventCount()).isZero();
  }

  /** 이미 걸려 있는 상태(정지 중 재기동 없음)에서 해제해도 중복되지 않는다 — 옛 스케줄을 취소하고 바꿔 끼운다. */
  @Test
  void activate_whenStillRegistered_replacesInsteadOfDuplicating() {
    TenantContext.runScoped(
        suspendedTenant,
        () -> {
          triggerSchedulerService.registerSchedule(triggerT, Map.of("cron", CRON, "timezone", TZ));
          proactiveSchedulerService.registerSchedule(jobT, CRON, TZ);
        });
    ScheduledFuture<?> oldTrigger = triggerTasks().get(triggerT);
    ScheduledFuture<?> oldJob = proactiveTasks().get(jobT);
    assertThat((Object) oldTrigger).isNotNull();
    assertThat((Object) oldJob).isNotNull();

    suspend(suspendedTenant);
    TenantContext.clear();
    platformTenantService.activate(suspendedTenant, operator);

    assertThat((Object) triggerTasks().get(triggerT)).isNotNull().isNotSameAs(oldTrigger);
    assertThat((Object) proactiveTasks().get(jobT)).isNotNull().isNotSameAs(oldJob);
    verify(oldTrigger).cancel(false);
    verify(oldJob).cancel(false);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  private void suspend(long tenantId) {
    TenantContext.clear();
    platformTenantService.suspend(tenantId, operator);
  }

  private static TaskScheduler mockScheduler() {
    TaskScheduler scheduler = mock(TaskScheduler.class);
    when(scheduler.schedule(any(Runnable.class), any(Trigger.class)))
        .thenAnswer(invocation -> mock(ScheduledFuture.class));
    return scheduler;
  }

  @SuppressWarnings("unchecked")
  private Map<Long, ScheduledFuture<?>> triggerTasks() {
    return (Map<Long, ScheduledFuture<?>>)
        ReflectionTestUtils.getField(triggerSchedulerService, "scheduledTasks");
  }

  @SuppressWarnings("unchecked")
  private Map<Long, ScheduledFuture<?>> proactiveTasks() {
    return (Map<Long, ScheduledFuture<?>>)
        ReflectionTestUtils.getField(proactiveSchedulerService, "scheduledTasks");
  }

  private Long insertPipeline(String prefix) {
    return dsl.insertInto(table(name("pipeline")))
        .set(field(name("name"), String.class), prefix + "-" + System.nanoTime())
        .set(field(name("is_active"), Boolean.class), true)
        .set(field(name("created_by"), Long.class), owner)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertScheduleTrigger(Long pipelineId, String triggerState) {
    return dsl.insertInto(table(name("pipeline_trigger")))
        .set(field(name("pipeline_id"), Long.class), pipelineId)
        .set(field(name("trigger_type"), String.class), "SCHEDULE")
        .set(field(name("name"), String.class), "wd174 재등록")
        .set(field(name("is_enabled"), Boolean.class), true)
        .set(
            field(name("config"), JSONB.class),
            JSONB.valueOf("{\"cron\":\"" + CRON + "\",\"timezone\":\"" + TZ + "\"}"))
        .set(field(name("trigger_state"), JSONB.class), JSONB.valueOf(triggerState))
        .set(field(name("created_by"), Long.class), owner)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertJob(String prefix) {
    return dsl.insertInto(table(name("proactive_job")))
        .set(field(name("user_id"), Long.class), owner)
        .set(field(name("name"), String.class), prefix + "-job-" + System.nanoTime())
        .set(field(name("prompt"), String.class), "test")
        .set(field(name("config"), JSONB.class), JSONB.valueOf("{}"))
        .set(field(name("enabled"), Boolean.class), true)
        .set(field(name("cron_expression"), String.class), CRON)
        .set(field(name("timezone"), String.class), TZ)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private void deleteFixtures(long tenantId, Long pipelineId, Long jobId) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        tenantId,
        () -> {
          dsl.execute("delete from proactive_job where id = ?", jobId);
          // 트리거·이벤트는 파이프라인 FK CASCADE 로 함께 지워진다
          dsl.execute("delete from pipeline where id = ?", pipelineId);
        });
  }

  private String nextFireTimeOf(Long triggerId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        suspendedTenant,
        () ->
            dsl.fetchOne(
                    "select trigger_state->>'nextFireTime' from pipeline_trigger where id = ?",
                    triggerId)
                .get(0, String.class));
  }

  private Object nextExecuteAtOf(Long jobId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        suspendedTenant,
        () -> dsl.fetchOne("select next_execute_at from proactive_job where id = ?", jobId).get(0));
  }

  private long missedEventCount() {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        suspendedTenant,
        () ->
            dsl.fetchOne(
                    "select count(*) from trigger_event where trigger_id = ? and event_type = 'MISSED'",
                    triggerT)
                .get(0, Long.class));
  }
}
