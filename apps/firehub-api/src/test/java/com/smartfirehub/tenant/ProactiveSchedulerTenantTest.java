package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.service.ProactiveJobSchedulerService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 프로액티브 잡 스케줄러의 테넌트 배선 검증 (P2-e Task 4, 사전 판정 R5).
 *
 * <p>두 가지를 본다 — 선례 {@code pipeline/TriggerSchedulerTenantTest} 와 같은 이유로 <b>두 개</b>다.
 *
 * <ul>
 *   <li><b>등록 커버리지</b>: {@code reloadAllSchedules()} 가 ACTIVE 테넌트를 순회하는가. 순회하지
 *       않으면 V104 로 {@code proactive_job} 에 RLS 가 걸리는 순간 부팅 시 조회가 0행이 되어 <b>크론이
 *       하나도 등록되지 않은 채 부팅이 성공</b>한다 — 예외도 에러 로그도 없다. 이 밴드에서 가장 조용한
 *       결함이며, 기존 프로액티브 테스트가 전부 {@code doesNotThrowAnyException} 이라 이 상태를
 *       통과시킨다.
 *   <li><b>발화 콜백 테넌트 승계</b>: 등록된 {@code Runnable} 이 등록 시점 테넌트를 들고 가는가. 이
 *       스케줄러 풀에는 {@code TaskDecorator} 가 없어, 감싸지 않으면 발화 스레드에 테넌트가 없다.
 * </ul>
 *
 * <p><b>잡 소유자에게 ACTIVE 멤버십을 일부러 2개 준다.</b> P2-e 이전 구현은 발화 시점에 소유자의
 * 멤버십에서 테넌트를 <i>추론</i>했는데, 멤버십이 1개인 흔한 경우에는 그 추론이 우연히 옳은 답을 내
 * 테스트가 공허하게 통과한다. 멤버십이 모호하면 옛 구현은 실행 자체를 건너뛰므로(관측 테넌트 null)
 * "행의 {@code tenant_id} 를 쓴다"는 새 계약만 통과할 수 있다. 새 계약은 옛 것보다 <b>강하다</b> —
 * 멤버십이 몇 개든 잡 행이 자기 테넌트를 들고 있으므로 실행을 건너뛸 이유가 없다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 테스트가 트랜잭션을 열면 GUC
 * 가 거기서 공급돼 프로덕션의 배선 누락을 영구히 가린다. 픽스처·정리만 {@code inTenantFixture} 로
 * 감싸고, 검증 대상 호출({@code reloadAllSchedules}, 발화 콜백)은 경계 <b>밖</b>에 둔다.
 *
 * <p>{@link ProactiveJobService} 는 목이다. {@code reloadAllSchedules()} 는 공유 테스트 DB 의 모든
 * 잡을 훑으므로, 실제 실행이면 AI 호출과 비동기 잡이 줄줄이 일어난다.
 */
class ProactiveSchedulerTenantTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private ProactiveJobSchedulerService schedulerService;

  @MockitoBean private ProactiveJobService proactiveJobService;

  private static final String CRON = "0 0 3 * * *";
  private static final String TZ = "Asia/Seoul";

  private TaskScheduler originalScheduler;
  private TaskScheduler schedulerMock;
  private Map<Long, ScheduledFuture<?>> preexistingSchedules;

  private long tenantA;
  private long tenantB;
  private long extraTenantForOwnerA;
  private Long userA;
  private Long userB;
  private Long jobA;
  private Long jobB;

  @BeforeEach
  void seedTwoTenants() {
    // tenant·user·membership 은 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만든다.
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "pj-sched-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "pj-sched-b");
    extraTenantForOwnerA = TenantRlsTestSupport.createActiveTenant(dsl, "pj-sched-a2");
    userA = TenantRlsTestSupport.insertUser(dsl, "pjsched_a_");
    userB = TenantRlsTestSupport.insertUser(dsl, "pjsched_b_");
    // 소유자 A 는 멤버십이 2개 — 멤버십 추론으로는 테넌트를 확정할 수 없는 상태를 만든다(위 클래스 주석).
    TenantRlsTestSupport.insertActiveMembership(dsl, userA, tenantA);
    TenantRlsTestSupport.insertActiveMembership(dsl, userA, extraTenantForOwnerA);
    TenantRlsTestSupport.insertActiveMembership(dsl, userB, tenantB);

    // 잡 픽스처는 각 테넌트 컨텍스트의 트랜잭션 안에서 — tenant_id DEFAULT 가 GUC 에서 채워진다.
    jobA = inTenantFixture(tenantA, () -> insertScheduleJob(userA, "sched-a"));
    jobB = inTenantFixture(tenantB, () -> insertScheduleJob(userB, "sched-b"));

    // 싱글턴 빈의 taskScheduler 를 목으로 바꾼다 — 실제 cron 발화를 기다리지 않고 Runnable 을 꺼내기 위함.
    originalScheduler =
        (TaskScheduler) ReflectionTestUtils.getField(schedulerService, "taskScheduler");
    schedulerMock = mock(TaskScheduler.class);
    when(schedulerMock.schedule(any(Runnable.class), any(Trigger.class)))
        .thenAnswer(invocation -> mock(ScheduledFuture.class));
    ReflectionTestUtils.setField(schedulerService, "taskScheduler", schedulerMock);

    // 키만이 아니라 값까지 통째로 스냅샷한다(선례 TriggerSchedulerTenantTest). reloadAllSchedules 는
    // 공유 테스트 DB 에 이미 등록돼 있던 잡도 다시 registerSchedule 하는데, 그 경로가 진짜
    // ScheduledFuture 를 cancel 하고 목으로 바꿔친다 — 키만 되돌리면 남의 스케줄이 목을 들고 남는다.
    //
    // 한계를 분명히 해 둔다: 복원해도 되돌아오는 것은 원래 ScheduledFuture **객체**일 뿐,
    // 이미 실행된 cancel 자체는 취소되지 않는다. 즉 이 스냅샷은 맵의 형상을 되돌릴 뿐 스케줄을
    // 되살리지 못한다. 실질 영향이 없는 이유는 잡 cron 이 0 0 3 * * * 라 테스트 실행 중에는
    // 어차피 발화하지 않기 때문이다 — 이 전제가 깨지면 이 정리는 불충분해진다.
    preexistingSchedules = new HashMap<>(scheduledTasks());
  }

  @AfterEach
  void restoreAndCleanup() {
    scheduledTasks().clear();
    scheduledTasks().putAll(preexistingSchedules);
    ReflectionTestUtils.setField(schedulerService, "taskScheduler", originalScheduler);

    inTenantFixture(tenantA, () -> deleteJob(jobA));
    inTenantFixture(tenantB, () -> deleteJob(jobB));
    TenantRlsTestSupport.deleteMembership(dsl, userA);
    TenantRlsTestSupport.deleteMembership(dsl, userB);
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteUser(dsl, userB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB, extraTenantForOwnerA);
    TenantContext.clear();
  }

  /**
   * (1a) 기동 재등록이 두 테넌트를 모두 커버하는지 본다.
   *
   * <p>한쪽만 단언하면 테넌트 순회가 없어도 통과하므로 양쪽을 함께 단언한다. 호출 직전에 컨텍스트를
   * 비우는 것이 핵심이다 — {@code @PostConstruct} 시점에는 승계할 테넌트가 없고,
   * {@link IntegrationTestBase} 가 세워둔 기본 테넌트를 남겨두면 순회가 없어도 통과한다.
   *
   * <p><b>등록 여부(키 존재)만 보지 않고 캡처된 테넌트까지 단언한다.</b> 순회 안에서 조회가 남의
   * 테넌트 행까지 돌려주면 잡마다 <b>마지막으로 순회된 테넌트</b>가 캡처돼, 등록은 다 됐는데 전부 남의
   * 테넌트로 발화하는 상태가 된다 — 키만 보면 그 상태도 통과한다.
   *
   * <p>존재 단언 부분은 V104(정책) 이전에는 판별력이 없다(정책이 없으면 조회가 컨텍스트와 무관하다).
   * 그래도 지금 써 두는 이유는 Task 6 이 정책을 켜는 순간 곧바로 가드가 되기 때문이다.
   */
  @Test
  @DisplayName("부팅 재등록은 ACTIVE 테넌트를 순회해 각 잡을 자기 테넌트로 등록한다")
  void reloadAllSchedules_registersJobsForEveryActiveTenant() throws Exception {
    assertNoAmbientTransaction();
    Map<Long, Long> observedTenantByJob = observeExecuteJobTenant();
    TenantContext.clear();

    schedulerService.reloadAllSchedules();

    assertThat(scheduledTasks())
        .as("한 테넌트라도 빠지면 그 테넌트의 크론은 에러 없이 영원히 발화하지 않는다")
        .containsKeys(jobA, jobB);

    // 등록된 콜백을 전부 빈 컨텍스트의 별도 스레드에서 돌려 캡처된 테넌트를 관측한다.
    // (공유 테스트 DB 라 다른 세션의 잡도 함께 등록될 수 있다 — 내 잡 두 건만 단언한다.)
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(schedulerMock, atLeast(2)).schedule(captor.capture(), any(Trigger.class));
    for (Runnable task : captor.getAllValues()) {
      runWithoutTenantContext(task);
    }

    assertThat(observedTenantByJob)
        .as("잡은 자기 행의 테넌트로 발화해야 한다 (-1 은 컨텍스트 없음)")
        .containsEntry(jobA, tenantA)
        .containsEntry(jobB, tenantB);
  }

  /**
   * (1b) 발화 콜백이 등록 시점의 테넌트를 들고 가는지 본다.
   *
   * <p>등록된 {@code Runnable} 을 꺼내 <b>테넌트 컨텍스트가 비워진 별도 스레드</b>에서 실행한다 —
   * 실제 cron 발화(다른 풀 스레드, 몇 시간 뒤)와 같은 조건이다. 감싸지 않았다면 여기서 null 이
   * 관측되거나(승계 없음), 멤버십 추론에 의존하던 옛 구현에서는 소유자 A 의 멤버십이 모호해
   * {@code executeJob} 호출 자체가 일어나지 않는다.
   */
  @Test
  @DisplayName("발화 콜백은 등록 시점 테넌트 안에서 실행된다 (멤버십 추론에 의존하지 않는다)")
  void fireCallback_carriesRegistrationTenantIntoFiringThread() throws Exception {
    assertNoAmbientTransaction();
    Map<Long, Long> observedTenantByJob = observeExecuteJobTenant();

    TenantContext.runScoped(tenantA, () -> schedulerService.registerSchedule(jobA, CRON, TZ));
    TenantContext.runScoped(tenantB, () -> schedulerService.registerSchedule(jobB, CRON, TZ));

    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(schedulerMock, times(2)).schedule(captor.capture(), any(Trigger.class));
    List<Runnable> fireTasks = captor.getAllValues();

    for (Runnable task : fireTasks) {
      runWithoutTenantContext(task);
    }

    assertThat(observedTenantByJob)
        .as(
            "발화 콜백이 테넌트 없이 executeJob 을 부르면 RLS 가 걸린 report_template 조회가 0행이 되어"
                + " 양식 없는 리포트가 조용히 생성된다 (-1 은 컨텍스트 없음, 키 부재는 아예 미실행)")
        .containsEntry(jobA, tenantA)
        .containsEntry(jobB, tenantB);
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /**
   * 이 테스트가 앰비언트 트랜잭션 밖에서 도는지 기계로 못박는다.
   *
   * <p>왜 주석이 아니라 단언인가: 누군가 이 클래스에 클래스 레벨 {@code @Transactional} 을 붙이면
   * 테스트 트랜잭션이 GUC 를 공급해 <b>검증하려던 배선 결함을 정확히 가린다</b>. 이 이니셔티브에서
   * 다섯 번 일어난 실패 패턴이라, 유일한 자동 가드를 주석으로 대체하지 않는다.
   *
   * <p>구 {@code ProactiveScheduleTenantTest} 가 갖고 있던 단언을 그대로 승계한 것이다.
   */
  private void assertNoAmbientTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
        .as("이 테스트는 트랜잭션 밖에서 돌아야 한다 — 클래스 레벨 @Transactional 이 붙으면 결함이 가려진다")
        .isFalse();
  }

  /** executeJob 이 불린 순간의 테넌트를 jobId 별로 기록한다. 배선이 없으면 -1(컨텍스트 없음)이 남는다. */
  private Map<Long, Long> observeExecuteJobTenant() {
    Map<Long, Long> observed = new ConcurrentHashMap<>();
    Mockito.doAnswer(
            invocation -> {
              Long firedJobId = invocation.getArgument(0);
              Long current = TenantContext.get();
              observed.put(firedJobId, current == null ? -1L : current);
              return null;
            })
        .when(proactiveJobService)
        .executeJob(Mockito.anyLong(), Mockito.anyLong());
    return observed;
  }

  /**
   * 테넌트 컨텍스트가 없는 새 스레드에서 실행하고, 실행 후에도 컨텍스트가 새지 않는지 확인한다.
   *
   * <p>후자를 함께 보는 이유: {@code runScoped} 는 clear 가 아니라 "진입 전 값 복원" 계약이다. 발화
   * 스레드에 테넌트가 남으면 풀 스레드가 재사용될 때 다른 잡이 남의 테넌트로 돈다.
   */
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

  /**
   * 잡 픽스처를 해당 테넌트로 삽입한다.
   *
   * <p>V103 이후 {@code proactive_job.tenant_id} 는 NOT NULL + GUC 파생 DEFAULT 라, 트랜잭션 없이
   * 삽입하면 GUC 가 비어 NOT NULL 위반이 된다. 감싸는 것은 픽스처뿐이며 검증 대상(재등록·발화)은
   * 여전히 이 경계 밖에서 일어난다.
   */
  private Long insertScheduleJob(Long owner, String namePrefix) {
    return dsl.insertInto(table(name("proactive_job")))
        .set(field(name("user_id"), Long.class), owner)
        .set(
            field(name("name"), String.class),
            namePrefix + "-" + TenantRlsTestSupport.nextTenantId())
        .set(field(name("prompt"), String.class), "test")
        .set(field(name("config"), JSONB.class), JSONB.valueOf("{}"))
        .set(field(name("enabled"), Boolean.class), true)
        .set(field(name("trigger_type"), String.class), "SCHEDULE")
        .set(field(name("cron_expression"), String.class), CRON)
        .set(field(name("timezone"), String.class), TZ)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private void deleteJob(Long jobId) {
    if (jobId != null) {
      dsl.deleteFrom(table(name("proactive_job")))
          .where(field(name("id"), Long.class).eq(jobId))
          .execute();
    }
  }
}
