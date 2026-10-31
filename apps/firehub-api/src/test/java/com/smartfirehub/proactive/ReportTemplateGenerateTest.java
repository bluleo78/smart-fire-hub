package com.smartfirehub.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.service.ReportTemplateAiClient;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-455: 목표 기반 리포트 양식 섹션 생성 API({@code POST /api/v1/proactive/templates/generate}).
 *
 * <p>ai-agent 클라이언트를 목으로 두고, AI 결과가 저장 때와 같은 섹션 검사(WD-298)를 통과해야만 200 으로 돌아가고, 규칙 위반·빈 결과·ai-agent
 * 실패는 502 로 알려 화면이 고정 구성으로 대신하게 되는지 검증한다. 성공 결과는 그대로 양식 저장(POST /templates)에도 통과해야 한다.
 */
@AutoConfigureMockMvc
class ReportTemplateGenerateTest extends IntegrationTestBase {

  private static final String URL = "/api/v1/proactive/templates/generate";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;

  @MockitoBean private ReportTemplateAiClient aiClient;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private String writerToken;
  private String readerToken;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    writerToken = token(user("proactive:read", "proactive:write"));
    readerToken = token(user("proactive:read"));
  }

  /** 주어진 권한만 가진 사용자(기본 USER 역할은 뗀다). */
  private long user(String... permissions) {
    long uid = fx.createUser("rtg_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("rtg_r_" + System.nanoTime(), fx.levelId("공개"), permissions);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "rtg" + uid, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("report_template")))
                .where(field(name("user_id"), Long.class).in(users))
                .execute());
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private MockHttpServletResponse send(String url, String token, String body) throws Exception {
    return mockMvc
        .perform(
            post(url)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse generate(String token, String question) throws Exception {
    return send(URL, token, om.writeValueAsString(Map.of("question", question)));
  }

  private static Map<String, Object> section(String key, String type, String label) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("key", key);
    s.put("type", type);
    s.put("label", label);
    s.put("instruction", label + "을(를) 작성하세요.");
    return s;
  }

  @Test
  void generate_validAiOutput_returnsSanitizedSections_thatSaveAsTemplate() throws Exception {
    Map<String, Object> first = section("summary", "text", "요약");
    first.put("required", true);
    // 모델이 끼워 넣은 필드(static·content)는 응답에서 빠져야 한다
    first.put("static", true);
    first.put("content", "고정 텍스트");
    when(aiClient.generateSections(anyLong(), eq("이번 달 매출 하락 원인")))
        .thenReturn(
            List.of(
                first,
                section("sales_trend", "chart", "매출 추이"),
                section("next_steps", "recommendation", "권고 조치")));

    MockHttpServletResponse res = generate(writerToken, "  이번 달 매출 하락 원인  ");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    JsonNode sections = om.readTree(res.getContentAsString()).path("sections");
    assertThat(sections).hasSize(3);
    assertThat(sections.get(0).path("key").asText()).isEqualTo("summary");
    assertThat(sections.get(0).path("required").asBoolean()).isTrue();
    assertThat(sections.get(0).has("static")).isFalse();
    assertThat(sections.get(0).has("content")).isFalse();
    assertThat(sections.get(1).path("type").asText()).isEqualTo("chart");
    assertThat(sections.get(2).path("instruction").asText()).isEqualTo("권고 조치을(를) 작성하세요.");

    // 같은 검사기를 거쳤으므로 그대로 양식으로 저장된다(WD-297 저장 흐름에서 다시 거절되지 않는다)
    String saveBody =
        om.writeValueAsString(
            Map.of("name", "rtg 양식", "sections", om.treeToValue(sections, List.class)));
    assertThat(send("/api/v1/proactive/templates", writerToken, saveBody).getStatus())
        .isEqualTo(201);
  }

  @Test
  void generate_duplicateKeys_returns502() throws Exception {
    when(aiClient.generateSections(anyLong(), anyString()))
        .thenReturn(List.of(section("summary", "text", "요약"), section("summary", "list", "중복")));
    MockHttpServletResponse res = generate(writerToken, "주간 화재 추이");
    assertThat(res.getStatus()).isEqualTo(502);
    assertThat(om.readTree(res.getContentAsString()).path("message").asText())
        .contains("양식 규칙에 맞지 않습니다");
  }

  @Test
  void generate_unknownTypeOrBadKey_returns502() throws Exception {
    when(aiClient.generateSections(anyLong(), anyString()))
        .thenReturn(List.of(section("summary", "pie", "요약")));
    assertThat(generate(writerToken, "주간 화재 추이").getStatus()).isEqualTo(502);

    when(aiClient.generateSections(anyLong(), anyString()))
        .thenReturn(List.of(section("Summary-1", "text", "요약")));
    assertThat(generate(writerToken, "주간 화재 추이").getStatus()).isEqualTo(502);
  }

  @Test
  void generate_emptyOutput_returns502() throws Exception {
    when(aiClient.generateSections(anyLong(), anyString())).thenReturn(List.of());
    assertThat(generate(writerToken, "주간 화재 추이").getStatus()).isEqualTo(502);
  }

  @Test
  void generate_aiAgentFailure_returns502WithKoreanMessage() throws Exception {
    when(aiClient.generateSections(anyLong(), anyString()))
        .thenThrow(new ExternalServiceException("AI 섹션 구성 생성에 실패했습니다. 잠시 후 다시 시도해 주세요."));
    MockHttpServletResponse res = generate(writerToken, "주간 화재 추이");
    assertThat(res.getStatus()).isEqualTo(502);
    assertThat(om.readTree(res.getContentAsString()).path("message").asText())
        .contains("AI 섹션 구성 생성에 실패했습니다");
  }

  @Test
  void generate_readOnlyUser_returns403_withoutCallingAi() throws Exception {
    assertThat(generate(readerToken, "주간 화재 추이").getStatus()).isEqualTo(403);
    verify(aiClient, never()).generateSections(anyLong(), anyString());
  }

  @Test
  void generate_blankQuestion_returns400_withoutCallingAi() throws Exception {
    assertThat(generate(writerToken, "   ").getStatus()).isEqualTo(400);
    verify(aiClient, never()).generateSections(anyLong(), anyString());
  }
}
