package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TriggerServiceTest extends IntegrationTestBase {

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long pipelineId;
  private Long pipelineId2;

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "trigger_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Trigger Test User")
            .set(USER.EMAIL, "trigger_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // Create test pipelines
    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Test Pipeline 1", "Description 1", List.of()), testUserId);
    pipelineId = pipeline.id();

    PipelineDetailResponse pipeline2 =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Test Pipeline 2", "Description 2", List.of()), testUserId);
    pipelineId2 = pipeline2.id();
  }

  @Test
  void createScheduleTrigger_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Daily Schedule",
            TriggerType.SCHEDULE,
            "Run every day at 9 AM",
            Map.of("cron", "0 9 * * *", "timezone", "Asia/Seoul", "concurrencyPolicy", "SKIP"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.name()).isEqualTo("Daily Schedule");
    assertThat(response.triggerType()).isEqualTo("SCHEDULE");
    assertThat(response.isEnabled()).isTrue();
    assertThat(response.config()).containsKey("cron");
    assertThat(response.config().get("cron")).isEqualTo("0 9 * * *");
  }

  @Test
  void createApiTrigger_returnsRawToken() {
    CreateTriggerRequest request =
        new CreateTriggerRequest("API Trigger", TriggerType.API, "External API trigger", Map.of());

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("API");
    assertThat(response.config()).containsKey("rawToken");
    assertThat(response.config()).containsKey("tokenHash");
    assertThat(response.config().get("rawToken")).isNotNull();
  }

  @Test
  void createPipelineChainTrigger_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Chain Trigger",
            TriggerType.PIPELINE_CHAIN,
            "Run after pipeline 2",
            Map.of("upstreamPipelineId", pipelineId2.intValue(), "condition", "SUCCESS"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("PIPELINE_CHAIN");
  }

  @Test
  void createPipelineChainTrigger_selfReference_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Self Chain",
            TriggerType.PIPELINE_CHAIN,
            "Self reference",
            Map.of("upstreamPipelineId", pipelineId.intValue(), "condition", "SUCCESS"));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(CyclicTriggerDependencyException.class);
  }

  @Test
  void createWebhookTrigger_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Webhook Trigger", TriggerType.WEBHOOK, "Receive webhooks", Map.of());

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("WEBHOOK");
    assertThat(response.config()).containsKey("webhookId");
  }

  @Test
  void createDatasetChangeTrigger_missingDatasetIds_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Dataset Change", TriggerType.DATASET_CHANGE, "Detect changes", Map.of());

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("datasetIds");
  }

  @Test
  void getTriggers_returnsListForPipeline() {
    triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest("Trigger 1", TriggerType.API, null, Map.of()),
        testUserId);
    triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest("Trigger 2", TriggerType.WEBHOOK, null, Map.of()),
        testUserId);

    List<TriggerResponse> triggers = triggerService.getTriggers(pipelineId);

    assertThat(triggers).hasSize(2);
  }

  @Test
  void updateTrigger_success() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Original", TriggerType.API, "Original desc", Map.of()),
            testUserId);

    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("Updated", null, "Updated desc", null), testUserId);

    TriggerResponse updated = triggerService.getTriggerById(created.id());
    assertThat(updated.name()).isEqualTo("Updated");
    assertThat(updated.description()).isEqualTo("Updated desc");
  }

  @Test
  void deleteTrigger_success() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("To Delete", TriggerType.API, null, Map.of()),
            testUserId);

    triggerService.deleteTrigger(created.id());

    assertThatThrownBy(() -> triggerService.getTriggerById(created.id()))
        .isInstanceOf(TriggerNotFoundException.class);
  }

  @Test
  void toggleTrigger_disablesAndEnables() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Toggle Test", TriggerType.API, null, Map.of()),
            testUserId);

    assertThat(created.isEnabled()).isTrue();

    triggerService.toggleTrigger(created.id(), false);
    TriggerResponse disabled = triggerService.getTriggerById(created.id());
    assertThat(disabled.isEnabled()).isFalse();

    triggerService.toggleTrigger(created.id(), true);
    TriggerResponse enabled = triggerService.getTriggerById(created.id());
    assertThat(enabled.isEnabled()).isTrue();
  }

  @Test
  void resolveApiToken_findsCorrectTrigger() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Token Test", TriggerType.API, null, Map.of()),
            testUserId);

    String rawToken = (String) created.config().get("rawToken");
    assertThat(rawToken).isNotNull();

    TriggerResponse resolved = triggerService.resolveApiToken(rawToken);
    assertThat(resolved).isNotNull();
    assertThat(resolved.id()).isEqualTo(created.id());
  }

  @Test
  void resolveApiToken_invalidToken_returnsNull() {
    TriggerResponse resolved = triggerService.resolveApiToken("invalid-token-value");
    assertThat(resolved).isNull();
  }

  @Test
  void fireTrigger_inactivePipeline_recordsSkippedEvent() {
    // Create trigger
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Fire Test", TriggerType.API, null, Map.of()),
            testUserId);

    // Deactivate pipeline
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest("Test Pipeline 1", "Description 1", false, null),
        testUserId);

    // Fire trigger
    triggerService.fireTrigger(created.id(), Map.of());

    // Verify SKIPPED event was created
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events).anyMatch(e -> "SKIPPED".equals(e.eventType()));
  }

  @Test
  void createScheduleTrigger_missingCron_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Bad Schedule", TriggerType.SCHEDULE, null, Map.of("timezone", "Asia/Seoul"));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cron");
  }
}
