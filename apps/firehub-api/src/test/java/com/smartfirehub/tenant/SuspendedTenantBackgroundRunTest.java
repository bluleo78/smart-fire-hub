package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.service.TriggerCreatorSkipNotifier;
import com.smartfirehub.pipeline.service.TriggerSchedulerService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.platform.service.PlatformTenantService;
import com.smartfirehub.proactive.service.ProactiveAiClient;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-174: 운영자가 워크스페이스(테넌트)를 정지하면 그 워크스페이스의 백그라운드 실행이 시작되지 않는다 — 예약 트리거 발화 콜백, 스마트 작업 예약 콜백, 외부 API·
 * 웹훅 트리거. 정지를 풀면 다시 정상으로 돈다.
 *
 * <p><b>공허한 통과를 피하는 단언</b>: WD-166·169 이후에는 게이트가 없어도 「실행 COMPLETED」는 이미 안 나온다(만든 사람·주인 자격 판정이 테넌트
 * ACTIVE 도 보기 때문). 그래서 "실행이 없다"만 보면 게이트를 지워도 통과한다. 대신 게이트가 없을 때만 생기는 흔적을 0 으로 단언한다 — 트리거 이벤트 행(어떤
 * 유형이든), 만든 사람 건너뜀 알림 호출, 스마트 작업 실행 제출·실행 행, 외부 응답의 정확한 사유 문구(게이트가 없으면 일반 「실행할 수 없어」 문구가 나간다).
 *
 * <p>정지 대상은 스크래치 테넌트다 — 공유 기본 테넌트(1)를 정지하면 다른 테스트가 오염된다. 클래스 레벨 {@code @Transactional} 이 없다: 정지 해제의
 * 커밋 뒤 재등록과 발화 콜백의 GUC 배선이 실제 경계에서 일어나야 한다.
 */
@AutoConfigureMockMvc
class SuspendedTenantBackgroundRunTest extends IntegrationTestBase {

  private static final String REASON = "워크스페이스가 정지되어 실행하지 않았습니다";
  private static final Map<String, Object> CRON_CONFIG =
      Map.of("cron", "0 0 3 * * *", "timezone", "Asia/Seoul");

  @Autowired private DSLContext dsl;
  @Autowired private MockMvc mockMvc;
  @Autowired private TriggerService triggerService;
  @Autowired private TriggerSchedulerService triggerSchedulerService;
  @Autowired private ProactiveJobSchedulerService proactiveSchedulerService;
  @Autowired private PlatformTenantService platformTenantService;

  // 게이트가 없으면 정지 테넌트 발화가 「만든 사람 정지」로 판정돼 이 알림이 불린다(WD-169) — 호출 여부만 본다
  @MockitoSpyBean private TriggerCreatorSkipNotifier creatorSkipNotifier;
  // 예약 콜백이 실행을 제출했는지 본다(제출되면 비동기 실행이 실행 행을 만든다)
  @MockitoSpyBean private ProactiveJobService proactiveJobService;
  // 해제 뒤 양성 대조 실행이 실제 AI 를 부르지 않게
  @MockitoBean private ProactiveAiClient proactiveAiClient;

  private long tenantId;
  private Long operator;
  private Long creator;
  private Long roleId;
  private Long pipelineId;
  private Long scheduleTriggerId;
  private Long jobId;
  private String apiToken;
  private String webhookId;

  private TaskScheduler originalTriggerScheduler;
  private TaskScheduler originalProactiveScheduler;
  private TaskScheduler triggerSchedulerMock;
  private TaskScheduler proactiveSchedulerMock;
  private Map<Long, ScheduledFuture<?>> preexistingTriggerSchedules;
  private Map<Long, ScheduledFuture<?>> preexistingProactiveSchedules;

