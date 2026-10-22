package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerType;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 실행 시 연쇄 깊이 방어(WD-106) — 저장 시 검사를 빠져나간 순환이 DB 에 이미 있어도 연쇄가 끝없이 돌지 않는지 실제 비동기 실행으로 본다.
 *
 * <p>결함 재현: A ↔ B 가 서로를 「완료 시 실행」하면 A 를 한 번 실행했을 때 약 20초에 591건이 실행됐다. 방어가 있으면 수동 실행 1건 + 연쇄 {@link
 * TriggerService#MAX_CHAIN_DEPTH} 건에서 멈추고, 다음 단계는 건너뜀(SKIPPED) 이벤트로 남는다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 붙이지 않는다</b> — 비동기 실행 스레드가 픽스처를 보려면 커밋돼 있어야 하고, 연쇄 경로가 스스로
 * 트랜잭션·테넌트를 세우는지도 함께 검증돼야 한다. 그래서 정리는 {@link #cleanup()} 에서 직접 한다.
 */
class TriggerChainRuntimeDepthTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private TriggerService triggerService;
  @Autowired private DSLContext dsl;

  private Long userId;
  private Long pipelineA;
  private Long pipelineB;

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "chain_depth_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Chain Depth User")
            .set(USER.EMAIL, "chain_depth_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    // WD-167: 트리거는 실행 주체(생성자)의 pipeline:execute 를 발화 시점에 확인한다 — 실행 권한을 준다
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
    // 스텝 없는 파이프라인은 곧바로 COMPLETED 로 끝나 SUCCESS 조건 연쇄를 발화한다.
    pipelineA =
        pipelineService
            .createPipeline(new CreatePipelineRequest("depth-A-" + unique, null, List.of()), userId)
            .id();
    pipelineB =
        pipelineService
            .createPipeline(new CreatePipelineRequest("depth-B-" + unique, null, List.of()), userId)
            .id();
    // 저장 시 검사를 우회해 A ↔ B 순환을 직접 넣는다(결함 이전에 운영에 저장된 상태 재현).
    inTenantFixture(
        () -> {
          insertChainTrigger(pipelineB, pipelineA); // A → B
          insertChainTrigger(pipelineA, pipelineB); // B → A
        });
  }

  @AfterEach
  void cleanup() throws InterruptedException {
    // 파이프라인을 지우면 트리거·실행·이벤트가 FK CASCADE 로 함께 사라진다. 방어가 없을 때(변이 확인) 연쇄가 계속 돌고 있을 수 있어
    // 트리거부터 지워 연쇄를 끊고 잠시 기다린 뒤 나머지를 지운다.
    inTenantFixture(
        () ->
            dsl.deleteFrom(table(name("pipeline_trigger")))
                .where(field(name("pipeline_id"), Long.class).in(pipelineA, pipelineB))
                .execute());
    awaitQuiet(5_000);
    inTenantFixture(
        () -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.in(pipelineA, pipelineB)).execute());
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(userId)).execute());
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
  }

  @Test
  void storedCycle_stopsAtMaxChainDepth_andRecordsSkippedEvent() throws InterruptedException {
    // 검증 대상 호출 — 픽스처 블록 밖에서 운영 경로 그대로 수동 실행한다.
    pipelineService.executePipeline(pipelineA, userId);

    long expected = 1 + TriggerService.MAX_CHAIN_DEPTH;
    awaitChainEnd(60_000);

    // 정확히 1(수동) + 상한(연쇄) 건. "이하" 로 단언하면 깊이를 잘못 세어 연쇄가 너무 일찍 끊기는 결함도 통과한다.
    assertThat(executionCount()).isEqualTo(expected);

    // 상한 다음 단계는 건너뜀 이벤트 한 건으로 남는다.
    List<String> skipped =
        inTenantFixture(
            () ->
                dsl.select(field("detail->>'chainDepth'", String.class))
                    .from(table(name("trigger_event")))
                    .where(field(name("pipeline_id"), Long.class).in(pipelineA, pipelineB))
                    .and(field(name("event_type"), String.class).eq("SKIPPED"))
                    .fetch(r -> r.value1()));
    assertThat(skipped).containsExactly(String.valueOf(TriggerService.MAX_CHAIN_DEPTH + 1));

    // 발화된 연쇄 단계는 1 … 상한까지 하나씩.
    List<Integer> firedDepths =
        inTenantFixture(
            () ->
                dsl.select(field("(detail->>'chainDepth')::int", Integer.class))
                    .from(table(name("trigger_event")))
                    .where(field(name("pipeline_id"), Long.class).in(pipelineA, pipelineB))
                    .and(field(name("event_type"), String.class).eq("FIRED"))
                    .orderBy(field(name("id")))
                    .fetch(r -> r.value1()));
    assertThat(firedDepths)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.rangeClosed(1, TriggerService.MAX_CHAIN_DEPTH)
                .boxed()
                .toList());
  }

  @Test
  void apiCallerCannotInjectChainDepth_toLiftTheCap() throws InterruptedException {
    // API 트리거 params 는 외부 호출자의 요청 본문이 그대로 FIRED 이벤트 detail 에 남는다. 거기에 음수 chainDepth 를 넣어도 연쇄
    // 깊이로 믿지 않아야 한다(연쇄 트리거의 FIRED 이벤트만 믿는다) — 믿으면 상한이 사실상 사라진다.
    Long apiTrigger =
        triggerService
            .createTrigger(
                pipelineA,
                new CreateTriggerRequest("api-root", TriggerType.API, null, Map.of()),
                userId)
            .id();

    triggerService.fireTrigger(apiTrigger, Map.of(TriggerService.CHAIN_DEPTH_PARAM, -1_000_000));
    awaitChainEnd(60_000);

    assertThat(executionCount()).isEqualTo(1 + TriggerService.MAX_CHAIN_DEPTH);
  }

  /** 두 파이프라인의 실행 건수. */
  private long executionCount() {
    return inTenantFixture(
        () ->
            (long)
                dsl.fetchCount(
                    PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.in(pipelineA, pipelineB)));
  }

  /**
   * 연쇄가 끝날 때까지 기다린다. 끝의 <b>적극적 신호</b>(건너뜀 이벤트가 생기고 진행 중 실행이 없음)를 먼저 기다린 뒤 짧은 정지 구간을 더 본다 — 한 실행이
   * COMPLETED 된 뒤 다음 실행이 만들어지기 전(@Async 리스너 → fireTrigger 트랜잭션) 틈에는 "건수 불변·진행 중 없음"이 잠시 참이 되므로, 정지만
   * 보고 빠져나오면 스위트가 바쁠 때 연쇄 도중에 단언해 버린다. 방어가 빠져 연쇄가 끝없이 돌면(변이 확인) 상한을 크게 넘는 순간 바로 빠져나와 단언이 실패하게 한다.
   */
  private void awaitChainEnd(long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (overshot()) {
        return;
      }
      int skipped =
          inTenantFixture(
              () ->
                  dsl.fetchCount(
                      table(name("trigger_event")),
                      field(name("pipeline_id"), Long.class)
                          .in(pipelineA, pipelineB)
                          .and(field(name("event_type"), String.class).eq("SKIPPED"))));
      if (skipped > 0 && inFlightCount() == 0) {
        break;
      }
      Thread.sleep(100);
    }
    // 끝 신호 뒤에도 더 실행되는지(상한을 넘겨 발화하는지) 잠시 더 본다.
    awaitQuiet(5_000);
  }

  /** 실행 건수가 2초 동안 늘지 않고 진행 중 실행도 없을 때까지 기다린다(정리용·끝 신호 뒤 확인용). */
  private void awaitQuiet(long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long last = -1;
    long stableSince = System.currentTimeMillis();
    while (System.currentTimeMillis() < deadline) {
      if (overshot()) {
        return;
      }
      long count = executionCount();
      if (count != last || inFlightCount() > 0) {
        last = count;
        stableSince = System.currentTimeMillis();
      } else if (System.currentTimeMillis() - stableSince >= 2_000) {
        return;
      }
      Thread.sleep(100);
    }
  }

  /** 상한을 크게 넘었는가 — 방어가 없을 때 끝없는 대기를 피하려는 조기 탈출 조건. */
  private boolean overshot() {
    return executionCount() > 3L * (1 + TriggerService.MAX_CHAIN_DEPTH);
  }

  /** 진행 중(PENDING/RUNNING) 실행 건수. */
  private int inFlightCount() {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                PIPELINE_EXECUTION,
                PIPELINE_EXECUTION
                    .PIPELINE_ID
                    .in(pipelineA, pipelineB)
                    .and(PIPELINE_EXECUTION.STATUS.in("PENDING", "RUNNING"))));
  }

  private void insertChainTrigger(Long target, Long upstream) {
    dsl.insertInto(table(name("pipeline_trigger")))
        .set(field(name("pipeline_id"), Long.class), target)
        .set(field(name("trigger_type"), String.class), "PIPELINE_CHAIN")
        .set(field(name("name"), String.class), "legacy-cycle")
        .set(field(name("is_enabled"), Boolean.class), true)
        .set(
            field(name("config"), JSONB.class),
            JSONB.valueOf("{\"upstreamPipelineId\":" + upstream + ",\"condition\":\"SUCCESS\"}"))
        .set(field(name("created_by"), Long.class), userId)
        .execute();
  }
}
