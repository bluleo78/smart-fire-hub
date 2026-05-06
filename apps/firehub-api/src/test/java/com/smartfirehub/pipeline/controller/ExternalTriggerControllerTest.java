package com.smartfirehub.pipeline.controller;

import static com.smartfirehub.jooq.Tables.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ExternalTriggerControllerTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;

  @Autowired private TriggerService triggerService;

  @Autowired private PipelineService pipelineService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long pipelineId;

  @BeforeEach
  void setUp() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "ext_trig_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Ext Trigger User " + unique)
            .set(USER.EMAIL, "ext_trig_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest("Ext Pipeline " + unique, "Description", List.of()),
            testUserId);
    pipelineId = pipeline.id();
  }

  @Test
  void apiTrigger_validToken_triggersExecution() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Test", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"))
        .andExpect(jsonPath("$.pipelineId").value(pipelineId.intValue()));
  }

  @Test
  void apiTrigger_invalidToken_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/triggers/api/invalid-token-value")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid token"));
  }

  @Test
  void webhookTrigger_validWebhookId_triggersExecution() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Webhook Test", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);
    String webhookId = (String) trigger.config().get("webhookId");

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"));
  }

  @Test
  void webhookTrigger_invalidWebhookId_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/non-existent-webhook-id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void apiTrigger_withAllowedIps_blockedFromUnmatchedIp_returnsForbidden() throws Exception {
    // allowedIps에 원격 IP(127.0.0.1)가 포함되지 않는 대역으로 트리거 생성
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "IP 제한 API 트리거",
                TriggerType.API,
                null,
                Map.of("allowedIps", List.of("10.0.0.0/24"))),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    // MockMvc 요청은 remoteAddr=127.0.0.1 → 10.0.0.0/24 대역 밖 → 403 기대
    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("IP not allowed"));
  }

  @Test
  void apiTrigger_withAllowedIps_allowedFromMatchingIp_returnsOk() throws Exception {
    // 127.0.0.1이 포함된 CIDR로 트리거 생성 → 허용되어야 함
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "IP 허용 API 트리거",
                TriggerType.API,
                null,
                Map.of("allowedIps", List.of("127.0.0.0/8"))),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"));
  }

  @Test
  void apiTrigger_withEmptyAllowedIps_allowsAnyIp() throws Exception {
    // allowedIps가 빈 목록이면 모든 IP 허용
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "IP 무제한 API 트리거", TriggerType.API, null, Map.of("allowedIps", List.of())),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"));
  }

  @Test
  void createTrigger_withInvalidIpFormat_returnsBadRequest() throws Exception {
    // 잘못된 IP 형식으로 트리거 생성 시 400 응답 기대
    // TriggerService.createTrigger가 IllegalArgumentException → GlobalExceptionHandler → 400
    try {
      triggerService.createTrigger(
          pipelineId,
          new CreateTriggerRequest(
              "잘못된 IP 트리거", TriggerType.API, null, Map.of("allowedIps", List.of("invalid-ip"))),
          testUserId);
      throw new AssertionError("IllegalArgumentException 이 발생해야 합니다");
    } catch (IllegalArgumentException e) {
      org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("잘못된 IP 주소 형식");
    }
  }

  @Test
  void apiTrigger_withAllowedIps_loopbackNormalized_allowsLocalhost() throws Exception {
    // ::1 (IPv6 루프백)과 127.0.0.1은 동일하게 취급되어야 함
    // MockMvc는 127.0.0.1로 전달되므로, 127.0.0.1/32 허용 시 통과해야 함
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "로컬 IP 트리거", TriggerType.API, null, Map.of("allowedIps", List.of("127.0.0.1/32"))),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"));
  }

  @Test
  void webhookTrigger_withSecret_missingSignature_returnsUnauthorized() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "Secure Webhook", TriggerType.WEBHOOK, null, Map.of("secret", "my-secret-key")),
            testUserId);
    String webhookId = (String) trigger.config().get("webhookId");

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Missing X-Hub-Signature header"));
  }
}
