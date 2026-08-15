package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>클래스 레벨 {@code @Transactional} 을 뺐다 — 의도된 것이다(P2-b Task 9).</b> 붙어 있으면
 * {@code pollDatasetChanges} 같은 테넌트 순회 경로가 테스트 트랜잭션에 얹혀 모든 순회 패스가
 * 테넌트 1 의 GUC 로 실행되고, 순회 배선을 지워도 통과하는 사각지대가 생긴다. 롤백이 사라졌으므로
 * 픽스처는 {@link #cleanup()} 에서 직접 지우고 유니크 컬럼은 실행마다 고유하게 만든다.
 */
class TriggerEventServiceTest extends IntegrationTestBase {

  @Autowired private TriggerEventService triggerEventService;

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private TriggerRepository triggerRepository;

  @Autowired private DSLContext dsl;

  @Autowired private TransactionTemplate tx;

  private Long testUserId;
  private Long upstreamPipelineId;
  private Long downstreamPipelineId;

  @BeforeEach
  void setUp() {
    String unique = String.valueOf(System.nanoTime());
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "event_test_user_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Event Test User")
            .set(USER.EMAIL, "event_test_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    PipelineDetailResponse upstream =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Upstream Pipeline", "Upstream", List.of()), testUserId);
    upstreamPipelineId = upstream.id();

    PipelineDetailResponse downstream =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Downstream Pipeline", "Downstream", List.of()), testUserId);
    downstreamPipelineId = downstream.id();
  }

  @AfterEach
  void cleanup() {
    // pipeline 을 지우면 트리거·이벤트가 FK CASCADE 로 함께 사라진다. RLS 대상이라 트랜잭션 안에서.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(downstreamPipelineId)).execute();
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(upstreamPipelineId)).execute();
        });
    // 트리거 발화가 감사 로그를 남긴다. 롤백이 없어졌으므로 user 를 지우기 전에 직접 정리해야
    // FK 로 막히지 않는다. V99 부터 audit_log 에도 RLS 가 걸리므로(형태 b) 트랜잭션 밖의 bare
    // delete 는 GUC 가 비어 NULL 테넌트 행만 지우고, 기본 테넌트 행이 남아 "user" 삭제가 FK 로
    // 터진다 — 정리도 테넌트 컨텍스트 트랜잭션 안에서 한다.
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(testUserId)).execute());
    dsl.deleteFrom(USER).where(USER.ID.eq(testUserId)).execute();
  }

  @Test
  void chainTriggerLookup_withMatchingUpstream_findsTriggers() {
    // Create chain trigger: downstream fires when upstream completes with SUCCESS
    triggerService.createTrigger(
        downstreamPipelineId,
        new CreateTriggerRequest(
            "Chain on Success",
            TriggerType.PIPELINE_CHAIN,
            "Fire on upstream success",
            Map.of("upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "SUCCESS")),
        testUserId);

    // Verify chain trigger lookup finds the trigger by upstream pipeline ID
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(upstreamPipelineId);
    assertThat(chainTriggers).hasSize(1);
    assertThat(chainTriggers.get(0).pipelineId()).isEqualTo(downstreamPipelineId);
    assertThat(chainTriggers.get(0).config().get("condition")).isEqualTo("SUCCESS");
  }

  @Test
  void chainTriggerLookup_withNonMatchingUpstream_returnsEmpty() {
    // Create chain trigger for a different upstream
    triggerService.createTrigger(
        downstreamPipelineId,
        new CreateTriggerRequest(
            "Chain on Success",
            TriggerType.PIPELINE_CHAIN,
            "Fire on upstream success",
            Map.of("upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "SUCCESS")),
        testUserId);

    // Query with a non-existent upstream pipeline ID
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(99999L);
    assertThat(chainTriggers).isEmpty();
  }

  @Test
  void onPipelineCompleted_withDisabledTrigger_doesNotFindIt() {
    // Create and then disable chain trigger
    TriggerResponse created =
        triggerService.createTrigger(
            downstreamPipelineId,
            new CreateTriggerRequest(
                "Disabled Chain",
                TriggerType.PIPELINE_CHAIN,
                "Disabled trigger",
                Map.of(
                    "upstreamPipelineId", upstreamPipelineId.intValue(), "condition", "SUCCESS")),
            testUserId);

    triggerService.toggleTrigger(created.id(), false);

    // Disabled triggers should not appear in lookup
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(upstreamPipelineId);
    assertThat(chainTriggers).isEmpty();
  }

  @Test
  void onPipelineCompleted_withNoChainTriggers_doesNothing() {
    // No chain triggers exist for this pipeline
    PipelineCompletedEvent event =
        new PipelineCompletedEvent(upstreamPipelineId, 4L, "COMPLETED", null);

    // Should not throw (async execution won't affect test thread)
    triggerEventService.onPipelineCompleted(event);

    // Verify no chain triggers found for this upstream
    List<TriggerResponse> chainTriggers =
        triggerRepository.findEnabledChainTriggersByUpstreamId(upstreamPipelineId);
    assertThat(chainTriggers).isEmpty();
  }

  @Test
  void fireTrigger_withActivePipeline_recordsEvent() {
    // Create an API trigger (simpler than chain for direct fire test)
    TriggerResponse created =
        triggerService.createTrigger(
            downstreamPipelineId,
            new CreateTriggerRequest("Fire Test", TriggerType.API, null, Map.of()),
            testUserId);

    // Fire trigger directly
    triggerService.fireTrigger(created.id(), Map.of());

    // Verify event was recorded (FIRED or ERROR depending on pipeline execution)
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(downstreamPipelineId, 10);
    assertThat(events).isNotEmpty();
  }

  @Test
  void pollDatasetChanges_withNoDatasetChangeTriggers_doesNothing() {
    // No dataset change triggers exist — should complete without error
    triggerEventService.pollDatasetChanges();
  }
}
