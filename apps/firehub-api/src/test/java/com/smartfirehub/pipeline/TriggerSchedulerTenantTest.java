package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.TriggerSchedulerService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SCHEDULE 트리거의 테넌트 배선 검증 (P2-b Task 4).
 *
 * <p>두 가지를 본다.
 *
 * <ul>
 *   <li>{@code reloadAllSchedules()} 가 ACTIVE 테넌트를 순회하는가 — 순회하지 않으면 기동 시 테넌트
 *       컨텍스트가 없어 SCHEDULE 트리거가 하나도 재등록되지 않는다.
 *   <li>cron 발화 콜백이 <b>등록 시점의 테넌트</b>를 들고 가는가 — 이 스케줄러 풀에는
 *       {@code TaskDecorator} 가 없어, 감싸지 않으면 발화 스레드에 테넌트가 없다.
 * </ul>
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 테스트가 트랜잭션을 열면 GUC
 * 가 그 트랜잭션에서 공급돼 프로덕션의 배선 누락을 구조적으로 가린다. 픽스처만
 * {@link TenantRlsTestSupport#runInTenantTransaction} 으로 감싸고, 검증 대상 호출은 트랜잭션 밖에
 * 남긴다(선례: {@code dashboard/job/PipelineExecutionTtlJobTest}).
 *
 * <p>{@link TriggerService} 는 목이다. {@code reloadAllSchedules()} 는 공유 테스트 DB 의 <b>모든</b>
 * 테넌트를 훑으므로, 실 서비스라면 다른 테스트가 심어둔 트리거의 missed-fire 를 실제로 발화시켜
 * 파이프라인 실행과 이벤트를 만들어낸다.
 */
class TriggerSchedulerTenantTest extends IntegrationTestBase {

  @Autowired private TriggerSchedulerService schedulerService;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private TriggerService triggerService;

  private static final Map<String, Object> CRON_CONFIG =
      Map.of("cron", "0 0 3 * * *", "timezone", "Asia/Seoul");

  private TransactionTemplate tx;
  private TaskScheduler originalScheduler;
  private TaskScheduler schedulerMock;
  private Map<Long, ScheduledFuture<?>> preexistingSchedules;

  private Long tenantA;
  private Long tenantB;
  private Long userA;
  private Long userB;
  private Long pipelineA;
  private Long pipelineB;
  private Long triggerA;
  private Long triggerB;

  @BeforeEach
  void seedTwoTenants() {
    tx = new TransactionTemplate(transactionManager);

    // tenant·user 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만들 수 있다.
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "sched-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "sched-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "sched_a_");
    userB = TenantRlsTestSupport.insertUser(dsl, "sched_b_");

    // 도메인 픽스처는 각 테넌트 컨텍스트의 트랜잭션 안에서 만든다 — tenant_id DEFAULT 가 GUC 에서 채워진다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () -> {
          pipelineA = insertPipeline(userA, "sched-tenant-a");
          triggerA = insertScheduleTrigger(pipelineA, userA, "Tenant A Schedule");
        });
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantB,
        () -> {
          pipelineB = insertPipeline(userB, "sched-tenant-b");
          triggerB = insertScheduleTrigger(pipelineB, userB, "Tenant B Schedule");
        });

    // 싱글턴 빈의 taskScheduler 를 목으로 바꾼다 — 실제 cron 발화를 기다리지 않고 등록된 Runnable 을 꺼내기 위함.
    originalScheduler = (TaskScheduler) ReflectionTestUtils.getField(schedulerService, "taskScheduler");
    schedulerMock = mock(TaskScheduler.class);
    when(schedulerMock.schedule(any(Runnable.class), any(Trigger.class)))
        .thenAnswer(invocation -> mock(ScheduledFuture.class));
    ReflectionTestUtils.setField(schedulerService, "taskScheduler", schedulerMock);

    // 키만이 아니라 값까지 통째로 스냅샷한다. reloadAllSchedules 는 이미 등록돼 있던 트리거도
    // 다시 registerSchedule 하는데, 그 경로가 진짜 ScheduledFuture 를 cancel 하고 목으로 바꿔치운다 —
    // 키만 되돌리면 남의 트리거가 취소된 채 목을 들고 남아 이후 테스트가 원인 불명으로 실패한다.
    preexistingSchedules = new HashMap<>(scheduledTasks());
  }

  @AfterEach
  void restoreAndCleanup() {
    // 이 테스트가 만든 항목을 걷어내고, 건드린 기존 항목은 원래 future 로 복원한다.
    // 목 스케줄러를 남기면 같은 컨텍스트를 쓰는 이후 테스트가 오염된다.
    scheduledTasks().clear();
    scheduledTasks().putAll(preexistingSchedules);
    ReflectionTestUtils.setField(schedulerService, "taskScheduler", originalScheduler);

    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantA, () -> deletePipeline(pipelineA));
    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantB, () -> deletePipeline(pipelineB));
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteUser(dsl, userB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  /**
   * 기동 재등록이 두 테넌트 모두를 커버하는지 본다.
   *
   * <p>단방향(한 테넌트만)이면 순회가 없어도 통과하므로 양쪽을 함께 단언한다. 호출 직전에 테넌트
   * 컨텍스트를 비우는 것이 핵심이다 — {@code @PostConstruct} 실행 시점에는 승계할 테넌트가 없기
   * 때문이며, {@link IntegrationTestBase} 가 세워둔 기본 테넌트를 남겨두면 순회가 없어도 통과한다.
   */
  @Test
  void reloadAllSchedules_registersSchedulesForEveryActiveTenant() {
    TenantContext.clear();

    schedulerService.reloadAllSchedules();

    assertThat(scheduledTasks()).containsKeys(triggerA, triggerB);
  }

  /**
   * 발화 콜백이 등록 시점의 테넌트를 들고 가는지 본다.
   *
   * <p>등록된 {@code Runnable} 을 꺼내 <b>테넌트 컨텍스트가 비워진 별도 스레드</b>에서 실행한다 —
   * 실제 cron 발화(다른 풀 스레드, 몇 시간 뒤)와 같은 조건이다. 감싸지 않았다면 여기서 null 이 관측된다.
   */
  @Test
  void fireCallback_carriesRegistrationTenantIntoFiringThread() throws Exception {
    Map<Long, Long> observedTenantByTrigger = new ConcurrentHashMap<>();
    doAnswer(
            invocation -> {
              Long firedTriggerId = invocation.getArgument(0);
              Long current = TenantContext.get();
              observedTenantByTrigger.put(firedTriggerId, current == null ? -1L : current);
              return null;
            })
        .when(triggerService)
        .fireTrigger(any(), any());

    TenantContext.runScoped(tenantA, () -> schedulerService.registerSchedule(triggerA, CRON_CONFIG));
    TenantContext.runScoped(tenantB, () -> schedulerService.registerSchedule(triggerB, CRON_CONFIG));

    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(schedulerMock, times(2)).schedule(captor.capture(), any(Trigger.class));
    List<Runnable> fireTasks = captor.getAllValues();

    for (Runnable task : fireTasks) {
      runWithoutTenantContext(task);
    }

    assertThat(observedTenantByTrigger)
        .as("발화 콜백은 등록 시점 테넌트로 실행돼야 한다 (-1 은 컨텍스트 없음)")
        .containsEntry(triggerA, tenantA)
        .containsEntry(triggerB, tenantB);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /** 테넌트 컨텍스트가 없는 새 스레드에서 실행하고, 실행 후에도 컨텍스트가 새지 않는지 확인한다. */
  private void runWithoutTenantContext(Runnable task) throws InterruptedException {
    Long[] leaked = new Long[1];
    Thread thread =
        new Thread(
            () -> {
              task.run();
              leaked[0] = TenantContext.get();
            });
    thread.start();
    thread.join();
    assertThat(leaked[0]).as("발화 스레드에 테넌트가 남으면 다음 작업이 남의 테넌트로 돈다").isNull();
  }

  @SuppressWarnings("unchecked")
  private Map<Long, ScheduledFuture<?>> scheduledTasks() {
    return (Map<Long, ScheduledFuture<?>>)
        ReflectionTestUtils.getField(schedulerService, "scheduledTasks");
  }

  private Long insertPipeline(Long ownerId, String namePrefix) {
    return dsl.insertInto(table(name("pipeline")))
        .set(field(name("name"), String.class), namePrefix + "-" + System.nanoTime())
        .set(field(name("is_active"), Boolean.class), true)
        .set(field(name("created_by"), Long.class), ownerId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertScheduleTrigger(Long pipelineId, Long ownerId, String triggerName) {
    return dsl.insertInto(table(name("pipeline_trigger")))
        .set(field(name("pipeline_id"), Long.class), pipelineId)
        .set(field(name("trigger_type"), String.class), "SCHEDULE")
        .set(field(name("name"), String.class), triggerName)
        .set(field(name("is_enabled"), Boolean.class), true)
        .set(
            field(name("config"), JSONB.class),
            JSONB.valueOf("{\"cron\":\"0 0 3 * * *\",\"timezone\":\"Asia/Seoul\"}"))
        .set(field(name("created_by"), Long.class), ownerId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** 파이프라인을 지우면 트리거·이벤트가 FK CASCADE 로 함께 사라진다. */
  private void deletePipeline(Long pipelineId) {
    if (pipelineId != null) {
      dsl.deleteFrom(table(name("pipeline")))
          .where(field(name("id"), Long.class).eq(pipelineId))
          .execute();
    }
  }
}
