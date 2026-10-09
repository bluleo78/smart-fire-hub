package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.embedding.config.EmbeddingSettingsService;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S3 §5-5·§3: AI 자격증명·임베딩 설정의 호스팅 위치 선언. Claude 계열은 항상 외부, 자체 호스팅 선언은 security:settings 필요, 유형 변경 시
 * 외부로 돌아간다. 분류 슬롯이 없으면 채팅 호스팅을 따른다. 공통 결정 R3: 세 저장 지점(채팅·분류·임베딩)에서 호스팅 값이 실제로 바뀌면 감사 {@code
 * AI_PROVIDER_HOSTING_CHANGE} 1건, 같으면 0건.
 */
@AutoConfigureMockMvc
class AiHostingDeclarationTest extends IntegrationTestBase {

  /** 임베딩 저장(prepare)의 SSRF 가드를 DNS 없이 통과시키려고 Ollama 주소를 허용 목록에 넣는다. */
  private static final String OLLAMA = "http://ollama-hd.internal:11434";

  @DynamicPropertySource
  static void allowOllama(DynamicPropertyRegistry r) {
    r.add("app.embedding.ollama-allowed-base-urls", () -> OLLAMA);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private AiCredentialService credentialService;
  @Autowired private EmbeddingConfigService embeddingConfigService;
  @Autowired private EmbeddingSettingsService embeddingSettingsService;
  @Autowired private TenantSettingsRepository tenantSettings;
  @Autowired private AiHostingResolver resolver;
  @Autowired private HostingDeclarationPolicy hostingPolicy;

  /** 임베딩 저장의 probe(외부 호출)를 차원만 돌려주게 바꾼다. */
  @MockitoSpyBean private EmbeddingProviderFactory providerFactory;

  /** 저장 후 재임베딩 잡 투입이 실제 배경 작업을 띄우지 않게 막는다. */
  @MockitoBean private TenantReembedJob reembedJob;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearAll();
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    clearAll();
    // 감사 행은 TestUsers.cleanup 이 user_id 기준으로 함께 지운다.
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private void clearAll() {
    tenantSettings.delete(AiCredentialSlot.CHAT.key());
    tenantSettings.delete(AiCredentialSlot.CLASSIFY.key());
    tenantSettings.delete(AiCredentialSlot.CLASSIFY_MODEL_KEY);
    tenantSettings.delete(EmbeddingConfigService.KEY);
  }

  /** opencode 자격증명 — SSRF 가드는 컨트롤러(OpencodePutValidator)에만 있으므로 서비스 직접 호출로 저장한다. */
  private AiCredentialUpsert opencode(String hosting) {
    Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("providerId", "corp");
    payload.put("baseURL", "http://10.0.0.5:8000/v1");
    if (hosting != null) payload.put("hosting", hosting);
    return new AiCredentialUpsert("opencode", payload, Map.of("apiKey", "k"));
  }

  private long userWith(String... perms) {
    long uid = fx.createUser("hd_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("hd_r_" + System.nanoTime(), fx.levelId("내부"), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private String bearer(long userId) {
    return "Bearer " + jwt.generateAccessToken(userId, "hd" + userId, DEFAULT_TEST_TENANT_ID);
  }

  /** 이 사용자의 호스팅 변경 감사 행(테넌트 1 범위). */
  private List<Record> hostingAudits(long userId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetch(
                "select resource_id, tenant_id, metadata::text as meta from audit_log"
                    + " where user_id = ? and action_type = ? order by id",
                userId,
                HostingChangeAuditor.ACTION));
  }

  @Test
  void forShare_requiresBothChatAndEmbeddingSelfHosted() {
    credentialService.save(AiCredentialSlot.CHAT, opencode("SELF_HOSTED"), null);
    assertThat(resolver.forShare()).as("임베딩 외부").isEqualTo(ProviderHosting.EXTERNAL);
    embeddingConfigService.store(
        new EmbeddingConfig(EmbeddingProviderType.OLLAMA, "bge-m3", "http://ollama:11434", "", 0),
        EmbeddingDimension.of(1024),
        ProviderHosting.SELF_HOSTED,
        null);
    assertThat(resolver.forShare()).isEqualTo(ProviderHosting.SELF_HOSTED);
  }

  @Test
  void defaultIsExternal_forEverySlot() {
    assertThat(resolver.chat()).isEqualTo(ProviderHosting.EXTERNAL);
    assertThat(resolver.classify()).isEqualTo(ProviderHosting.EXTERNAL);
    assertThat(resolver.embedding()).isEqualTo(ProviderHosting.EXTERNAL);
  }

  @Test
  void opencodeSelfHosted_isResolved_andTypeChangeResetsToExternal() {
    credentialService.save(AiCredentialSlot.CHAT, opencode("SELF_HOSTED"), null);
    assertThat(resolver.chat()).isEqualTo(ProviderHosting.SELF_HOSTED);
    // 같은 유형 저장에서 hosting 을 생략하면 유지(병합 규칙)
    credentialService.save(AiCredentialSlot.CHAT, opencode(null), null);
    assertThat(resolver.chat()).isEqualTo(ProviderHosting.SELF_HOSTED);
    // 유형을 바꾸면 새 문서 — 외부로 돌아간다
    credentialService.save(
        AiCredentialSlot.CHAT,
        new AiCredentialUpsert("sdk", Map.of(), Map.of("apiKey", "x")),
        null);
    assertThat(resolver.chat()).isEqualTo(ProviderHosting.EXTERNAL);
  }

  @Test
  void claudeFamily_cannotDeclareSelfHosted() {
    assertThatThrownBy(
            () ->
                credentialService.save(
                    AiCredentialSlot.CHAT,
                    new AiCredentialUpsert(
                        "sdk", Map.of("hosting", "SELF_HOSTED"), Map.of("apiKey", "x")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(AiCredentialService.MSG_CLAUDE_EXTERNAL_ONLY);
  }

  @Test
  void unknownHostingValue_isRejected() {
    assertThatThrownBy(
            () -> credentialService.save(AiCredentialSlot.CHAT, opencode("ON_PREM"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(AiCredentialService.MSG_HOSTING_INVALID);
  }

  @Test
  void claudeSelfHosted_viaHttp_is400() throws Exception {
    long admin = userWith("ai:settings", "security:settings");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"agentType\":\"sdk\",\"payload\":{\"hosting\":\"SELF_HOSTED\"},\"secret\":{\"apiKey\":\"x\"}}"))
        .andExpect(status().isBadRequest());
    assertThat(hostingAudits(admin)).as("거부된 저장은 감사하지 않는다").isEmpty();
  }

  @Test
  void raisingToSelfHosted_requiresSecuritySettings_butLoweringDoesNot() {
    long aiOnly = userWith("ai:settings");
    long sec = userWith("ai:settings", "security:settings");
    assertThatThrownBy(
            () ->
                hostingPolicy.requireChangeAllowed(
                    aiOnly, ProviderHosting.EXTERNAL, ProviderHosting.SELF_HOSTED))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo(HostingDeclarationPolicy.FORBIDDEN_CODE);
    // 내리는 방향·변화 없음은 허용
    hostingPolicy.requireChangeAllowed(
        aiOnly, ProviderHosting.SELF_HOSTED, ProviderHosting.EXTERNAL);
    hostingPolicy.requireChangeAllowed(
        aiOnly, ProviderHosting.SELF_HOSTED, ProviderHosting.SELF_HOSTED);
    hostingPolicy.requireChangeAllowed(sec, ProviderHosting.EXTERNAL, ProviderHosting.SELF_HOSTED);
  }

  @Test
  void embeddingRaiseWithoutSecuritySettings_is403_andNothingStoredOrAudited() {
    long aiOnly = userWith("ai:settings");
    doReturn(1024).when(providerFactory).probeDimension(any());
    assertThatThrownBy(
            () ->
                embeddingSettingsService.save(
                    new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, "SELF_HOSTED"),
                    aiOnly))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo(HostingDeclarationPolicy.FORBIDDEN_CODE);
    assertThat(embeddingConfigService.view().configured()).isFalse();
    assertThat(hostingAudits(aiOnly)).isEmpty();
  }

  @Test
  void previewHosting_matchesWhatSaveWillStore() {
    assertThat(credentialService.previewHosting(AiCredentialSlot.CHAT, opencode("SELF_HOSTED")))
        .isEqualTo(ProviderHosting.SELF_HOSTED);
    credentialService.save(AiCredentialSlot.CHAT, opencode("SELF_HOSTED"), null);
    assertThat(credentialService.previewHosting(AiCredentialSlot.CHAT, opencode(null)))
        .as("같은 유형·생략 = 유지")
        .isEqualTo(ProviderHosting.SELF_HOSTED);
    assertThat(
            credentialService.previewHosting(
                AiCredentialSlot.CHAT, new AiCredentialUpsert("sdk", Map.of(), Map.of())))
        .as("유형 변경 = 외부")
        .isEqualTo(ProviderHosting.EXTERNAL);
  }

  @Test
  void classify_followsChat_untilItsOwnSlotExists() {
    credentialService.save(AiCredentialSlot.CHAT, opencode("SELF_HOSTED"), null);
    assertThat(resolver.classify()).isEqualTo(ProviderHosting.SELF_HOSTED);
    credentialService.saveClassify(opencode("EXTERNAL"), "corp/m1", null);
    assertThat(resolver.classify()).isEqualTo(ProviderHosting.EXTERNAL);
  }

  @Test
  void embeddingHosting_isStoredInDocument() {
    EmbeddingConfig cfg =
        new EmbeddingConfig(EmbeddingProviderType.OLLAMA, "bge-m3", "http://ollama:11434", "", 0);
    embeddingConfigService.store(
        cfg, EmbeddingDimension.of(1024), ProviderHosting.SELF_HOSTED, null);
    assertThat(resolver.embedding()).isEqualTo(ProviderHosting.SELF_HOSTED);
    assertThat(embeddingConfigService.view().hosting()).isEqualTo("SELF_HOSTED");
    embeddingConfigService.store(cfg, EmbeddingDimension.of(1024), ProviderHosting.EXTERNAL, null);
    assertThat(resolver.embedding()).isEqualTo(ProviderHosting.EXTERNAL);
  }

  // ---- R3 감사: 호스팅 값이 실제로 바뀐 저장만 1건 ----

  @Test
  void chatHostingChange_viaHttp_isAuditedOnce_andSameValueResaveIsNot() throws Exception {
    long admin = userWith("ai:settings");
    // opencode 는 HTTP 경로에서 SSRF 가드에 걸리므로 서비스로 심는다(감사 대상 아님 — 컨트롤러 저장 지점만 감사).
    credentialService.save(AiCredentialSlot.CHAT, opencode("SELF_HOSTED"), null);
    String sdkBody = "{\"agentType\":\"sdk\",\"payload\":{},\"secret\":{\"apiKey\":\"x\"}}";

    // 유형 변경(opencode→sdk)은 외부로 돌아가는 실제 호스팅 변경이다.
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(sdkBody))
        .andExpect(status().isNoContent());
    List<Record> audits = hostingAudits(admin);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).get("resource_id", String.class)).isEqualTo("CHAT");
    assertThat(audits.get(0).get("tenant_id", Long.class)).isEqualTo(DEFAULT_TEST_TENANT_ID);
    assertThat(audits.get(0).get("meta", String.class))
        .contains("\"from\": \"SELF_HOSTED\"")
        .contains("\"to\": \"EXTERNAL\"");

    // 같은 값(EXTERNAL→EXTERNAL) 재저장은 기록하지 않는다.
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(sdkBody))
        .andExpect(status().isNoContent());
    assertThat(hostingAudits(admin)).hasSize(1);
  }

