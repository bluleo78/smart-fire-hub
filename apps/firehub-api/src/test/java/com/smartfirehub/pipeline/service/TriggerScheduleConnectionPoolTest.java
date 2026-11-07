package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SCHEDULE 트리거 동시 생성·켜기·수정의 커넥션 풀 교착 회귀 테스트(WD-554).
 *
 * <p>예전에는 afterCommit 콜백이 {@code nextFireTime} 기록(REQUIRES_NEW)을 해, 바깥 트랜잭션이 커넥션을 쥔 채 두 번째 커넥션을
 * 요구했다. 동시 요청 수가 풀 크기(테스트 2개) 이상이면 모두 두 번째 커넥션을 기다리며 교착했다(Hikari 30초 대기 → 500). 이제 기록은 같은 트랜잭션에 실려
 * 요청당 커넥션 1개라, 풀보다 많은 동시 요청도 차례로 끝나야 하고 스케줄·{@code nextFireTime} 이 모두 남아야 한다.
 *
 * <p>클래스 {@code @Transactional} 을 두지 않는다 — 테스트 트랜잭션이 커넥션을 쥐면 풀 계산이 달라지고 afterCommit 이 실행되지 않는다.
 */
class TriggerScheduleConnectionPoolTest extends IntegrationTestBase {

  private static final int CONCURRENCY = 8;

  /** 교착이 없으면 넉넉히 끝나는 시간. 교착이면 Hikari 대기(30초)에 걸려 이 안에 끝나지 않는다. */
  private static final long TIMEOUT_SECONDS = 10;

  @Autowired private TriggerService triggerService;
  @Autowired private TriggerSchedulerService schedulerService;
  @Autowired private PipelineService pipelineService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private Long pipelineId;
  private final List<Long> createdTriggerIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "sched_pool_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Schedule Pool User")
            .set(USER.EMAIL, "sched_pool_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("sched-pool-" + unique, "WD-554", List.of()), userId)
            .id();
  }

  /** 컨텍스트가 캐시돼 공유되므로 등록한 스케줄을 반드시 내려 이후 테스트에서 발화하지 않게 한다. */
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

  private CreateTriggerRequest scheduleRequest(int i) {
    return new CreateTriggerRequest(
        "동시생성" + i, TriggerType.SCHEDULE, null, Map.of("cron", "0 0 3 * * *", "timezone", "UTC"));
  }

  /** 동시 SCHEDULE 생성 8건(풀 2) — 모두 제시간에 끝나고, 스케줄이 걸리고, nextFireTime 이 같은 트랜잭션에서 커밋돼야 한다. */
  @Test
  void concurrentScheduleCreates_doNotExhaustPool() throws Exception {
    List<Object> outcomes =
        runConcurrently(
            i -> () -> triggerService.createTrigger(pipelineId, scheduleRequest(i), userId));

    assertThat(outcomes).hasSize(CONCURRENCY).allMatch(o -> o instanceof TriggerResponse);
    for (Object o : outcomes) {
      TriggerResponse created = (TriggerResponse) o;
      createdTriggerIds.add(created.id());
      assertThat(schedulerService.isScheduled(created.id())).as("스케줄 등록").isTrue();
      assertThat(created.triggerState()).as("응답의 다음 실행").containsKey("nextFireTime");
    }
    // 저장된 값도 응답과 같아야 한다(응답과 상태 불일치 회귀).
    List<TriggerResponse> stored = triggerService.getTriggers(pipelineId);
    assertThat(stored).hasSize(CONCURRENCY);
    assertThat(stored).allMatch(t -> t.triggerState().containsKey("nextFireTime"));
  }

  /** 꺼 둔 SCHEDULE 트리거 8건을 동시에 켠다(토글 경로) — 교착 없이 모두 켜지고 스케줄이 걸려야 한다. */
  @Test
  void concurrentScheduleToggles_doNotExhaustPool() throws Exception {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      TriggerResponse created =
          triggerService.createTrigger(pipelineId, scheduleRequest(i), userId);
      createdTriggerIds.add(created.id());
      ids.add(created.id());
      triggerService.toggleTrigger(created.id(), false);
    }

    List<Object> outcomes =
        runConcurrently(
            i ->
                () -> {
                  triggerService.toggleTrigger(ids.get(i), true);
                  return Boolean.TRUE;
                });

    assertThat(outcomes).hasSize(CONCURRENCY).allMatch(Boolean.TRUE::equals);
    for (Long id : ids) {
      assertThat(schedulerService.isScheduled(id)).as("스케줄 등록: %s", id).isTrue();
    }
  }

  /** SCHEDULE 트리거 8건의 cron 을 동시에 수정(수정 경로) — 교착 없이 끝나고 새 nextFireTime 이 남아야 한다. */
  @Test
  void concurrentScheduleUpdates_doNotExhaustPool() throws Exception {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < CONCURRENCY; i++) {
      TriggerResponse created =
          triggerService.createTrigger(pipelineId, scheduleRequest(i), userId);
      createdTriggerIds.add(created.id());
      ids.add(created.id());
    }

    List<Object> outcomes =
        runConcurrently(
            i ->
                () -> {
                  triggerService.updateTrigger(
                      pipelineId,
                      ids.get(i),
                      new UpdateTriggerRequest(
                          null, null, null, Map.of("cron", "0 30 4 * * *", "timezone", "UTC")),
                      userId);
                  return Boolean.TRUE;
                });

    assertThat(outcomes).hasSize(CONCURRENCY).allMatch(Boolean.TRUE::equals);
    for (TriggerResponse t : triggerService.getTriggers(pipelineId)) {
      assertThat(t.config()).containsEntry("cron", "0 30 4 * * *");
      assertThat(t.triggerState()).containsKey("nextFireTime");
      assertThat(schedulerService.isScheduled(t.id())).isTrue();
    }
  }

  /**
   * 작업 {@link #CONCURRENCY} 개를 같은 순간에 출발시키고(CountDownLatch) 결과를 모은다. 작업이 던진 예외는 결과로 돌려준다. 전체가
   * {@link #TIMEOUT_SECONDS} 안에 끝나지 않으면 TimeoutException 으로 실패한다 — 교착의 신호다. 워커 스레드에는 요청 필터처럼 테넌트
   * 컨텍스트를 세운다.
   */
  private List<Object> runConcurrently(java.util.function.IntFunction<Callable<Object>> factory)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENCY; i++) {
        Callable<Object> task = factory.apply(i);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return TenantContext.runScopedGet(
                      DEFAULT_TEST_TENANT_ID,
                      () -> {
                        try {
                          return task.call();
                        } catch (Exception e) {
                          return e;
                        }
                      });
                }));
      }
      start.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> f : futures) {
        outcomes.add(f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }
}
