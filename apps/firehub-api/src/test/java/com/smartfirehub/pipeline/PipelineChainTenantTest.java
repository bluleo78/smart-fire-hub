package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PIPELINE_CHAIN 완료 이벤트 경로의 테넌트 배선 검증 (P2-b Task 5).
 *
 * <p>검증 대상은 "완료 이벤트를 발행한 테넌트가 {@code @Async} 리스너 스레드까지, 그리고 그
 * 스레드가 여는 <b>트랜잭션의 GUC</b> 까지 이어지는가" 다. ThreadLocal 만 확인하면 부족하다 —
 * RLS 정책이 읽는 값은 {@code app.tenant_id} GUC 이고, 그 GUC 는
 * {@code TenantAwareTransactionManager.doBegin} 에서만 주입되므로 "컨텍스트는 있는데 트랜잭션이
 * 없어 조용히 0행" 이 이 이니셔티브의 대표적 실패 형태다.
 *
 * <p>Task 9(V96)가 {@code pipeline_trigger} 에 정책을 걸었으므로 두 층을 함께 단언한다:
 * (1) 발화 스레드의 GUC 가 발행 테넌트와 같을 것 — RLS 필터링이 성립하기 위한 필요조건이고,
 * 정책이 없어도 판별력이 있다. (2) 남의 테넌트 트리거는 아예 발화하지 않을 것 — 정책이 실제로
 * 거르는지 보는 {@code never()} 단언이다. (1)만 두면 정책을 지워도 통과하고, (2)만 두면 GUC 가
 * 아예 비어 아무것도 발화하지 않는 경우에도 공허하게 통과한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> 테스트가 트랜잭션을 열면
 * GUC 를 테스트가 공급해 프로덕션 배선 누락을 구조적으로 가린다. 픽스처만
 * {@link TenantRlsTestSupport#runInTenantTransaction} 으로 감싸고, 검증 대상(이벤트 발행)은
 * 트랜잭션 밖에 남긴다(선례: {@code pipeline/TriggerSchedulerTenantTest}).
 */
class PipelineChainTenantTest extends IntegrationTestBase {

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 실제 발화는 하위 파이프라인을 진짜로 실행시키므로 목으로 대체하고, 관측만 한다. */
  @MockitoBean private TriggerService triggerService;

  private TransactionTemplate tx;

  private Long tenantA;
  private Long tenantB;
  private Long userA;
  private Long userB;
  private Long upstreamPipeline;
  private Long pipelineB;
  private Long triggerA;
  private Long triggerB;

  /** 발화된 트리거 → 그 발화 스레드의 트랜잭션에서 읽은 {@code app.tenant_id} GUC 값. */
  private final Map<Long, Long> observedGucByTrigger = new ConcurrentHashMap<>();

  @BeforeEach
  void seedTwoTenants() {
    tx = new TransactionTemplate(transactionManager);

    // tenant·user 는 테넌트 경계 위의 전역 테이블(RLS 없음)이라 컨텍스트 없이 만들 수 있다.
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "chain-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "chain-b");
    userA = TenantRlsTestSupport.insertUser(dsl, "chain_a_");
    userB = TenantRlsTestSupport.insertUser(dsl, "chain_b_");

    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantA,
        () -> {
          upstreamPipeline = insertPipeline(userA, "chain-upstream-a");
          triggerA = insertChainTrigger(upstreamPipeline, userA, "Tenant A Chain", upstreamPipeline);
        });

    // 핵심: B 의 체인 트리거도 <b>같은</b> upstreamPipelineId 를 가리킨다. 서로 다른 id 를 쓰면
    // pipelineId 가 판별자가 되어 테넌트 배선이 전혀 없어도 테스트가 통과하는 공허한 단언이 된다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantB,
        () -> {
          pipelineB = insertPipeline(userB, "chain-downstream-b");
          triggerB = insertChainTrigger(pipelineB, userB, "Tenant B Chain", upstreamPipeline);
        });

    // 발화 시점에 "그 스레드가 여는 트랜잭션"의 GUC 를 읽어 둔다 — RLS 정책이 실제로 읽는 값이다.
    doAnswer(
            invocation -> {
              Long firedTriggerId = invocation.getArgument(0);
              String guc =
                  tx.execute(
                      status ->
                          dsl.fetchOne("SELECT current_setting('app.tenant_id', true)")
                              .get(0, String.class));
              observedGucByTrigger.put(
                  firedTriggerId,
                  (guc == null || guc.isBlank()) ? -1L : Long.parseLong(guc.trim()));
              return null;
            })
        .when(triggerService)
        .fireTrigger(any(), any());
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.runInTenantTransaction(tx, tenantA, () -> deletePipeline(upstreamPipeline));
    TenantRlsTestSupport.runInTenantTransaction(tx, tenantB, () -> deletePipeline(pipelineB));
    TenantRlsTestSupport.deleteUser(dsl, userA);
    TenantRlsTestSupport.deleteUser(dsl, userB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  /**
   * 테넌트 A 컨텍스트에서 발행한 완료 이벤트가 A 의 테넌트로 체인 트리거를 발화시키는지 본다.
   *
   * <p>발행 직전 {@link IntegrationTestBase} 가 세워둔 기본 테넌트(1)를 지우는 것이 핵심이다 —
   * 남겨 두면 승계가 없어도 GUC 가 채워져 단언이 공허해진다.
   */
  @Test
  void completionEventFromTenantA_firesChainTriggerWithTenantAGuc() {
    publishCompletionAs(tenantA);
    awaitFired(triggerA);

    assertThat(observedGucByTrigger)
        .as("A 의 완료 이벤트는 A 의 GUC 로 체인 트리거를 발화해야 한다 (-1 은 GUC 없음)")
        .containsEntry(triggerA, tenantA);
    assertNeverFired(triggerB, "B");
  }

  /** 반대 방향 — B 에서 발행하면 B 의 GUC 로 발화된다. 한 방향만 보면 하드코딩도 통과한다. */
  @Test
  void completionEventFromTenantB_firesChainTriggerWithTenantBGuc() {
    publishCompletionAs(tenantB);
    awaitFired(triggerB);

    assertThat(observedGucByTrigger)
        .as("B 의 완료 이벤트는 B 의 GUC 로 체인 트리거를 발화해야 한다 (-1 은 GUC 없음)")
        .containsEntry(triggerB, tenantB);
    assertNeverFired(triggerA, "A");
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /**
   * 남의 테넌트 체인 트리거가 발화하지 않았음을 단언한다(V96 정책이 실제로 거르는지).
   *
   * <p>두 트리거는 <b>같은</b> upstreamPipelineId 를 가리키므로 정책이 없으면 둘 다 발화한다 —
   * 즉 이 단언은 정책을 지우면 실제로 깨진다. 발화는 비동기라 짧게 안정화 시간을 준 뒤 본다:
   * 상대 트리거는 이미 발화가 확인된 것과 <b>같은 리스너 패스</b>에서 조회되므로, 걸러지지
   * 않았다면 이 시점에 이미 관측 맵에 들어와 있다.
   */
  private void assertNeverFired(Long triggerId, String label) {
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(observedGucByTrigger)
        .as("테넌트 %s 의 체인 트리거가 발화했다 — V96 정책이 남의 테넌트 트리거를 거르지 못했다", label)
        .doesNotContainKey(triggerId);
  }

  /**
   * 해당 트리거의 관측 결과가 들어올 때까지 기다린다.
   *
   * <p>Mockito {@code timeout()} 대신 관측 맵을 폴링하는 이유: Mockito 는 호출을 <b>기록한 뒤</b>
   * answer 를 실행하므로, verify 가 통과한 시점에 맵이 아직 비어 있을 수 있다(플레이크).
   * Awaitility 는 이 프로젝트 클래스패스에 없다.
   */
  private void awaitFired(Long triggerId) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (observedGucByTrigger.containsKey(triggerId)) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(observedGucByTrigger)
        .as("체인 트리거 %s 가 10초 안에 발화하지 않았다 — 리스너에 테넌트가 승계되지 않았을 수 있다", triggerId)
        .containsKey(triggerId);
  }

  /** 트랜잭션 밖에서, 지정 테넌트 컨텍스트만 세운 채 완료 이벤트를 발행한다(운영 발행 경로와 동일). */
  private void publishCompletionAs(Long tenantId) {
    TenantContext.clear();
    TenantContext.runScoped(
        tenantId,
        () ->
            eventPublisher.publishEvent(
                // executionId 는 null 이면 리스너의 Map.of 에서 NPE 가 난다 — 더미 값을 넣는다.
                // createdBy=null 이면 NotificationService 리스너는 조기 반환한다(관심 밖).
                new PipelineCompletedEvent(upstreamPipeline, 999_999L, "COMPLETED", null)));
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

  /** PIPELINE_CHAIN 트리거를 만든다. 상위 파이프라인은 config 의 upstreamPipelineId 로만 참조된다. */
  private Long insertChainTrigger(
      Long pipelineId, Long ownerId, String triggerName, Long upstreamPipelineId) {
    return dsl.insertInto(table(name("pipeline_trigger")))
        .set(field(name("pipeline_id"), Long.class), pipelineId)
        .set(field(name("trigger_type"), String.class), "PIPELINE_CHAIN")
        .set(field(name("name"), String.class), triggerName)
        .set(field(name("is_enabled"), Boolean.class), true)
        .set(
            field(name("config"), JSONB.class),
            JSONB.valueOf(
                "{\"upstreamPipelineId\":" + upstreamPipelineId + ",\"condition\":\"SUCCESS\"}"))
        .set(field(name("created_by"), Long.class), ownerId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** 파이프라인을 지우면 트리거가 FK CASCADE 로 함께 사라진다. */
  private void deletePipeline(Long pipelineId) {
    if (pipelineId != null) {
      dsl.deleteFrom(table(name("pipeline")))
          .where(field(name("id"), Long.class).eq(pipelineId))
          .execute();
    }
  }
}
