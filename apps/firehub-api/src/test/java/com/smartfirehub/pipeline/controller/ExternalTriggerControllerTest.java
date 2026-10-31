package com.smartfirehub.pipeline.controller;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
  private String pipelineName;

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
    TenantRlsTestSupport.insertActiveMembership(dsl, testUserId, DEFAULT_TEST_TENANT_ID);
    // WD-167: 트리거는 실행 주체(생성자)의 pipeline:execute 를 발화 시점에 확인한다 — 실행 권한을 준다
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> TenantRlsTestSupport.grantAdminRole(dsl, testUserId));

    pipelineName = "Ext Pipeline " + unique;
    PipelineDetailResponse pipeline =
        pipelineService.createPipeline(
            new CreatePipelineRequest(pipelineName, "Description", List.of()), testUserId);
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
        .andExpect(jsonPath("$.pipelineId").value(pipelineId.intValue()))
        .andExpect(jsonPath("$.triggerId").value(trigger.id().intValue()))
        // 실제로 시작한 실행의 id 를 돌려준다(WD-152)
        .andExpect(jsonPath("$.executionId").isNumber());
  }

  /**
   * 회귀 테스트(WD-152): 파이프라인이 비활성이면 실행하지 않았으므로 200 「triggered」가 아니라 409 「skipped」+한국어 사유여야 한다. 예전에는
   * 결과와 무관하게 200 을 돌려줘 외부 시스템이 실행됐다고 믿었다. 이벤트 로그에도 같은 한국어 사유가 남아야 한다.
   */
  @Test
  void apiTrigger_inactivePipeline_returnsConflictWithKoreanReason() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Inactive", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");
    deactivatePipeline();

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value("파이프라인이 비활성 상태라 실행하지 않았습니다"));

    assertSkippedEventWithReason(trigger.id(), "파이프라인이 비활성 상태라 실행하지 않았습니다");
  }

  /**
   * 회귀 테스트(WD-152): 꺼진 API 트리거를 맞는 토큰으로 부르면 "토큰이 틀렸다"(401)가 아니라 409 「트리거가 꺼져 있어 실행하지 않았습니다」다. 꺼진
   * 트리거 발화는 기존대로 이벤트를 남기지 않는다.
   */
  @Test
  void apiTrigger_disabledTrigger_returnsConflict() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Disabled", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");
    triggerService.toggleTrigger(trigger.id(), false);

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value("트리거가 꺼져 있어 실행하지 않았습니다"));

    assertThat(eventsOf(trigger.id())).isEmpty();
  }

  /** 꺼진 API 트리거라도 IP 제한이 먼저다 — 허용되지 않은 IP 에는 "꺼져 있음"을 알려 주지 않고 기존대로 403 이다. */
  @Test
  void apiTrigger_disabledTrigger_fromBlockedIp_returnsForbidden() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "API Disabled IP",
                TriggerType.API,
                null,
                Map.of("allowedIps", List.of("10.0.0.0/24"))),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");
    triggerService.toggleTrigger(trigger.id(), false);

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("IP not allowed"));
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

  /**
   * 회귀 테스트(WD-243): trigger:write 사용자가 남의 API 트리거 수정 config 에 자기 토큰의 해시를 넣어도 저장되지 않는다. 공격자 토큰은 401,
   * 원래 토큰은 계속 실행된다. 예전에는 해시가 그대로 바뀌어 공격자 토큰이 생성자 신원으로 실행됐고 원래 토큰은 401 이었다.
   */
  @Test
  void apiTrigger_updateWithForgedTokenHash_attackerTokenUnauthorized_originalStillWorks()
      throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Forged", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");
    String attackerToken = "attacker-token-" + UUID.randomUUID();
    String attackerHash =
        java.util.Base64.getEncoder()
            .encodeToString(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(attackerToken.getBytes(StandardCharsets.UTF_8)));

    triggerService.updateTrigger(
        pipelineId,
        trigger.id(),
        new UpdateTriggerRequest(
            "API Forged", null, null, Map.of("allowedIps", List.of(), "tokenHash", attackerHash)),
        testUserId);

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + attackerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.triggerId").value(trigger.id().intValue()));
  }

  /** 회귀 테스트(WD-243): config 에 allowedIps 만 보내 저장해도 토큰 해시가 지워지지 않아 원래 토큰이 계속 동작한다. */
  @Test
  void apiTrigger_updateConfigOnlyAllowedIps_originalTokenStillWorks() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Config Only", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    triggerService.updateTrigger(
        pipelineId,
        trigger.id(),
        new UpdateTriggerRequest(
            "API Config Only", null, null, Map.of("allowedIps", List.of("127.0.0.1"))),
        testUserId);

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.triggerId").value(trigger.id().intValue()));
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
        .andExpect(jsonPath("$.status").value("triggered"))
        .andExpect(jsonPath("$.executionId").isNumber());
  }

  /** 회귀 테스트(WD-152): 웹훅도 파이프라인이 비활성이면 409 「skipped」+한국어 사유이고, 이벤트 로그에 같은 사유가 남는다. */
  @Test
  void webhookTrigger_inactivePipeline_returnsConflictWithKoreanReason() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Webhook Inactive", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);
    String webhookId = (String) trigger.config().get("webhookId");
    deactivatePipeline();

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value("파이프라인이 비활성 상태라 실행하지 않았습니다"));

    assertSkippedEventWithReason(trigger.id(), "파이프라인이 비활성 상태라 실행하지 않았습니다");
  }

  /**
   * 꺼진 웹훅(시크릿 없음)은 409 「트리거가 꺼져 있어 실행하지 않았습니다」다(WD-152).
   *
   * <p>이 계약은 P2-b 의 "꺼진 웹훅은 404"를 대체한다. 그때는 해석 함수(V95)가 꺼진 트리거를 아예 해석하지 않아 404 가 났는데, 그러면 맞는 웹훅 id
   * 를 가진 호출자도 "웹훅이 없다"는 말만 듣고 꺼져 있다는 것을 알 수 없었다. 시크릿이 없는 웹훅은 웹훅 id 자체가 자격이므로 꺼짐을 알려 준다.
   */
  @Test
  void webhookTrigger_disabledTrigger_returnsConflict() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("Webhook Disabled", TriggerType.WEBHOOK, null, Map.of()),
            testUserId);
    String webhookId = (String) trigger.config().get("webhookId");
    triggerService.toggleTrigger(trigger.id(), false);

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value("트리거가 꺼져 있어 실행하지 않았습니다"));

    assertThat(eventsOf(trigger.id())).isEmpty();
  }

  /** 시크릿이 있는 꺼진 웹훅은 서명이 맞을 때만 409 로 꺼짐을 알린다(WD-152). */
  @Test
  void webhookTrigger_disabledWithSecret_validSignature_returnsConflict() throws Exception {
    TriggerResponse trigger = createSecretWebhook("Secure Disabled Webhook");
    String webhookId = (String) trigger.config().get("webhookId");
    triggerService.toggleTrigger(trigger.id(), false);
    String body = "{\"data\": \"test\"}";

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature", sign(body))
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("skipped"))
        .andExpect(jsonPath("$.reason").value("트리거가 꺼져 있어 실행하지 않았습니다"));
  }

  /**
   * 시크릿이 있는 꺼진 웹훅에 서명이 없거나 틀리면 WD-152 이전과 똑같이 404 다 — 서명 통과 전에는 꺼짐은 물론 웹훅의 존재도 드러내지 않는다. 401 을 주면
   * "이 id 의 웹훅이 있다"는 사실이 서명 없이 새어 나간다.
   */
  @Test
  void webhookTrigger_disabledWithSecret_missingOrInvalidSignature_returnsNotFound()
      throws Exception {
    TriggerResponse trigger = createSecretWebhook("Secure Disabled Webhook 2");
    String webhookId = (String) trigger.config().get("webhookId");
    triggerService.toggleTrigger(trigger.id(), false);

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Webhook not found"));

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature", "sha256=deadbeef")
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Webhook not found"));
  }

  /** 켜진 웹훅의 서명 불일치는 기존대로 401 이다. */
  @Test
  void webhookTrigger_withSecret_invalidSignature_returnsUnauthorized() throws Exception {
    TriggerResponse trigger = createSecretWebhook("Secure Webhook Invalid Sig");
    String webhookId = (String) trigger.config().get("webhookId");

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature", "sha256=deadbeef")
                .content("{\"data\": \"test\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid signature"));
  }

  /** 켜진 웹훅에 맞는 서명이면 실행되고 200 이다 — 서명 헬퍼가 실제 검증과 같은 형식인지도 함께 고정한다. */
  @Test
  void webhookTrigger_withSecret_validSignature_triggersExecution() throws Exception {
    TriggerResponse trigger = createSecretWebhook("Secure Webhook Valid Sig");
    String webhookId = (String) trigger.config().get("webhookId");
    String body = "{\"data\": \"test\"}";

    mockMvc
        .perform(
            post("/api/v1/triggers/webhook/" + webhookId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature", sign(body))
                .content(body))
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

  /** 허용 IP 가 203.0.113.0/24 인 API 트리거를 만들고 원본 토큰을 돌려준다(WD-402 테스트용). */
  private String createIpRestrictedApiTrigger(String name) {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                name, TriggerType.API, null, Map.of("allowedIps", List.of("203.0.113.0/24"))),
            testUserId);
    return (String) trigger.config().get("rawToken");
  }

  /** 회귀 테스트(WD-402): 프록시가 아닌 곳에서 X-Forwarded-For 에 허용 IP 를 적어 보내도 헤더를 무시하고 403 이다. */
  @Test
  void apiTrigger_spoofedForwardedForFromUntrustedRemote_returnsForbidden() throws Exception {
    String rawToken = createIpRestrictedApiTrigger("XFF 위조 직접 접속");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Forwarded-For", "203.0.113.5")
                .with(
                    req -> {
                      req.setRemoteAddr("198.51.100.9"); // 신뢰 프록시가 아닌 직접 접속
                      return req;
                    }))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("IP not allowed"));
  }

  /** WD-402: 신뢰 프록시(루프백 nginx)를 거쳐 실제 허용 IP 에서 온 요청은 헤더의 실제 주소로 판정해 통과한다. */
  @Test
  void apiTrigger_allowedIpThroughTrustedProxy_returnsOk() throws Exception {
    String rawToken = createIpRestrictedApiTrigger("XFF 신뢰 프록시 허용");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Forwarded-For", "203.0.113.5")
                .with(
                    req -> {
                      req.setRemoteAddr("127.0.0.1");
                      return req;
                    }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"));
  }

  /**
   * 회귀 테스트(WD-402): 클라이언트가 허용 IP 를 앞에 끼워 보내면 nginx 는 그 뒤에 실제 주소를 덧붙인다({@code 허용IP, 실제IP}). 오른쪽의 실제
   * 주소로 판정해 403 이다.
   */
  @Test
  void apiTrigger_spoofedPrefixThroughTrustedProxy_returnsForbidden() throws Exception {
    String rawToken = createIpRestrictedApiTrigger("XFF 위조 프록시 경유");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Forwarded-For", "203.0.113.5, 198.51.100.9")
                .with(
                    req -> {
                      req.setRemoteAddr("127.0.0.1");
                      return req;
                    }))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("IP not allowed"));
  }

  /**
   * 회귀 테스트(WD-402): 내부망 공격자(10.9.9.9)가 허용된 사내 IP(10.1.2.3)를 헤더에 적으면 nginx 가 {@code 10.1.2.3,
   * 10.9.9.9} 로 만든다. 전부 사설(신뢰) 대역이어도 맨 왼쪽이 아니라 맨 오른쪽(실제 접속 주소)으로 판정해 403 이다.
   */
  @Test
  void apiTrigger_intranetSpoofThroughTrustedProxy_returnsForbidden() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest(
                "XFF 내부망 위조", TriggerType.API, null, Map.of("allowedIps", List.of("10.1.2.3/32"))),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Forwarded-For", "10.1.2.3, 10.9.9.9")
                .with(
                    req -> {
                      req.setRemoteAddr("172.18.0.3"); // nginx 컨테이너
                      return req;
                    }))
        .andExpect(status().isForbidden());

    // 같은 트리거에 실제 10.1.2.3 이 nginx 를 거쳐 오면 통과한다(정상 사내 호출은 깨지지 않는다)
    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Forwarded-For", "10.1.2.3")
                .with(
                    req -> {
                      req.setRemoteAddr("172.18.0.3");
                      return req;
                    }))
        .andExpect(status().isOk());
  }

  /** WD-402: 신뢰 프록시 뒤에서 해석되지 않는 값이 오면 허용 목록과 맞지 않아 403 이다(우회 불가). */
  @Test
  void apiTrigger_garbageForwardedForThroughTrustedProxy_returnsForbidden() throws Exception {
    String rawToken = createIpRestrictedApiTrigger("XFF 쓰레기 값");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("X-Forwarded-For", "203.0.113.5, not-an-ip")
                .with(
                    req -> {
                      req.setRemoteAddr("127.0.0.1");
                      return req;
                    }))
        .andExpect(status().isForbidden());
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

  /** 테스트 시크릿. 웹훅 생성과 서명 계산이 같은 값을 쓴다. */
  private static final String WEBHOOK_SECRET = "my-secret-key";

  /** 시크릿이 설정된 웹훅 트리거를 만든다. */
  private TriggerResponse createSecretWebhook(String name) {
    return triggerService.createTrigger(
        pipelineId,
        new CreateTriggerRequest(name, TriggerType.WEBHOOK, null, Map.of("secret", WEBHOOK_SECRET)),
        testUserId);
  }

  /** 서버 검증과 같은 형식("sha256=" + 소문자 hex HMAC-SHA256)으로 본문 서명을 만든다. */
  private static String sign(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  /** 테스트 파이프라인을 비활성으로 바꾼다. */
  private void deactivatePipeline() {
    pipelineService.updatePipeline(
        pipelineId,
        new UpdatePipelineRequest(pipelineName, "Description", false, null),
        testUserId);
  }

  /**
   * 한 트리거의 이벤트만 추린다 — 같은 파이프라인의 다른 트리거 이벤트와 섞이지 않게 한다. 컨트롤러가 요청 처리 뒤 테넌트 컨텍스트를 비우므로 MockMvc 호출 뒤의
   * 조회는 테넌트 픽스처 안에서 한다(RLS 아래에서 컨텍스트 없이 읽으면 0행이다).
   */
  private List<TriggerEventResponse> eventsOf(Long triggerId) {
    return inTenantFixture(
        () ->
            triggerService.getTriggerEvents(pipelineId, 50).stream()
                .filter(e -> triggerId.equals(e.triggerId()))
                .toList());
  }

  /** 트리거의 건너뜀 이벤트 사유가 기대한 한국어 문구인지 확인한다. */
  private void assertSkippedEventWithReason(Long triggerId, String reason) {
    assertThat(eventsOf(triggerId))
        .anyMatch(e -> "SKIPPED".equals(e.eventType()) && reason.equals(e.detail().get("reason")));
  }

  /**
   * 회귀 테스트(WD-403): 상한(16KB)을 넘는 API 트리거 본문은 실행은 그대로 하되, 이벤트에는 본문 대신 크기·키 요약만 남긴다. 예전에는 5MB 본문이
   * trigger_event.detail 에 그대로 쌓여 「트리거」 탭이 열 때마다 수십 MB 를 내려받았다.
   */
  @Test
  @SuppressWarnings("unchecked")
  void apiTrigger_largePayload_firesButStoresOnlySummary() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Big", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");
    String big = "x".repeat(ExternalTriggerController.MAX_STORED_PAYLOAD_BYTES + 1);

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + big + "\",\"kind\":\"bulk\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("triggered"));

    TriggerEventResponse fired =
        eventsOf(trigger.id()).stream()
            .filter(e -> "FIRED".equals(e.eventType()))
            .findFirst()
            .orElseThrow();
    Map<String, Object> detail = fired.detail();
    assertThat(detail).doesNotContainKey("note");
    assertThat(detail.get("payloadTruncated")).isEqualTo(true);
    assertThat(((Number) detail.get("payloadSize")).intValue())
        .isGreaterThan(ExternalTriggerController.MAX_STORED_PAYLOAD_BYTES);
    assertThat(((Number) detail.get("payloadKeyCount")).intValue()).isEqualTo(2);
    assertThat((List<Object>) detail.get("payloadKeys")).containsExactlyInAnyOrder("note", "kind");
    // 호출 IP 는 요약이어도 남는다
    assertThat(detail).containsKey("sourceIp");
  }

  /** 상한 이하 본문은 지금처럼 이벤트에 그대로 남는다(WD-403). */
  @Test
  void apiTrigger_smallPayload_storedAsIs() throws Exception {
    TriggerResponse trigger =
        triggerService.createTrigger(
            pipelineId,
            new CreateTriggerRequest("API Small", TriggerType.API, null, Map.of()),
            testUserId);
    String rawToken = (String) trigger.config().get("rawToken");

    mockMvc
        .perform(
            post("/api/v1/triggers/api/" + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":42}"))
        .andExpect(status().isOk());

    Map<String, Object> detail =
        eventsOf(trigger.id()).stream()
            .filter(e -> "FIRED".equals(e.eventType()))
            .findFirst()
            .orElseThrow()
            .detail();
    assertThat(((Number) detail.get("orderId")).intValue()).isEqualTo(42);
    assertThat(detail).doesNotContainKey("payloadTruncated");
  }
}
