package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * TriggerService 추가 통합 테스트 — 기존 테스트에서 커버되지 않은 분기. - WEBHOOK with secret
 * (encryptSecret/decryptSecret 경로) - verifyWebhookSignature (secret 있음 / 없음 / webhook 없음) -
 * findByWebhookId - fireTrigger: disabled trigger, null trigger (deleted) - DATASET_CHANGE 트리거 생성
 * 성공 - updateTrigger: PIPELINE_CHAIN 업스트림 변경 시 cycle 검사 - deleteTrigger: SCHEDULE 트리거 삭제
 * (afterCommit unregister 분기) - toggleTrigger: SCHEDULE 트리거 (enable/disable afterCommit 분기) -
 * validateScheduleConfig: 기본값 주입 (timezone, concurrencyPolicy) - getTriggerEvents 조회
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
  private Long watchedDatasetId;

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
    // WD-167: 트리거는 실행 주체(생성자)의 pipeline:execute 를 발화 시점에 확인한다 — 실행 권한을 준다
    TenantRlsTestSupport.grantAdminRole(dsl, testUserId);
    // 보안 등급(Task 3 D1): DATASET_CHANGE 감시 대상은 저장자가 볼 수 있어야 한다 — 테스트 사용자를 기본 테넌트 USER(기본=내부)
    // 멤버로 만들고, 감시 대상은 실제 데이터셋(기본 등급)으로 쓴다. 예전처럼 존재하지 않는 id(1,2,3)는 이제 저장이 거부된다.
    com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
        dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
        testUserId);
    watchedDatasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, "trigext_ds_" + System.nanoTime())
            .set(DATASET.TABLE_NAME, "trigext_ds_" + System.nanoTime())
            .set(DATASET.STORAGE_TYPE, "TABLE")
            .set(DATASET.ORIGIN_TYPE, "SOURCE")
            .set(DATASET.CREATED_BY, testUserId)
            .returning(DATASET.ID)
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
    // 응답에는 평문도 암호문도 없고 설정 여부만 있다(WD-220)
    assertThat(response.config()).doesNotContainKey("secret");
    assertThat(response.config()).doesNotContainKey("secretEncrypted");
    assertThat(response.config()).containsEntry("hasSecret", true);
    // 저장소에는 새 형식(v2 GCM) 암호문이 남는다
    assertThat(storedSecret(response.id())).startsWith("v2:");
  }

  // -----------------------------------------------------------------------
  // WD-220: 웹훅 시크릿 응답 마스킹 · 수정 시 보존 · 레거시 암호문 호환
  // -----------------------------------------------------------------------

  @Test
  void getTriggers_webhookWithSecret_hidesSecretEncrypted() {
    TriggerResponse created = createSignedWebhook("Masked Webhook");

    TriggerResponse listed =
        triggerService.getTriggers(pipelineId).stream()
            .filter(t -> t.id().equals(created.id()))
            .findFirst()
            .orElseThrow();
    TriggerResponse single = triggerService.getTriggerById(created.id());

    for (TriggerResponse r : List.of(listed, single)) {
      assertThat(r.config()).doesNotContainKeys("secretEncrypted", "secret");
      assertThat(r.config()).containsEntry("hasSecret", true);
      assertThat(r.config()).containsEntry("webhookId", created.config().get("webhookId"));
    }
  }

  @Test
  void getTriggers_webhookWithoutSecret_reportsHasSecretFalse() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Plain Webhook", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);

    assertThat(triggerService.getTriggerById(created.id()).config())
        .containsEntry("hasSecret", false)
        .doesNotContainKey("secretEncrypted");
  }

  @Test
  void updateTrigger_webhookEchoingResponseConfig_keepsSecretAndSignatureRequired() {
    TriggerResponse created = createSignedWebhook("Echo Webhook");
    String before = storedSecret(created.id());
    String webhookId = (String) created.config().get("webhookId");

    // 웹 편집 창처럼 조회 응답의 config(hasSecret 포함, 암호문 없음)를 그대로 되돌려 보낸다
    Map<String, Object> echoed = triggerService.getTriggerById(created.id()).config();
    triggerService.updateTrigger(
        created.id(),
        new UpdateTriggerRequest("Echo Webhook (renamed)", null, "edited", echoed),
        testUserId);

    assertThat(storedSecret(created.id())).isEqualTo(before);
    assertThat(storedConfigText(created.id())).doesNotContain("hasSecret");
    String body = "{\"event\":\"push\"}";
    assertThat(triggerService.verifyWebhookSignature(webhookId, body, "sha256=wrong")).isFalse();
    assertThat(triggerService.verifyWebhookSignature(webhookId, body, "")).isFalse();
    assertThat(
            triggerService.verifyWebhookSignature(
                webhookId, body, computeHmacSha256(SIGNED_SECRET, body)))
        .isTrue();
  }

  @Test
  void updateTrigger_webhookWithForgedServerKeys_ignoresThem() {
    TriggerResponse created = createSignedWebhook("Forged Webhook");
    String before = storedSecret(created.id());
    String webhookId = (String) created.config().get("webhookId");

    // 클라이언트가 암호문·웹훅 id·평문 시크릿을 직접 넣어도 저장값이 바뀌지 않는다
    triggerService.updateTrigger(
        created.id(),
        new UpdateTriggerRequest(
            null,
            null,
            null,
            Map.of(
                "secretEncrypted", "forged",
                "webhookId", "hijacked-id",
                "secret", "plain-text",
                "hasSecret", false)),
        testUserId);

    assertThat(storedSecret(created.id())).isEqualTo(before);
    assertThat(storedConfigText(created.id()))
        .contains(webhookId)
        .doesNotContain("hijacked-id")
        .doesNotContain("plain-text");
  }

  @Test
  void updateTrigger_webhookWithoutSecret_cannotPlantSecret() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("No Secret Plant", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);

    triggerService.updateTrigger(
        created.id(),
        new UpdateTriggerRequest(null, null, null, Map.of("secretEncrypted", "planted")),
        testUserId);

    assertThat(storedSecret(created.id())).isNull();
  }

  @Test
  void createWebhookTrigger_withClientSecretEncrypted_ignoresIt() {
    TriggerResponse created =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Planted On Create", TriggerType.WEBHOOK, null, Map.of("secretEncrypted", "x")),
            testUserId);

    assertThat(storedSecret(created.id())).isNull();
  }

  @Test
  void verifyWebhookSignature_legacyDefaultKeyCiphertext_stillVerifies() throws Exception {
    TriggerResponse created = createSignedWebhook("Legacy Webhook");
    String webhookId = (String) created.config().get("webhookId");
    // 운영에 남아 있는 옛 형식(기본 키 AES-ECB)으로 저장값을 바꿔 넣는다
    javax.crypto.Cipher ecb = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
    ecb.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(
            "SmartFireHub2026DefaultSecretKey".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "AES"));
    String legacy =
        java.util.Base64.getEncoder()
            .encodeToString(
                ecb.doFinal("legacy-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    dsl.execute(
        "update pipeline_trigger set config = jsonb_set(config, '{secretEncrypted}', to_jsonb(?::text)) where id = ?",
        legacy,
        created.id());

    String body = "{}";
    assertThat(
            triggerService.verifyWebhookSignature(
                webhookId, body, computeHmacSha256("legacy-secret", body)))
        .isTrue();
    assertThat(triggerService.verifyWebhookSignature(webhookId, body, "sha256=bad")).isFalse();
  }

  private static final String SIGNED_SECRET = "wd220-secret";

  private TriggerResponse createSignedWebhook(String name) {
    return triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest(name, TriggerType.WEBHOOK, null, Map.of("secret", SIGNED_SECRET)),
        testUserId);
  }

  /** 저장소에 실제로 남은 웹훅 암호문(응답은 마스킹되므로 DB 를 직접 본다). */
  private String storedSecret(Long triggerId) {
    Object v =
        dsl.fetchValue(
            "select config->>'secretEncrypted' from pipeline_trigger where id = ?", triggerId);
    return v == null ? null : v.toString();
  }

  private String storedConfigText(Long triggerId) {
    return String.valueOf(
        dsl.fetchValue("select config::text from pipeline_trigger where id = ?", triggerId));
  }

  @Test
  void createWebhookTrigger_emptySecret_doesNotEncrypt() {
    // secret이 빈 문자열이면 암호화하지 않는다
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Webhook Empty Secret", TriggerType.WEBHOOK, "No secret", Map.of("secret", ""));

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
                "Signed Webhook", TriggerType.WEBHOOK, null, Map.of("secret", "test-secret-key")),
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
                "Signed Webhook 2", TriggerType.WEBHOOK, null, Map.of("secret", "test-secret-key")),
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
            Map.of("datasetIds", List.of(watchedDatasetId)));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.triggerType()).isEqualTo("DATASET_CHANGE");
  }

  /**
   * pollingIntervalSeconds가 30 미만이면 서버 측에서 IllegalArgumentException을 던져야 한다 (refs #196). 클라이언트 검증을
   * 우회한 직접 API 호출에서도 400 차단을 보장한다.
   */
  @Test
  void createDatasetChangeTrigger_pollingIntervalBelowMin_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Too Short Polling",
            TriggerType.DATASET_CHANGE,
            null,
            Map.of("datasetIds", List.of(1), "pollingIntervalSeconds", 5, "debounceSeconds", 0));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(DatasetChangeTriggerConfig.POLLING_INTERVAL_MESSAGE);
  }

  /** pollingIntervalSeconds가 3600 초과이면 서버 측에서 IllegalArgumentException을 던져야 한다 (refs #196). */
  @Test
  void createDatasetChangeTrigger_pollingIntervalAboveMax_throwsException() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Too Long Polling",
            TriggerType.DATASET_CHANGE,
            null,
            Map.of("datasetIds", List.of(1), "pollingIntervalSeconds", 3601, "debounceSeconds", 0));

    assertThatThrownBy(() -> triggerService.createTrigger(pipelineId, request, testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(DatasetChangeTriggerConfig.POLLING_INTERVAL_MESSAGE);
  }

  /** pollingIntervalSeconds가 경계값(30초)이면 정상 생성되어야 한다 (refs #196). */
  @Test
  void createDatasetChangeTrigger_pollingIntervalAtMin_success() {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Min Polling",
            TriggerType.DATASET_CHANGE,
            null,
            Map.of(
                "datasetIds",
                List.of(watchedDatasetId),
                "pollingIntervalSeconds",
                30,
                "debounceSeconds",
                0));

    TriggerResponse response = triggerService.createTrigger(pipelineId, request, testUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.config()).containsEntry("pollingIntervalSeconds", 30);
  }

  // -----------------------------------------------------------------------
  // DATASET_CHANGE config 형식 검증·정규화(WD-225)
  // -----------------------------------------------------------------------

  /** 데이터 변경 트리거 생성 요청 — 감시 목록은 실제 데이터셋, 나머지 키는 인자로. null 값을 넣기 위해 HashMap 을 쓴다. */
  private CreateTriggerRequest datasetChangeRequest(String name, Object... extraKeyValues) {
    Map<String, Object> config = new java.util.HashMap<>();
    config.put("datasetIds", List.of(watchedDatasetId));
    for (int i = 0; i < extraKeyValues.length; i += 2) {
      config.put((String) extraKeyValues[i], extraKeyValues[i + 1]);
    }
    return new CreateTriggerRequest(name, TriggerType.DATASET_CHANGE, null, config);
  }

  /** 숫자 아님·소수·int 넘침(2^32+60 이 예전엔 60 으로 잘려 통과)은 500 이나 통과가 아니라 400 한국어로 거부된다. */
  @Test
  void createDatasetChangeTrigger_invalidPollingInterval_rejectedInKorean() {
    for (Object bad : List.<Object>of("abc", "", 30.9, 4294967356L, "4294967356", true, 29, 3601)) {
      assertThatThrownBy(
              () ->
                  triggerService.createTrigger(
                      pipelineId,
                      datasetChangeRequest("bad-polling", "pollingIntervalSeconds", bad),
                      testUserId))
          .as("pollingIntervalSeconds=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(DatasetChangeTriggerConfig.POLLING_INTERVAL_MESSAGE);
    }
  }

  @Test
  void createDatasetChangeTrigger_invalidDebounce_rejectedInKorean() {
    for (Object bad : List.<Object>of("abc", 1.5, -1, 3601, 4294967296L)) {
      assertThatThrownBy(
              () ->
                  triggerService.createTrigger(
                      pipelineId,
                      datasetChangeRequest("bad-debounce", "debounceSeconds", bad),
                      testUserId))
          .as("debounceSeconds=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(DatasetChangeTriggerConfig.DEBOUNCE_MESSAGE);
    }
  }

  /** 정수 문자열·소수부 0 실수는 받아 정수로 저장한다 — 조회·폴러가 저장 검증과 같은 값을 보게. */
  @Test
  void createDatasetChangeTrigger_numericStringAndWholeDouble_normalizedToInteger() {
    TriggerResponse response =
        triggerService.createTrigger(
            pipelineId,
            datasetChangeRequest(
                "normalized", "pollingIntervalSeconds", " 60 ", "debounceSeconds", 5.0),
            testUserId);

    TriggerResponse stored = triggerService.getTriggerById(response.id());
    assertThat(stored.config()).containsEntry("pollingIntervalSeconds", 60);
    assertThat(stored.config()).containsEntry("debounceSeconds", 5);
  }

  /** null 은 키 없음과 같이 기본값으로 보고, 저장 config 에서 키를 지운다(예전엔 NPE 500). */
  @Test
  void createDatasetChangeTrigger_nullValues_treatedAsDefault() {
    TriggerResponse response =
        triggerService.createTrigger(
            pipelineId,
            datasetChangeRequest("nulls", "pollingIntervalSeconds", null, "debounceSeconds", null),
            testUserId);

    TriggerResponse stored = triggerService.getTriggerById(response.id());
    assertThat(stored.config()).doesNotContainKeys("pollingIntervalSeconds", "debounceSeconds");
  }

  @Test
  void createDatasetChangeTrigger_missingOrEmptyOrNonListDatasetIds_rejectedInKorean() {
    for (Object bad : java.util.Arrays.asList(null, List.of(), "1")) {
      Map<String, Object> config = new java.util.HashMap<>();
      if (bad != null) {
        config.put("datasetIds", bad);
      }
      assertThatThrownBy(
              () ->
                  triggerService.createTrigger(
                      pipelineId,
                      new CreateTriggerRequest("ids", TriggerType.DATASET_CHANGE, null, config),
                      testUserId))
          .as("datasetIds=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(DatasetChangeTriggerConfig.DATASET_IDS_REQUIRED_MESSAGE);
    }
  }

  /** 감시 목록 원소가 숫자가 아니면 400 한국어(VIEW 판정 403 보다 먼저), 숫자 문자열은 id 로 정규화한다. */
  @Test
  void createDatasetChangeTrigger_datasetIdElements_validatedAndNormalized() {
    for (Object bad : java.util.Arrays.asList("abc", 1.5, null)) {
      Map<String, Object> config = new java.util.HashMap<>();
      config.put("datasetIds", java.util.Arrays.asList(watchedDatasetId, bad));
      assertThatThrownBy(
              () ->
                  triggerService.createTrigger(
                      pipelineId,
                      new CreateTriggerRequest("ids", TriggerType.DATASET_CHANGE, null, config),
                      testUserId))
          .as("element=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(DatasetChangeTriggerConfig.DATASET_ID_FORMAT_MESSAGE);
    }

    TriggerResponse response =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "string-id",
                TriggerType.DATASET_CHANGE,
                null,
                Map.of("datasetIds", List.of(String.valueOf(watchedDatasetId)))),
            testUserId);
    List<?> storedIds =
        (List<?>) triggerService.getTriggerById(response.id()).config().get("datasetIds");
    assertThat(storedIds).hasSize(1);
    assertThat(((Number) storedIds.get(0)).longValue()).isEqualTo(watchedDatasetId);
    assertThat(storedIds.get(0)).isInstanceOf(Number.class);
  }

  /** 수정 경로도 같은 규칙 — 문자열·null 은 500 이 아니라 400 한국어 또는 정규화 저장. */
  @Test
  void updateDatasetChangeTrigger_configValues_validatedAndNormalized() {
    TriggerResponse created =
        triggerService.createTrigger(pipelineId, datasetChangeRequest("upd"), testUserId);

    for (Object bad : List.<Object>of("abc", 30.9, 4294967356L, 10)) {
      Map<String, Object> config = new java.util.HashMap<>();
      config.put("datasetIds", List.of(watchedDatasetId));
      config.put("pollingIntervalSeconds", bad);
      assertThatThrownBy(
              () ->
                  triggerService.updateTrigger(
                      created.id(),
                      new UpdateTriggerRequest("upd", null, null, config),
                      testUserId))
          .as("pollingIntervalSeconds=%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(DatasetChangeTriggerConfig.POLLING_INTERVAL_MESSAGE);
    }
    Map<String, Object> badDebounce = new java.util.HashMap<>();
    badDebounce.put("datasetIds", List.of(watchedDatasetId));
    badDebounce.put("debounceSeconds", "x");
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(),
                    new UpdateTriggerRequest("upd", null, null, badDebounce),
                    testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(DatasetChangeTriggerConfig.DEBOUNCE_MESSAGE);
    Map<String, Object> badIds = new java.util.HashMap<>();
    badIds.put("datasetIds", List.of("abc"));
    assertThatThrownBy(
            () ->
                triggerService.updateTrigger(
                    created.id(), new UpdateTriggerRequest("upd", null, null, badIds), testUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(DatasetChangeTriggerConfig.DATASET_ID_FORMAT_MESSAGE);

    Map<String, Object> ok = new java.util.HashMap<>();
    ok.put("datasetIds", List.of(watchedDatasetId));
    ok.put("pollingIntervalSeconds", "120");
    ok.put("debounceSeconds", null);
    triggerService.updateTrigger(
        created.id(), new UpdateTriggerRequest("upd", null, null, ok), testUserId);

    TriggerResponse stored = triggerService.getTriggerById(created.id());
    assertThat(stored.config()).containsEntry("pollingIntervalSeconds", 120);
    assertThat(stored.config()).doesNotContainKey("debounceSeconds");
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
            Map.of("cron", "0 0 * * *")); // timezone/concurrencyPolicy 없음

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

    // disabled 상태에서 fire → 이벤트 기록 없이 스킵하되, 결과로는 "꺼져 있음"을 알려 준다(WD-152)
    TriggerFireResult result = triggerService.fireTrigger(created.id(), Map.of());
    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.DISABLED);
    assertThat(result.reason()).isEqualTo("트리거가 꺼져 있어 실행하지 않았습니다");

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
    TriggerFireResult result = triggerService.fireTrigger(created.id(), Map.of("source", "test"));

    // 발화 결과의 실행 id 가 FIRED 이벤트의 실행 id 와 같아야 외부 호출자에게 돌려준 executionId 를 믿을 수 있다(WD-152)
    assertThat(result.outcome()).isEqualTo(TriggerFireResult.Outcome.FIRED);
    assertThat(result.executionId()).isNotNull();
    assertThat(result.reason()).isNull();
    List<TriggerEventResponse> events = triggerService.getTriggerEvents(pipelineId, 10);
    assertThat(events)
        .anyMatch(
            e -> "FIRED".equals(e.eventType()) && result.executionId().equals(e.executionId()));
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
        created.id(), new UpdateTriggerRequest("Updated Name", null, "new desc", null), testUserId);

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
                "Delete Schedule", TriggerType.SCHEDULE, null, Map.of("cron", "0 1 * * *")),
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
                "Toggle Schedule", TriggerType.SCHEDULE, null, Map.of("cron", "0 2 * * *")),
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
                "Update Schedule", TriggerType.SCHEDULE, null, Map.of("cron", "0 3 * * *")),
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
