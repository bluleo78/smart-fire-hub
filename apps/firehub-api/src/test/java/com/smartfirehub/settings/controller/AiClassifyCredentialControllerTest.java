package com.smartfirehub.settings.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredentialSlot;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code AiClassifyCredentialController} 통합 테스트(#707). 실제 {@code OpencodeProbeService} 를 쓴다 —
 * "분류 프로브가 채팅 키를 빌리지 않는다"는 그 서비스 내부 분기가 실제로 돌아야 의미가 있다.
 */
@AutoConfigureMockMvc
class AiClassifyCredentialControllerTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/settings/ai-classify-credential";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;

  private final List<Long> createdUserIds = new ArrayList<>();

  @AfterEach
  void cleanup() {
    // mockMvc 요청 뒤 필터가 컨텍스트를 비우므로 직접 복원한다(AiCredentialControllerTest 와 같은 이유).
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettingsRepository.delete(AiCredentialSlot.CHAT.key());
    tenantSettingsRepository.delete(AiCredentialSlot.CLASSIFY.key());
    tenantSettingsRepository.delete(AiCredentialSlot.CLASSIFY_MODEL_KEY);
    for (Long userId : createdUserIds) {
      inTenantFixture(() -> dsl.execute("delete from user_role where user_id = ?", userId));
      TenantRlsTestSupport.deleteUser(dsl, userId);
    }
  }

  /** ai:settings 를 가진 테넌트 사용자(테넌트 1 의 ADMIN 롤, role_id=1 — 시드 고정값). */
  private long tenantUserWithAiSettings() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, "cls-admin-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);
    inTenantFixture(() -> dsl.execute("insert into user_role (user_id, role_id) values (?, 1)", userId));
    return userId;
  }

  /** 어떤 테넌트 롤도 없는 사용자 — ai:settings 를 포함해 아무 권한도 없다. */
  private long tenantUserWithoutPermission() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, "cls-noperm-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);
    return userId;
  }

  private String tenantToken(long userId) {
    return jwtTokenProvider.generateAccessToken(userId, "u" + userId, DEFAULT_TEST_TENANT_ID);
  }

  private String json(Map<String, ?> body) throws Exception {
    return new ObjectMapper().writeValueAsString(body);
  }

  @Test
  void GET_미설정이면_configured_false_와_빈_모델() throws Exception {
    long userId = tenantUserWithAiSettings();
    mockMvc
        .perform(get(BASE).header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.agentType").value("sdk"))
        .andExpect(jsonPath("$.model").value(""))
        .andExpect(jsonPath("$.secretFieldNames").isEmpty());
  }

  @Test
  void PUT_은_두_키를_저장하고_GET_이_비밀_없이_돌려준다() throws Exception {
    long userId = tenantUserWithAiSettings();
    mockMvc
        .perform(
            put(BASE)
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType", "sdk",
                            "payload", Map.of(),
                            "secret", Map.of("oauthToken", "oat-classify-SECRET"),
                            "model", "claude-haiku-4-5"))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get(BASE).header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("oauthToken"))
        .andExpect(
            result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("SECRET"));
    // 채팅 슬롯은 그대로 미설정이다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(aiCredentialService.read().configured()).isFalse();
  }

  @Test
  void PUT_모델이_없으면_400_과_안내_문구() throws Exception {
    long userId = tenantUserWithAiSettings();
    mockMvc
        .perform(
            put(BASE)
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("agentType", "sdk", "payload", Map.of(), "secret", Map.of("apiKey", "sk")))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("분류 모델을 선택하세요"));
  }

  @Test
  void PUT_opencode_모델_공급자가_다르면_400() throws Exception {
    long userId = tenantUserWithAiSettings();
    mockMvc
        .perform(
            put(BASE)
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType", "opencode",
                            "payload", Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
                            "secret", Map.of(),
                            "model", "anthropic/claude-haiku-4-5"))))
        .andExpect(status().isBadRequest());
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(aiCredentialService.readClassify().configured()).isFalse();
  }

  @Test
  void PUT_opencode_사설_baseURL_은_apiKey_없이도_400() throws Exception {
    long userId = tenantUserWithAiSettings();
    mockMvc
        .perform(
            put(BASE)
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType", "opencode",
                            "payload", Map.of("providerId", "x", "baseURL", "http://169.254.169.254/latest"),
                            "secret", Map.of(),
                            "model", "x/m"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void DELETE_는_두_키를_지우고_멱등이다() throws Exception {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    aiCredentialService.saveClassify(
        new AiCredentialUpsert("cli-api", Map.of(), Map.of("apiKey", "sk")), "claude-haiku-4-5", null);
    long userId = tenantUserWithAiSettings();

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(delete(BASE).header("Authorization", "Bearer " + tenantToken(userId)))
          .andExpect(status().isNoContent());
    }
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(tenantSettingsRepository.findValue(AiCredentialSlot.CLASSIFY.key())).isEmpty();
    assertThat(tenantSettingsRepository.findValue(AiCredentialSlot.CLASSIFY_MODEL_KEY)).isEmpty();
  }

  /** 채팅 슬롯에만 opencode 키가 있다 — 분류 프로브는 그 키를 빌리지 않고 400 이다. */
  @Test
  void probe_는_채팅_슬롯의_키를_재사용하지_않는다() throws Exception {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    aiCredentialService.save(
        new AiCredentialUpsert(
            "opencode",
            Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
            Map.of("apiKey", "sk-CHAT")),
        null);
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            post(BASE + "/probe")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://api.openai.com/v1"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 네_라우트_전부_권한이_없으면_403() throws Exception {
    String token = "Bearer " + tenantToken(tenantUserWithoutPermission());
    mockMvc.perform(get(BASE).header("Authorization", token)).andExpect(status().isForbidden());
    mockMvc
        .perform(put(BASE).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(BASE).header("Authorization", token)).andExpect(status().isForbidden());
    mockMvc
        .perform(post(BASE + "/probe").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
  }

  /**
   * baseURL 에 userinfo(user:pass@)가 있으면 400 — 자격증명이 URL 에 실려 저장·캐시 판별자·로그로
   * 새는 것을 막는다(#707). 검증기는 채팅·분류 공용이라 채팅 PUT 에도 같이 적용된다.
   */
  @Test
  void PUT_opencode_baseURL_에_userinfo_가_있으면_400() throws Exception {
    long userId = tenantUserWithAiSettings();
    mockMvc
        .perform(
            put(BASE)
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType", "opencode",
                            "payload",
                                Map.of("providerId", "openai", "baseURL", "https://user:pass@api.openai.com/v1"),
                            "secret", Map.of(),
                            "model", "openai/gpt-4o-mini"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(OpencodePutValidator.MSG_BASE_URL_USERINFO));
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(aiCredentialService.readClassify().configured()).isFalse();
  }
}