  @Test
  void classifyHostingChange_viaHttp_isAuditedOnce_andSameValueResaveIsNot() throws Exception {
    long admin = userWith("ai:settings");
    credentialService.saveClassify(opencode("SELF_HOSTED"), "corp/m1", null);
    String sdkBody =
        "{\"agentType\":\"sdk\",\"payload\":{},\"secret\":{\"apiKey\":\"x\"},\"model\":\"claude-x\"}";

    mockMvc
        .perform(
            put("/api/v1/settings/ai-classify-credential")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(sdkBody))
        .andExpect(status().isNoContent());
    List<Record> audits = hostingAudits(admin);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).get("resource_id", String.class)).isEqualTo("CLASSIFY");
    assertThat(audits.get(0).get("meta", String.class))
        .contains("\"from\": \"SELF_HOSTED\"")
        .contains("\"to\": \"EXTERNAL\"");

    mockMvc
        .perform(
            put("/api/v1/settings/ai-classify-credential")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(sdkBody))
        .andExpect(status().isNoContent());
    assertThat(hostingAudits(admin)).hasSize(1);
  }

  @Test
  void embeddingHostingChange_isAuditedOnce_andSameValueResaveIsNot() {
    long sec = userWith("ai:settings", "security:settings");
    doReturn(1024).when(providerFactory).probeDimension(any());

    embeddingSettingsService.save(
        new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, "SELF_HOSTED"), sec);
    assertThat(resolver.embedding()).isEqualTo(ProviderHosting.SELF_HOSTED);
    List<Record> audits = hostingAudits(sec);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).get("resource_id", String.class)).isEqualTo("EMBEDDING");
    assertThat(audits.get(0).get("tenant_id", Long.class)).isEqualTo(DEFAULT_TEST_TENANT_ID);
    assertThat(audits.get(0).get("meta", String.class))
        .contains("\"from\": \"EXTERNAL\"")
        .contains("\"to\": \"SELF_HOSTED\"");

    // 같은 값 명시 재저장·생략(유지) 재저장 모두 기록하지 않는다.
    embeddingSettingsService.save(
        new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, "SELF_HOSTED"), sec);
    embeddingSettingsService.save(
        new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, null), sec);
    assertThat(resolver.embedding()).isEqualTo(ProviderHosting.SELF_HOSTED);
    assertThat(hostingAudits(sec)).hasSize(1);
  }

  @Test
  void embeddingUnknownHosting_is400() {
    long sec = userWith("ai:settings", "security:settings");
    assertThatThrownBy(
            () ->
                embeddingSettingsService.save(
                    new EmbeddingConfigRequest("OLLAMA", "bge-m3", OLLAMA, null, "ON_PREM"), sec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(AiCredentialService.MSG_HOSTING_INVALID);
  }

  /** 공인 IP 리터럴 opencode(apiKey 없음) — SSRF 가드는 DNS 없이 통과하고 프로브는 건너뛰므로 HTTP 경로로 선언 권한 판정까지 도달한다. */
  private static final String PUBLIC_OPENCODE_SELF_HOSTED =
      "{\"agentType\":\"opencode\",\"payload\":{\"providerId\":\"corp\","
          + "\"baseURL\":\"https://93.184.216.34/v1\",\"hosting\":\"SELF_HOSTED\"},\"secret\":{}";

  @Test
  void chatRaiseViaHttp_withoutSecuritySettings_is403_andNothingStoredOrAudited() throws Exception {
    long aiOnly = userWith("ai:settings");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", bearer(aiOnly))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PUBLIC_OPENCODE_SELF_HOSTED + "}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(HostingDeclarationPolicy.FORBIDDEN_CODE));
    // MockMvc 요청 필터가 끝나며 TenantContext 를 비우므로 다시 세운다 — 안 그러면 읽기가 "미설정"으로 공허하게 통과한다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(credentialService.read().configured()).as("거부 시 아무것도 쓰지 않는다").isFalse();
    assertThat(hostingAudits(aiOnly)).isEmpty();
  }

  @Test
  void classifyRaiseViaHttp_withoutSecuritySettings_is403() throws Exception {
    long aiOnly = userWith("ai:settings");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-classify-credential")
                .header("Authorization", bearer(aiOnly))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PUBLIC_OPENCODE_SELF_HOSTED + ",\"model\":\"corp/m1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(HostingDeclarationPolicy.FORBIDDEN_CODE));
    // MockMvc 요청 필터가 끝나며 TenantContext 를 비우므로 다시 세운다 — 안 그러면 읽기가 "미설정"으로 공허하게 통과한다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(credentialService.readClassify().configured()).isFalse();
    assertThat(hostingAudits(aiOnly)).isEmpty();
  }

  @Test
  void chatRaiseViaHttp_withSecuritySettings_isStoredAndAudited() throws Exception {
    long sec = userWith("ai:settings", "security:settings");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", bearer(sec))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PUBLIC_OPENCODE_SELF_HOSTED + "}"))
        .andExpect(status().isNoContent());
    // MockMvc 요청 필터가 끝나며 TenantContext 를 비우므로 다시 세운다 — 안 그러면 읽기가 "미설정"으로 공허하게 통과한다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(resolver.chat()).isEqualTo(ProviderHosting.SELF_HOSTED);
    List<Record> audits = hostingAudits(sec);
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0).get("meta", String.class))
        .contains("\"from\": \"EXTERNAL\"")
        .contains("\"to\": \"SELF_HOSTED\"");
  }
}