  @BeforeEach
  void setUp() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "wd174");
    operator = TenantRlsTestSupport.insertUser(dsl, "wd174_op_");
    creator = TenantRlsTestSupport.insertUser(dsl, "wd174_c_");
    TenantRlsTestSupport.insertActiveMembership(dsl, creator, tenantId);

    // 만든 사람(=작업 주인)에게 이 워크스페이스의 실행 권한을 준다 — 해제 뒤 양성 대조가 자격 판정에 막히지 않게
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        tenantId,
        () -> {
          roleId =
              dsl.insertInto(table(name("role")))
                  .set(field(name("name"), String.class), "wd174_r_" + System.nanoTime())
                  .set(field(name("is_system"), Boolean.class), false)
                  .returning(field(name("id"), Long.class))
                  .fetchOne()
                  .get(field(name("id"), Long.class));
          for (String code :
              List.of(
                  "pipeline:read",
                  "pipeline:execute",
                  "trigger:read",
                  "trigger:write",
                  "proactive:write")) {
            dsl.execute(
                "insert into role_permission(role_id, permission_id)"
                    + " select ?, id from permission where code = ?",
                roleId,
                code);
          }
          dsl.execute("insert into user_role(user_id, role_id) values (?, ?)", creator, roleId);
          pipelineId =
              dsl.insertInto(table(name("pipeline")))
                  .set(field(name("name"), String.class), "wd174-" + System.nanoTime())
                  .set(field(name("is_active"), Boolean.class), true)
                  .set(field(name("created_by"), Long.class), creator)
                  .returning(field(name("id"), Long.class))
                  .fetchOne()
                  .get(field(name("id"), Long.class));
          scheduleTriggerId =
              dsl.insertInto(table(name("pipeline_trigger")))
                  .set(field(name("pipeline_id"), Long.class), pipelineId)
                  .set(field(name("trigger_type"), String.class), "SCHEDULE")
                  .set(field(name("name"), String.class), "wd174 예약")
                  .set(field(name("is_enabled"), Boolean.class), true)
                  .set(
                      field(name("config"), JSONB.class),
                      JSONB.valueOf("{\"cron\":\"0 0 3 * * *\",\"timezone\":\"Asia/Seoul\"}"))
                  .set(field(name("created_by"), Long.class), creator)
                  .returning(field(name("id"), Long.class))
                  .fetchOne()
                  .get(field(name("id"), Long.class));
          jobId =
              dsl.insertInto(table(name("proactive_job")))
                  .set(field(name("user_id"), Long.class), creator)
                  .set(field(name("name"), String.class), "wd174-job-" + System.nanoTime())
                  .set(field(name("prompt"), String.class), "test")
                  .set(field(name("config"), JSONB.class), JSONB.valueOf("{}"))
                  .set(field(name("enabled"), Boolean.class), true)
                  .set(field(name("cron_expression"), String.class), "0 0 3 * * *")
                  .set(field(name("timezone"), String.class), "Asia/Seoul")
                  .returning(field(name("id"), Long.class))
                  .fetchOne()
                  .get(field(name("id"), Long.class));
        });

    // 외부 트리거는 서비스로 만든다 — 토큰 해시·웹훅 id 형식이 생산 경로와 같아야 해석 함수가 찾는다
    TenantContext.runScoped(
        tenantId,
        () -> {
          TriggerResponse api =
              triggerService.createTrigger(
                  pipelineId,
                  new CreateTriggerRequest("wd174 API", TriggerType.API, null, Map.of()),
                  creator);
          apiToken = (String) api.config().get("rawToken");
          TriggerResponse webhook =
              triggerService.createTrigger(
                  pipelineId,
                  new CreateTriggerRequest("wd174 웹훅", TriggerType.WEBHOOK, null, Map.of()),
                  creator);
          webhookId = (String) webhook.config().get("webhookId");
        });

    // 두 스케줄러의 실제 풀을 목으로 바꾼다 — cron 을 기다리지 않고 등록된 발화 콜백을 꺼내 직접 돌린다(선례 TriggerSchedulerTenantTest)
    originalTriggerScheduler =
        (TaskScheduler) ReflectionTestUtils.getField(triggerSchedulerService, "taskScheduler");
    originalProactiveScheduler =
        (TaskScheduler) ReflectionTestUtils.getField(proactiveSchedulerService, "taskScheduler");
    triggerSchedulerMock = mockScheduler();
    proactiveSchedulerMock = mockScheduler();
    ReflectionTestUtils.setField(triggerSchedulerService, "taskScheduler", triggerSchedulerMock);
    ReflectionTestUtils.setField(
        proactiveSchedulerService, "taskScheduler", proactiveSchedulerMock);
    preexistingTriggerSchedules = new HashMap<>(scheduledTasks(triggerSchedulerService));
    preexistingProactiveSchedules = new HashMap<>(scheduledTasks(proactiveSchedulerService));

    // 정지 전(ACTIVE)에 예약을 건다 — 운영에서 정지 시점에 이미 걸려 있던 cron 과 같은 상태
    TenantContext.runScoped(
        tenantId,
        () -> {
          triggerSchedulerService.registerSchedule(scheduleTriggerId, CRON_CONFIG);
          proactiveSchedulerService.registerSchedule(jobId, "0 0 3 * * *", "Asia/Seoul");
        });
    clearInvocations(creatorSkipNotifier, proactiveJobService);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    // 양성 대조의 비동기 실행(파이프라인·스마트 작업)이 끝날 틈을 준 뒤 지운다
    Thread.sleep(1_500);
    scheduledTasks(triggerSchedulerService).clear();
    scheduledTasks(triggerSchedulerService).putAll(preexistingTriggerSchedules);
    scheduledTasks(proactiveSchedulerService).clear();
    scheduledTasks(proactiveSchedulerService).putAll(preexistingProactiveSchedules);
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
                        "delete from audit_log where resource = 'tenant' and resource_id = ?",
                        String.valueOf(tenantId))),
        () ->
            TenantRlsTestSupport.runInTenantTransaction(
                fixtureTransactionTemplate,
                tenantId,
                () -> {
                  dsl.execute("delete from proactive_message where tenant_id = ?", tenantId);
                  dsl.execute("delete from proactive_job_execution where job_id = ?", jobId);
                  dsl.execute("delete from proactive_job where id = ?", jobId);
                  dsl.execute("delete from pipeline where id = ?", pipelineId);
                  dsl.execute("delete from user_role where role_id = ?", roleId);
                  dsl.execute("delete from role_permission where role_id = ?", roleId);
                  dsl.execute("delete from role where id = ?", roleId);
                }),
        // 트리거 생성·실행이 남긴 이 테넌트의 감사 행 — 남기면 user·tenant 삭제가 FK 로 실패한다
        () -> TenantRlsTestSupport.deleteOwnAuditLogRows(dsl, fixtureTransactionTemplate, tenantId),
        () -> TenantRlsTestSupport.deleteMembership(dsl, creator),
        () -> TenantRlsTestSupport.deleteUser(dsl, creator),
        () -> TenantRlsTestSupport.deleteUser(dsl, operator),
        () -> TenantRlsTestSupport.deleteTenants(dsl, tenantId));
    TenantContext.clear();
  }

  /** 예약 트리거 발화 콜백: 정지 워크스페이스면 실행·트리거 이벤트(SKIPPED 포함)·만든 사람 건너뜀 알림이 모두 없다. */
  @Test
  void suspended_scheduleTriggerCallback_startsNothingAndLeavesNoTrace() throws Exception {
    suspend();

    runWithoutTenantContext(lastRegisteredCallback(triggerSchedulerMock));

    assertThat(triggerEventCount()).as("정지 중 발화는 이벤트를 쌓지 않는다(SKIPPED 포함)").isZero();
    assertThat(pipelineExecutionCount()).isZero();
    verify(creatorSkipNotifier, never()).notifyIfFirstSkip(any(), anyLong(), any(), any());
  }

  /** 스마트 작업 예약 콜백: 정지 워크스페이스면 실행을 제출하지 않는다 — 실행 행·AI 호출 없음. */
  @Test
  void suspended_proactiveScheduleCallback_submitsNothing() throws Exception {
    suspend();

    runWithoutTenantContext(lastRegisteredCallback(proactiveSchedulerMock));
    Thread.sleep(500); // 제출됐다면 비동기 실행이 실행 행을 만들 틈

    verify(proactiveJobService, never()).executeJob(anyLong(), anyLong());
    assertThat(proactiveExecutionCount()).as("정지 중 예약 실행은 실행 이력을 쌓지 않는다").isZero();
    verify(proactiveAiClient, never()).execute(any(), any(), any(), any(), any(), any(), any(), any());
  }

  /** 외부 API 트리거: 정지 워크스페이스면 409 + 워크스페이스 정지 사유, 실행·이벤트 없음. */
  @Test
  void suspended_externalApiTrigger_returns409WithWorkspaceReason() throws Exception {
    suspend();

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + apiToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value(REASON));

    assertThat(triggerEventCount()).isZero();
    assertThat(pipelineExecutionCount()).isZero();
    verify(creatorSkipNotifier, never()).notifyIfFirstSkip(any(), anyLong(), any(), any());
  }

  /** 웹훅 트리거: 같은 규칙(같은 해석 함수·같은 발화 관문). */
  @Test
  void suspended_webhookTrigger_returns409WithWorkspaceReason() throws Exception {
    suspend();

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value(REASON));

    assertThat(triggerEventCount()).isZero();
    assertThat(pipelineExecutionCount()).isZero();
  }

  /**
   * 해제 뒤에는 다시 정상으로 돈다 — 외부 API 200 triggered, 해제가 다시 건 예약 트리거 콜백이 FIRED, 스마트 작업 예약 콜백이 실행을 제출한다. 정지
   * 중 호출은 흔적이 없고, 해제 뒤 호출만 남는다.
   */
  @Test
  void activated_afterSuspension_runsNormallyAgain() throws Exception {
    suspend();
    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + apiToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict());

    TenantContext.clear();
    platformTenantService.activate(tenantId, operator);

    // 해제가 커밋 뒤 예약을 다시 걸었다(이미 걸려 있었으므로 바꿔 끼움) — 그 새 콜백을 돌린다
    assertThat(triggerSchedulerService.isScheduled(scheduleTriggerId)).isTrue();
    assertThat(proactiveSchedulerService.isScheduled(jobId)).isTrue();
    runWithoutTenantContext(lastRegisteredCallback(triggerSchedulerMock));
    runWithoutTenantContext(lastRegisteredCallback(proactiveSchedulerMock));

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + apiToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"))
        .andExpect(jsonPath("$.executionId").isNumber());

    assertThat(firedEventCount()).as("예약 콜백 1 + 외부 API 1").isEqualTo(2);
    verify(proactiveJobService, times(1)).executeJob(eq(jobId), eq(creator));
    verify(creatorSkipNotifier, never()).notifyIfFirstSkip(any(), anyLong(), any(), any());
  }

  /** 회귀: ACTIVE 워크스페이스는 영향 없음 — 예약 콜백이 그대로 FIRED, 스마트 작업 콜백이 실행을 제출한다. */
  @Test
  void activeTenant_unaffected() throws Exception {
    runWithoutTenantContext(lastRegisteredCallback(triggerSchedulerMock));
    runWithoutTenantContext(lastRegisteredCallback(proactiveSchedulerMock));

    assertThat(firedEventCount()).isEqualTo(1);
    verify(proactiveJobService, times(1)).executeJob(eq(jobId), eq(creator));
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /** 운영 경로 그대로 정지한다(운영자 요청 스레드에는 테넌트 컨텍스트가 없다). */
  private void suspend() {
    TenantContext.clear();
    platformTenantService.suspend(tenantId, operator);
  }

  private static TaskScheduler mockScheduler() {
    TaskScheduler scheduler = mock(TaskScheduler.class);
    when(scheduler.schedule(any(Runnable.class), any(Trigger.class)))
        .thenAnswer(invocation -> mock(ScheduledFuture.class));
    return scheduler;
  }

  /** 목 스케줄러에 마지막으로 등록된 발화 콜백. */
  private static Runnable lastRegisteredCallback(TaskScheduler scheduler) {
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, org.mockito.Mockito.atLeastOnce())
        .schedule(captor.capture(), any(Trigger.class));
    List<Runnable> all = captor.getAllValues();
    return all.get(all.size() - 1);
  }

  /** 실제 cron 발화와 같은 조건 — 테넌트 컨텍스트가 없는 다른 스레드에서 돌린다. */
  private static void runWithoutTenantContext(Runnable task) throws InterruptedException {
    Thread thread = new Thread(task);
    thread.start();
    thread.join();
  }

  @SuppressWarnings("unchecked")
  private static Map<Long, ScheduledFuture<?>> scheduledTasks(Object scheduler) {
    return (Map<Long, ScheduledFuture<?>>)
        ReflectionTestUtils.getField(scheduler, "scheduledTasks");
  }

  private long countInTenant(String sql, Object arg) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate, tenantId, () -> dsl.fetchOne(sql, arg).get(0, Long.class));
  }

  private long triggerEventCount() {
    return countInTenant("select count(*) from trigger_event where pipeline_id = ?", pipelineId);
  }

  private long firedEventCount() {
    return countInTenant(
        "select count(*) from trigger_event where pipeline_id = ? and event_type = 'FIRED'",
        pipelineId);
  }

  private long pipelineExecutionCount() {
    return countInTenant(
        "select count(*) from pipeline_execution where pipeline_id = ?", pipelineId);
  }

  private long proactiveExecutionCount() {
    return countInTenant("select count(*) from proactive_job_execution where job_id = ?", jobId);
  }
}
