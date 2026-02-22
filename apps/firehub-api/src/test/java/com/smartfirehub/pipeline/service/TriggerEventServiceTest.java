package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.event.PipelineCompletedEvent;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TriggerEventServiceTest extends IntegrationTestBase {

  @Autowired private TriggerEventService triggerEventService;

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private TriggerRepository triggerRepository;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long upstreamPipelineId;
  private Long downstreamPipelineId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "event_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Event Test User")
            .set(USER.EMAIL, "event_test@example.com")
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
    PipelineCompletedEvent event = new PipelineCompletedEvent(upstreamPipelineId, 4L, "COMPLETED");

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
