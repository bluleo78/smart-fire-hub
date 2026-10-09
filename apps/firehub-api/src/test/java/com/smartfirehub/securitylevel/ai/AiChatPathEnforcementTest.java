package com.smartfirehub.securitylevel.ai;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.global.security.InternalCallHeaders;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 스펙 §4.3 채팅 MCP 행: AI 대행 요청(Internal + X-On-Behalf-Of)에서 목록·검색·스키마는 AI 불허 데이터셋을 빼고, 상세·행·SQL 은
 * VIEW 통과 후 403 POLICY_BLOCKED. 같은 사용자의 웹(JWT) 요청은 그대로 보인다(대조군). share 목적이면 SHARE DENY(기밀)도 막힌다.
 *
 * <p>존재 은닉(Review Focus 3): 볼 수 없는 데이터셋은 AI 요청에서도 기존과 같은 404·DATASET_SQL_ACCESS_DENIED 이고 등급 이름이
 * 실리지 않는다. 민감 데이터셋은 실제 테이블로 만든다 — 스키마 조회는 information_schema 를 읽으므로 물리 테이블이 없으면 "제외됨" 단언이 공허해진다.
 */
@AutoConfigureMockMvc
class AiChatPathEnforcementTest extends IntegrationTestBase {

  /** application-test.yml 의 agent.internal-token. */
  private static final String INTERNAL_TOKEN = "test-internal-token";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private TenantSettingsRepository tenantSettings;
  @Autowired private DatasetService datasetService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long userId;
  private long creator;
  private long publicId;
  private long sensitiveId;
  private String marker;
  private String sensitiveTable;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    marker = "acp" + System.nanoTime();
    creator = fx.createUser("acp_creator");
    users.add(creator);
    userId = fx.createUser("acp_user");
    users.add(userId);
    fx.removeUserRole(userId);
    long roleId =
        fx.createRole(
            "acp_role_" + marker,
            fx.levelId("기밀"),
            "dataset:read",
            "data:read",
            "analytics:read",
            "analytics:write",
            "data:import");
    roles.add(roleId);
    fx.assignRole(userId, roleId);
    publicId = realTable(marker + "_pub", "공개");
    sensitiveTable = marker + "_sens";
    sensitiveId = realTable(sensitiveTable, "민감");
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
  }

  @AfterEach
  void tearDown() {
    // MockMvc 요청 필터가 끝나며 TenantContext 를 비우므로 정리 전에 다시 세운다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearHosting();
    for (long id : List.of(publicId, sensitiveId)) {
      try {
        datasetService.deleteDataset(id);
      } catch (Exception ignored) {
        // 정리 실패는 다음 정리에 맡긴다.
      }
    }
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private void clearHosting() {
    tenantSettings.delete(AiCredentialSlot.CHAT.key());
    tenantSettings.delete(EmbeddingConfigService.KEY);
  }

  /** 물리 테이블이 있는 데이터셋을 만들고 등급을 지정한다(스키마·행 조회가 실제로 실행되는 대조군을 위해). */
  private long realTable(String t, String level) {
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    t,
                    t,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                creator)
            .id();
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
                .where(DATASET.ID.eq(id))
                .execute());
    return id;
  }

  /** ai-agent 의 MCP 대행 호출 재현(내부 토큰 + 대행 사용자·테넌트, 선택적으로 목적 헤더). */
  private MockHttpServletRequestBuilder ai(
      MockHttpServletRequestBuilder b, long onBehalfOf, String purpose) {
    b.header("Authorization", "Internal " + INTERNAL_TOKEN)
        .header(InternalCallHeaders.ON_BEHALF_OF, String.valueOf(onBehalfOf))
        .header(InternalCallHeaders.ON_BEHALF_OF_TENANT, String.valueOf(DEFAULT_TEST_TENANT_ID));
    if (purpose != null) {
      b.header(InternalCallHeaders.AI_PURPOSE, purpose);
    }
    return b;
  }

  private MockHttpServletRequestBuilder ai(MockHttpServletRequestBuilder b, String purpose) {
    return ai(b, userId, purpose);
  }

  /** 같은 사용자의 웹 요청(JWT) — 비AI 대조군. */
  private MockHttpServletRequestBuilder web(MockHttpServletRequestBuilder b) {
    return b.header(
        "Authorization",
        "Bearer " + jwt.generateAccessToken(userId, "acp" + userId, DEFAULT_TEST_TENANT_ID));
  }

  private MockHttpServletResponse run(MockHttpServletRequestBuilder b) throws Exception {
    return mockMvc.perform(b).andReturn().getResponse();
  }

  private List<Long> listIds(MockHttpServletRequestBuilder b) throws Exception {
    MockHttpServletResponse r = run(b);
    assertThat(r.getStatus()).as("목록 요청 자체가 성공해야 한다(인증·테넌트 성립)").isEqualTo(200);
    JsonNode page = om.readTree(r.getContentAsString());
    List<Long> ids = new ArrayList<>();
    page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
    return ids;
  }

  private MockHttpServletRequestBuilder sql(String sql) {
    return post("/api/v1/analytics/queries/execute")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"sql\":\"" + sql + "\",\"maxRows\":10}");
  }

  /** 403 POLICY_BLOCKED 본문 계약(action·levelName·policyKey)을 단언한다. */
  private void assertPolicyBlocked(
      MockHttpServletResponse r, String action, String levelName, String policyKey)
      throws Exception {
    assertThat(r.getStatus()).isEqualTo(403);
    JsonNode body = om.readTree(r.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo(PolicyBlockedException.CODE);
    assertThat(body.get("errors").get("action").asText()).isEqualTo(action);
    assertThat(body.get("errors").get("levelName").asText()).isEqualTo(levelName);
    assertThat(body.get("errors").get("policyKey").asText()).isEqualTo(policyKey);
  }

  /** 채팅 자격증명을 자체 호스팅 opencode 로 선언한다(테스트 전용 — 저장소 직접 기록). */
  private void declareChatSelfHosted() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettings.upsert(
        AiCredentialSlot.CHAT.key(),
        "{\"v\":1,\"agentType\":\"opencode\",\"payload\":{\"providerId\":\"corp\","
            + "\"baseURL\":\"http://10.0.0.5/v1\",\"hosting\":\"SELF_HOSTED\"},\"secret\":{}}",
        null);
  }

  /** 임베딩 공급자를 자체 호스팅 Ollama 로 선언한다. */
  private void declareEmbeddingSelfHosted() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettings.upsert(
        EmbeddingConfigService.KEY,
        "{\"v\":1,\"provider\":\"OLLAMA\",\"model\":\"bge-m3\",\"baseUrl\":\"http://ollama:11434\","
            + "\"dimension\":1024,\"hosting\":\"SELF_HOSTED\",\"secret\":{\"apiKey\":\"\"}}",
        null);
  }

  @Test
  void list_aiExternal_hidesSensitive_butWebStillSeesIt() throws Exception {
    assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), null)))
        .containsExactly(publicId);
    assertThat(listIds(web(get("/api/v1/datasets?search=" + marker))))
        .containsExactlyInAnyOrder(publicId, sensitiveId);
  }

  @Test
  void list_aiSelfHosted_showsSensitive() throws Exception {
    declareChatSelfHosted();
    assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), null)))
        .containsExactlyInAnyOrder(publicId, sensitiveId);
  }

  @Test
  void detail_aiExternal_is403PolicyBlocked_withLevelName() throws Exception {
    assertPolicyBlocked(
        run(ai(get("/api/v1/datasets/" + sensitiveId), null)), "AI", "민감", "ai_policy");
    // 대조군: 같은 AI 요청이라도 AI 허용 등급(공개)은 통과 — 내부 인증·테넌트가 성립했다는 증거.
    assertThat(run(ai(get("/api/v1/datasets/" + publicId), null)).getStatus()).isEqualTo(200);
    // 대조군: 같은 사용자의 웹 요청은 그대로 열린다(비AI 동작 불변).
    assertThat(run(web(get("/api/v1/datasets/" + sensitiveId))).getStatus()).isEqualTo(200);
  }

  @Test
  void aiCall_hiddenDataset_is404_notPolicyBlocked() throws Exception {
    long low = lowUser();
    for (String path :
        List.of("/api/v1/datasets/" + sensitiveId, "/api/v1/datasets/" + sensitiveId + "/data")) {
      MockHttpServletResponse r = run(ai(get(path), low, null));
      assertThat(r.getStatus()).as(path).isEqualTo(404);
      assertThat(r.getContentAsString()).doesNotContain("POLICY_BLOCKED").doesNotContain("민감");
    }
    // 대조군: 같은 사용자가 볼 수 있는 공개 데이터셋은 200 — 404 가 인증 실패가 아니라 VIEW 판정임을 보인다.
    assertThat(run(ai(get("/api/v1/datasets/" + publicId), low, null)).getStatus()).isEqualTo(200);
  }

  @Test
  void aiCall_hiddenTableInSql_isAccessDenied_withoutLevelName() throws Exception {
    long low = lowUser();
    MockHttpServletResponse r = run(ai(sql("SELECT * FROM " + sensitiveTable), low, null));
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(om.readTree(r.getContentAsString()).get("code").asText())
        .isEqualTo("DATASET_SQL_ACCESS_DENIED");
    assertThat(r.getContentAsString()).doesNotContain("POLICY_BLOCKED").doesNotContain("민감");
  }

  @Test
  void aiCall_sqlMixingHiddenAndAiBlocked_reportsAccessDeniedFirst() throws Exception {
    // 기밀(허용 목록 필요)은 grant 없는 사용자에게 숨김. 민감은 볼 수 있지만 외부 AI 불허.
    // VIEW 판정이 AI 판정보다 먼저여야 숨김 테이블의 존재가 등급 이름 응답으로 구분되지 않는다.
    long secretId = fx.createDatasetRow(marker + "_hid", fx.levelId("기밀"), creator);
    try {
      MockHttpServletResponse r =
          run(
              ai(
                  sql("SELECT a.v FROM " + sensitiveTable + " a JOIN " + marker + "_hid b ON true"),
                  null));
      assertThat(r.getStatus()).isEqualTo(403);
      assertThat(om.readTree(r.getContentAsString()).get("code").asText())
          .isEqualTo("DATASET_SQL_ACCESS_DENIED");
      assertThat(r.getContentAsString()).doesNotContain("POLICY_BLOCKED").doesNotContain("기밀");
    } finally {
      fx.deleteDatasetRow(secretId);
    }
  }

  @Test
  void rows_aiExternal_is403PolicyBlocked() throws Exception {
    assertPolicyBlocked(
        run(ai(get("/api/v1/datasets/" + sensitiveId + "/data"), null)), "AI", "민감", "ai_policy");
    assertThat(run(web(get("/api/v1/datasets/" + sensitiveId + "/data"))).getStatus())
        .isEqualTo(200);
  }

  @Test
  void adhocSql_aiExternal_isPolicyBlocked() throws Exception {
    assertPolicyBlocked(
        run(ai(sql("SELECT * FROM " + sensitiveTable), null)), "AI", "민감", "ai_policy");
    // 대조군: 웹 애드혹 쿼리는 그대로 실행된다.
    assertThat(run(web(sql("SELECT * FROM " + sensitiveTable))).getStatus()).isEqualTo(200);
  }

  @Test
  void schema_aiExternal_excludesSensitiveTable() throws Exception {
    MockHttpServletResponse r = run(ai(get("/api/v1/analytics/queries/schema"), null));
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).doesNotContain(sensitiveTable);
    // 대조군: 웹 요청과 목적 none(비LLM 대행)은 같은 테이블이 스키마에 보인다 — 위 단언이 공허하지 않다.
    assertThat(run(web(get("/api/v1/analytics/queries/schema"))).getContentAsString())
        .contains(sensitiveTable);
    assertThat(run(ai(get("/api/v1/analytics/queries/schema"), "none")).getContentAsString())
        .contains(sensitiveTable);
  }

  @Test
  void sharePurpose_withEmbeddingExternal_hidesSensitiveToo() throws Exception {
    declareChatSelfHosted(); // 임베딩은 외부(기본) — 공유 목적은 forShare 규칙으로 외부 취급
    assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), null))).contains(sensitiveId);
    assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), "share")))
        .doesNotContain(sensitiveId);
  }

  @Test
  void sharePurpose_hidesSecret_evenWhenSelfHosted() throws Exception {
    declareChatSelfHosted();
    declareEmbeddingSelfHosted();
    long secretId = fx.createDatasetRow(marker + "_secret", fx.levelId("기밀"), creator);
    fx.grantUser(secretId, userId);
    try {
      assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), null))).contains(secretId);
      // 민감(공유 허용·자체 호스팅 한정)이 공유 목록에 남아 있어야 두 선언이 실제로 읽혔고 기밀 제외가 share_policy 때문임이 드러난다.
      assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), "share")))
          .contains(sensitiveId)
          .doesNotContain(secretId);
      assertPolicyBlocked(
          run(ai(get("/api/v1/datasets/" + secretId), "share")), "SHARE", "기밀", "share_policy");
    } finally {
      fx.deleteDatasetRow(secretId);
    }
  }

  @Test
  void purposeNone_behavesLikeWeb() throws Exception {
    assertThat(listIds(ai(get("/api/v1/datasets?search=" + marker), "none")))
        .containsExactlyInAnyOrder(publicId, sensitiveId);
    assertThat(run(ai(get("/api/v1/datasets/" + sensitiveId), "none")).getStatus()).isEqualTo(200);
  }

  /** POST JSON 요청 빌더. */
  private MockHttpServletRequestBuilder postJson(String path, String body) {
    return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /**
   * 행 검색(/datasets/{id}/rows/search)은 데이터셋 경로 인터셉터의 requireView 가 AI 판정까지 건다 — 핸들러(색인 미설정 등)보다 먼저
   * 403 POLICY_BLOCKED. 대조군: 같은 AI 요청의 공개 데이터셋과 같은 사용자의 웹 요청은 정책 차단이 아니다(색인 미설정 응답은 상관없다).
   */
  @Test
  void rowSearch_aiExternal_is403PolicyBlocked() throws Exception {
    String body = "{\"query\":\"x\",\"mode\":\"KEYWORD\"}";
    assertPolicyBlocked(
        run(ai(postJson("/api/v1/datasets/" + sensitiveId + "/rows/search", body), null)),
        "AI",
        "민감",
        "ai_policy");
    for (MockHttpServletResponse r :
        List.of(
            run(ai(postJson("/api/v1/datasets/" + publicId + "/rows/search", body), null)),
            run(web(postJson("/api/v1/datasets/" + sensitiveId + "/rows/search", body))))) {
      assertThat(r.getStatus()).isNotEqualTo(403);
      assertThat(r.getContentAsString()).doesNotContain("POLICY_BLOCKED");
    }
  }

  /** 데이터셋 SQL 쿼리(/datasets/{id}/query) — AI 요청은 403 POLICY_BLOCKED, 같은 사용자의 웹 요청은 실행된다. */
  @Test
  void datasetQuery_aiExternal_isPolicyBlocked() throws Exception {
    String body = "{\"sql\":\"SELECT * FROM " + sensitiveTable + "\",\"maxRows\":10}";
    assertPolicyBlocked(
        run(ai(postJson("/api/v1/datasets/" + sensitiveId + "/query", body), null)),
        "AI",
        "민감",
        "ai_policy");
    MockHttpServletResponse webResponse =
        run(web(postJson("/api/v1/datasets/" + sensitiveId + "/query", body)));
    assertThat(webResponse.getStatus()).as(webResponse.getContentAsString()).isEqualTo(200);
  }

  /**
   * 카탈로그 키워드 검색(/datasets/search, find_datasets 경로) — AI 요청 결과에서 민감 데이터셋이 빠진다(visibleSql 의 AI 훅).
   */
  @Test
  void catalogSearch_aiExternal_excludesSensitive() throws Exception {
    String body = "{\"query\":\"" + marker + "\",\"mode\":\"KEYWORD\",\"topK\":20}";
    // 대조군 먼저: 웹 요청이 두 데이터셋을 다 찾아야 AI 요청의 "제외"가 색인 부재가 아니라 정책 때문임이 드러난다.
    assertThat(searchIds(web(postJson("/api/v1/datasets/search", body))))
        .contains(publicId, sensitiveId);
    assertThat(searchIds(ai(postJson("/api/v1/datasets/search", body), null)))
        .contains(publicId)
        .doesNotContain(sensitiveId);
  }

  private List<Long> searchIds(MockHttpServletRequestBuilder b) throws Exception {
    MockHttpServletResponse r = run(b);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    List<Long> ids = new ArrayList<>();
    om.readTree(r.getContentAsString()).forEach(n -> ids.add(n.get("datasetId").asLong()));
    return ids;
  }

  /** 공개 등급까지만 보는 사용자(민감 데이터셋이 숨김). */
  private long lowUser() {
    long low = fx.createUser("acp_low");
    users.add(low);
    fx.removeUserRole(low);
    long lowRole =
        fx.createRole(
            "acp_low_" + System.nanoTime(),
            fx.levelId("공개"),
            "dataset:read",
            "data:read",
            "analytics:read");
    roles.add(lowRole);
    fx.assignRole(low, lowRole);
    return low;
  }
}
