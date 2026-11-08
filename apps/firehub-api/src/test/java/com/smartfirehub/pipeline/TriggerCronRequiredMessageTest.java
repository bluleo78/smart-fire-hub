package com.smartfirehub.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 스케줄 트리거의 cron 누락 문구 한국어화(WD-569) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: SCHEDULE 트리거를 cron 없이 저장하면 400 은 났지만 응답 message 가 영어(「cron expression is required for
 * SCHEDULE trigger」)였고, 웹은 이를 토스트로 그대로 보여 줬다. 형식 오류(「유효하지 않은 cron 표현식입니다」)는 이미 한국어라 톤이 어긋났다. 이제 누락도
 * 400 + 한국어 message 로 거부한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청이 각자 커밋된다. 정리는 {@link #tearDown()}.
 */
@AutoConfigureMockMvc
class TriggerCronRequiredMessageTest extends IntegrationTestBase {

  private static final String CRON_REQUIRED_MESSAGE = "스케줄 트리거에는 cron 표현식이 필요합니다.";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long editor;
  private long pipelineId;

  @BeforeEach
  void setUp() throws Exception {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    editor = fx.createUser("wd569-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");

    MvcResult pipeline =
        mockMvc
            .perform(
                post("/api/v1/pipelines")
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new CreatePipelineRequest(
                                "wd569-" + System.nanoTime(), "WD-569", List.of()))))
            .andReturn();
    assertThat(pipeline.getResponse().getStatus()).isEqualTo(201);
    pipelineId = readTree(pipeline).get("id").asLong();
  }

  /** 실패 요청은 트리거를 남기지 않으므로 파이프라인(트리거는 cascade)·사용자만 지운다. */
  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    awaitSecurityAudit();
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                DEFAULT_TEST_TENANT_ID,
                () -> dsl.execute("delete from pipeline where created_by = ?", editor)),
        () -> fx.deleteUser(editor));
  }

  /** config 에 cron 키가 아예 없으면 400 + 한국어 문구. */
  @Test
  void createSchedule_withoutCron_returns400WithKoreanMessage() throws Exception {
    MvcResult r = createTrigger(Map.of("timezone", "UTC"));
    assertBadRequest(r, CRON_REQUIRED_MESSAGE);
  }

  /** cron 이 null 이어도 같은 문구. */
  @Test
  void createSchedule_nullCron_returns400WithKoreanMessage() throws Exception {
    Map<String, Object> config = new HashMap<>();
    config.put("cron", null);
    config.put("timezone", "UTC");
    MvcResult r = createTrigger(config);
    assertBadRequest(r, CRON_REQUIRED_MESSAGE);
  }

  /** 공백만 있는 cron 도 「누락」으로 보고 같은 문구(형식 오류 문구 뒤에 빈 값이 붙지 않게). */
  @Test
  void createSchedule_blankCron_returns400WithKoreanMessage() throws Exception {
    MvcResult r = createTrigger(Map.of("cron", "  ", "timezone", "UTC"));
    assertBadRequest(r, CRON_REQUIRED_MESSAGE);
  }

  /** 비교 기준: 형식이 틀린 cron 은 기존대로 「유효하지 않은 cron 표현식입니다」 로 시작하는 400. */
  @Test
  void createSchedule_invalidCron_keepsKoreanInvalidMessage() throws Exception {
    MvcResult r = createTrigger(Map.of("cron", "abc", "timezone", "UTC"));
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
    assertThat(readTree(r).get("message").asText()).startsWith("유효하지 않은 cron 표현식입니다");
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  private MvcResult createTrigger(Map<String, Object> config) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "cron 없는 스케줄");
    body.put("triggerType", "SCHEDULE");
    body.put("config", config);
    return mockMvc
        .perform(
            post("/api/v1/pipelines/" + pipelineId + "/triggers")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  /** 400 이고 응답 message 가 기대한 한국어 문장인지 본다 — 웹은 이 message 를 토스트로 그대로 보여 준다. */
  private void assertBadRequest(MvcResult r, String expectedMessage) throws Exception {
    assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
    assertThat(readTree(r).get("message").asText()).isEqualTo(expectedMessage);
  }

  private String body(MvcResult r) throws Exception {
    return "응답 본문: " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private JsonNode readTree(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
