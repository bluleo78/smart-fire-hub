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

/**
 * TriggerService 추가 통합 테스트 — 기존 테스트에서 커버되지 않은 분기.
 * - WEBHOOK with secret (encryptSecret/decryptSecret 경로)
 * - verifyWebhookSignature (secret 있음 / 없음 / webhook 없음)
 * - findByWebhookId
 * - fireTrigger: disabled trigger, null trigger (deleted)
 * - DATASET_CHANGE 트리거 생성 성공
 * - updateTrigger: PIPELINE_CHAIN 업스트림 변경 시 cycle 검사
 * - deleteTrigger: SCHEDULE 트리거 삭제 (afterCommit unregister 분기)
 * - toggleTrigger: SCHEDULE 트리거 (enable/disable afterCommit 분기)
 * - validateScheduleConfig: 기본값 주입 (timezone, concurrencyPolicy)
 * - getTriggerEvents 조회
 */
@Transactional
class TriggerServiceExtTest extends IntegrationTestBase {

  @Autowired private TriggerService triggerService;
  @Autowired private PipelineService pipelineService;
  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long pipelineId;
  private Long pipelineId2;
  private Long pipelineId3;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "trigext_" + System.nanoTime())
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Trig Ext User")
            .set(USER.EMAIL, "trigext_" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    pipelineId =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("Ext Pipeline 1", "desc", List.of()), testUserId)
            .id();

    pipelineId2 =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("Ext Pipeline 2", "desc", List.of()), testUserId)
            .id();

    pipelineId3 =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest("Ext Pipeline 3", "desc", List.of()), testUserId)
            .id();
  }

  // -----------------------------------------------------------------------
  // WEBHOOK with secret — encryptSecret 분기
  // -----------------------------------------------------------------------

  @Test
  void createWebhookTrigger_withSecret_storesEncryptedSecret() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Webhook With Secret",
            TriggerType.WEBHOOK,
            "Signed webhook",
            Map.of("secret", "my-webhook-secret"));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.config()).containsKey("webhookId");
    // 원본 secret은 없어야 하고 암호화된 값만 있어야 한다
    assertThat(response.config()).doesNotContainKey("secret");
    assertThat(response.config()).containsKey("secretEncrypted");
  }

  @Test
  void createWebhookTrigger_emptySecret_doesNotEncrypt() {
    // secret이 빈 문자열이면 암호화하지 않는다
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Webhook Empty Secret",
            TriggerType.WEBHOOK,
            "No secret",
            Map.of("secret", ""));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.config()).containsKey("webhookId");
    assertThat(response.config()).doesNotContainKey("secretEncrypted");
  }

  // -----------------------------------------------------------------------
  // verifyWebhookSignature
  // -----------------------------------------------------------------------

  @Test
  void verifyWebhookSignature_noSecret_returnsTrue() {
    // secret 없는 webhook → 서명 검증 없이 true 반환
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("No Secret Webhook", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);

    String webhookId = (String) created.config().get("webhookId");
    boolean valid = triggerService.verifyWebhookSignature(webhookId, "{}", "any-sig");

    assertThat(valid).isTrue();
  }

  @Test
  void verifyWebhookSignature_withSecret_correctSignature_returnsTrue() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Signed Webhook",
                TriggerType.WEBHOOK,
                null,
                Map.of("secret", "test-secret-key")),
            testUserId);

    String webhookId = (String) created.config().get("webhookId");
    String body = "{\"event\":\"push\"}";

    // HMAC-SHA256 계산 (TriggerService 내부 로직과 동일)
    String expectedSig = computeHmacSha256("test-secret-key", body);

    boolean valid = triggerService.verifyWebhookSignature(webhookId, body, expectedSig);
    assertThat(valid).isTrue();
  }

  @Test
  void verifyWebhookSignature_withSecret_wrongSignature_returnsFalse() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Signed Webhook 2",
                TriggerType.WEBHOOK,
                null,
                Map.of("secret", "test-secret-key")),
            testUserId);

    String webhookId = (String) created.config().get("webhookId");
    boolean valid = triggerService.verifyWebhookSignature(webhookId, "{}", "sha256=wrong");

    assertThat(valid).isFalse();
  }

  @Test
  void verifyWebhookSignature_nonExistentWebhookId_returnsFalse() {
    // webhook ID가 존재하지 않으면 false 반환
    boolean valid =
        triggerService.verifyWebhookSignature("non-existent-uuid", "{}", "sha256=something");
    assertThat(valid).isFalse();
  }

  // -----------------------------------------------------------------------
  // findByWebhookId
  // -----------------------------------------------------------------------

  @Test
  void findByWebhookId_existingId_returnsTrigger() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Find Webhook", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);

    String webhookId = (String) created.config().get("webhookId");
    TriggerResponse found = triggerService.findByWebhookId(webhookId);

    assertThat(found).isNotNull();
    assertThat(found.id()).isEqualTo(created.id());
  }

  @Test
  void findByWebhookId_nonExistentId_returnsNull() {
    TriggerResponse found = triggerService.findByWebhookId("does-not-exist");
    assertThat(found).isNull();
  }

  // -----------------------------------------------------------------------
  // DATASET_CHANGE 트리거 생성
  // -----------------------------------------------------------------------

  @Test
  void createDatasetChangeTrigger_withDatasetIds_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Dataset Change Trigger",
            TriggerType.DATASET_CHANGE,
            "Watch datasets",
            Map.of("datasetIds", List.of(1, 2, 3)));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("DATASET_CHANGE");
  }

  // -----------------------------------------------------------------------
  // SCHEDULE — 기본값 주입 (timezone, concurrencyPolicy)
  // -----------------------------------------------------------------------

  @Test
  void createScheduleTrigger_missingTimezoneAndPolicy_defaultsInjected() {
    // timezone, concurrencyPolicy 없이 cron만 전달하면 기본값 주입
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Minimal Schedule",
            TriggerType.SCHEDULE,
            null,
            Map.of("cron", "0 0 * * *"));  // timezone/concurrencyPolicy 없음

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.config()).containsEntry("timezone", "Asia/Seoul");
    assertThat(response.config()).containsEntry("concurrencyPolicy", "SKIP");
  }

  @Test
  void createScheduleTrigger_invalidConcurrencyPolicy_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Bad Policy",
            TriggerType.SCHEDULE,
            null,
            Map.of("cron", "0 0 * * *", "concurrencyPolicy", "INVALID_POLICY"));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -----------------------------------------------------------------------
  // fireTrigger — disabled trigger
  // -----------------------------------------------------------------------

  @Test
  void fireTrigger_disabledTrigger_doesNotExecute() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Disabled Trigger", TriggerType.API, null, Map.of()),
            testUserId);

    triggerService.toggleTrigger(created.id(), false);

    // disabled 상태에서 fire → 이벤트 기록 없이 조용히 스킵
    triggerService.fireTrigger(created.id(), Map.of());

    // 이벤트가 생성되지 않아야 한다 (SKIPPED 이벤트도 없어야 함)
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events).isEmpty();
  }

  // -----------------------------------------------------------------------
  // fireTrigger — active pipeline, fires successfully
  // -----------------------------------------------------------------------

  @Test
  void fireTrigger_activePipeline_recordsFiredEvent() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Fire Active", TriggerType.API, null, Map.of()),
            testUserId);

    // 파이프라인이 활성 상태(기본값)이면 FIRED 이벤트 생성
    triggerService.fireTrigger(created.id(), Map.of("source", "test"));

    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events).anyMatch(e -> "FIRED".equals(e.eventType()));
  }

  // -----------------------------------------------------------------------
  // getTriggerEvents
  // -----------------------------------------------------------------------

  @Test
  void getTriggerEvents_noEvents_returnsEmptyList() {
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events).isEmpty();
  }

  // -----------------------------------------------------------------------
  // updateTrigger — PIPELINE_CHAIN 업스트림 변경 시 self-reference 감지
  // -----------------------------------------------------------------------

  @Test
  void updateTrigger_pipelineChain_changingUpstreamToSelf_throwsCyclicException() {
    // 먼저 valid chain trigger 생성 (pipeline1 ← pipeline2)
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Chain Update Test",
                TriggerType.PIPELINE_CHAIN,
                null,
                Map.of("upstreamPipelineId", pipelineId2.intValue(), "condition", "SUCCESS")),
            testUserId);

    // 업스트림을 자기 자신(pipelineId)으로 변경하면 CyclicTriggerDependencyException
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(),
                    new UpdateTriggerRequest(
                        null, null, null, Map.of("upstreamPipelineId", pipelineId.intValue())),
                    testUserId))
        .isInstanceOf(CyclicTriggerDependencyException.class);
  }

  @Test
  void updateTrigger_nonChainTrigger_noValidation() {
    // PIPELINE_CHAIN이 아닌 API 트리거 업데이트 시 chain 검증 없이 성공
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Update Test", TriggerType.API, null, Map.of()),
            testUserId);

    triggerService.updateTrigger(
        created.id(),
        new UpdateTriggerRequest("Updated Name", null, "new desc", null),
        testUserId);

    TriggerResponse updated = triggerService.getTriggerById(created.id());
    assertThat(updated.name()).isEqualTo("Updated Name");
  }

  // -----------------------------------------------------------------------
  // deleteTrigger — SCHEDULE 트리거 (afterCommit unregisterSchedule 분기)
  // -----------------------------------------------------------------------

  @Test
  void deleteTrigger_scheduleTrigger_succeeds() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Delete Schedule",
                TriggerType.SCHEDULE,
                null,
                Map.of("cron", "0 1 * * *")),
            testUserId);

    triggerService.deleteTrigger(created.id());

    assertThatThrownBy(() -> triggerService.getTriggerById(created.id()))
        .isInstanceOf(TriggerNotFoundException.class);
  }

  // -----------------------------------------------------------------------
  // toggleTrigger — SCHEDULE 트리거 enable/disable
  // -----------------------------------------------------------------------

  @Test
  void toggleTrigger_scheduleTrigger_disableAndEnable() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Toggle Schedule",
                TriggerType.SCHEDULE,
                null,
                Map.of("cron", "0 2 * * *")),
            testUserId);

    // 비활성화 → SCHEDULE afterCommit unregister 분기 커버
    triggerService.toggleTrigger(created.id(), false);
    assertThat(triggerService.getTriggerById(created.id()).isEnabled()).isFalse();

    // 다시 활성화 → SCHEDULE afterCommit register 분기 커버
    triggerService.toggleTrigger(created.id(), true);
    assertThat(triggerService.getTriggerById(created.id()).isEnabled()).isTrue();
  }

  // -----------------------------------------------------------------------
  // updateTrigger — SCHEDULE 트리거 enabled/disabled 분기
  // -----------------------------------------------------------------------

  @Test
  void updateTrigger_scheduleTrigger_disabled_unregisters() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Update Schedule",
                TriggerType.SCHEDULE,
                null,
                Map.of("cron", "0 3 * * *")),
            testUserId);

    // isEnabled=false로 업데이트 → afterCommit에서 unregisterSchedule 경로
    triggerService.updateTrigger(
        created.id(),
        new UpdateTriggerRequest("Updated Schedule", false, null, Map.of("cron", "0 4 * * *")),
        testUserId);

    TriggerResponse updated = triggerService.getTriggerById(created.id());
    assertThat(updated.isEnabled()).isFalse();
  }

  // -----------------------------------------------------------------------
  // PIPELINE_CHAIN — missing upstreamPipelineId
  // -----------------------------------------------------------------------

  @Test
  void createPipelineChainTrigger_missingUpstreamId_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Bad Chain",
            TriggerType.PIPELINE_CHAIN,
            null,
            Map.of("condition", "SUCCESS")); // upstreamPipelineId 없음

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("upstreamPipelineId");
  }

  @Test
  void createPipelineChainTrigger_invalidCondition_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Bad Condition Chain",
            TriggerType.PIPELINE_CHAIN,
            null,
            Map.of("upstreamPipelineId", pipelineId2.intValue(), "condition", "INVALID"));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -----------------------------------------------------------------------
  // Helper
  // -----------------------------------------------------------------------

  /** TriggerService 내부와 동일한 HMAC-SHA256 계산 로직 */
  private String computeHmacSha256(String secret, String data) throws RuntimeException {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      javax.crypto.spec.SecretKeySpec keySpec =
          new javax.crypto.spec.SecretKeySpec(
              secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(keySpec);
      byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return "sha256=" + hex;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
