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
