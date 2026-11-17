package com.smartfirehub.settings.controller;

import static com.smartfirehub.support.SettingsTestSupport.upsertSystemSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.AiCredentialUpsert;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SettingsTestSupport;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code AiCredentialController} 통합 테스트(Task 7, #706 로 테넌트 전용화).
 *
 * <p><b>실제 {@code OpencodeProbeService} 빈을 쓴다 — mock 하지 않는다.</b> 이 파일의 핵심 단언 두 개("프로브는 플랫폼 행의 키를
 * 빌리지 않는다", "baseURL 이 저장된 값과 다르면 apiKey 를 요구한다")는 그 서비스 <b>내부</b>의 실제 분기(재사용할 저장된 키가 없음/baseURL
 * 불일치)가 실행돼야 의미가 있다 — mock 으로 그 예외를 흉내 내면 "컨트롤러가 예외를 400 으로 옮기는지"만 증명하지 "컨트롤러가 스스로 다른 저장소를 읽어 키를 새어
 * 보내지 않는지"는 증명하지 못한다. 다행히 두 시나리오 모두 {@code resolveApiKey} 의 사전 검증 단계에서 끝나 실제 네트워크 호출 전에 {@code
 * IllegalArgumentException} 이 던져지므로(해당 서비스의 javadoc 참고) 네트워크 없이도 빠르고 결정적이다. 같은 이유로 Reason→상태 매핑의
 * <b>일부</b>(INVALID_URL/SCHEME_NOT_ALLOWED/ PORT_NOT_ALLOWED/BLOCKED_ADDRESS)도 여기서 실제 서비스로 검증한다 —
 * 전부 SSRF 가드에서 걸려 DNS/소켓 연결 전에 끝나는 것들만 골랐다. 나머지(도달 불가/타임아웃/공급자 거부 등, 실제 네트워크가 필요한 값들)는 {@link
 * OpencodeCredentialValidationTest}(순수 유닛, 12개 값 전수)와 mock 기반의 {@link
 * AiCredentialProbeStatusMappingTest} 가 다룬다.
 */
@AutoConfigureMockMvc
class AiCredentialControllerTest extends IntegrationTestBase {

  private static final String CREDENTIAL_KEY = "ai.credential";
  private static final Long USER = null;

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;

  @Autowired private EncryptionService encryptionService;

  private String platformCredentialOriginal;
  private final List<Long> createdUserIds = new ArrayList<>();

  @BeforeEach
  void captureOriginalPlatformValue() {
    platformCredentialOriginal = SettingsTestSupport.rawSystemSettingValue(dsl, CREDENTIAL_KEY);
  }

  @AfterEach
  void cleanup() {
    // AiCredentialServiceTest 와 달리 이 클래스는 mockMvc.perform() 을 거친다 — 실제
    // JwtAuthenticationFilter 가 매 요청 뒤 finally 에서 TenantContext.clear() 를 무조건
    // 호출하므로(스레드 재사용 시 테넌트가 새는 것을 막는 장치), 테스트 메서드 본문에서 이미
    // 컨텍스트가 비워져 있다. IntegrationTestBase.clearTenantContext() 는 서브클래스 @AfterEach
    // 뒤에 실행되지만 그 전에 컨텍스트가 살아 있다는 보장은 "요청을 거치지 않은" 테스트에만
    // 해당한다 — 여기서는 직접 복원해야 tenant_settings RLS 삭제가 fail-closed 로 막히지 않는다.
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    tenantSettingsRepository.delete(CREDENTIAL_KEY);
    tenantSettingsRepository.delete("ai.model");
    // 플랫폼 행은 plantPlatformCredential 로만 심는다(V126 이후 원래 없다 → 삭제로 원복).
    SettingsTestSupport.restoreSystemSettingValue(dsl, CREDENTIAL_KEY, platformCredentialOriginal);

    for (Long userId : createdUserIds) {
      inTenantFixture(() -> dsl.execute("delete from user_role where user_id = ?", userId));
      // 자격증명 저장·해제 감사 행(WD-465)이 user_id FK 로 사용자 삭제를 막으므로 먼저 지운다.
      inTenantFixture(() -> dsl.execute("delete from audit_log where user_id = ?", userId));
      TenantRlsTestSupport.deleteMembership(dsl, userId);
      TenantRlsTestSupport.deleteUser(dsl, userId);
    }
  }

  // -------------------------------------------------------------------------
  // 픽스처 헬퍼
  // -------------------------------------------------------------------------

  /** 기본 테스트 테넌트의 {@code ai.model} 을 저장한다(AI 설정은 테넌트 전용). */
  private void setTenantAiModel(String model) {
    inTenantFixture(() -> tenantSettingsRepository.upsert("ai.model", model, null));
  }

  private void saveTenantCredential(String agentType, String baseURL, String apiKey) {
    aiCredentialService.save(upsert(agentType, baseURL, apiKey), USER);
  }

  /**
   * 옛 플랫폼 평면 행을 {@code system_settings} 에 직접 심는다(쓰기 API 가 없다). opencode 면 baseURL 을 함께 담는다. 이 행이
   * 있어도 테넌트 API 의 결과가 바뀌지 않아야 한다.
   */
  private void plantPlatformCredential(String agentType, String apiKey) {
    String payload =
        "opencode".equals(agentType)
            ? "{\"providerId\":\"openai\",\"baseURL\":\"https://api.openai.com/v1\"}"
            : "{}";
    String json =
        "{\"v\":1,\"agentType\":\""
            + agentType
            + "\",\"payload\":"
            + payload
            + ",\"secret\":{\"apiKey\":\""
            + encryptionService.encrypt(apiKey)
            + "\"}}";
    upsertSystemSetting(dsl, CREDENTIAL_KEY, json);
  }

  private AiCredentialUpsert upsert(String agentType, String baseURL, String apiKey) {
    Map<String, Object> payload =
        baseURL == null ? Map.of() : Map.of("providerId", "openai", "baseURL", baseURL);
    String secretField = "cli".equals(agentType) ? "oauthToken" : "apiKey";
    Map<String, String> secret = apiKey == null ? Map.of() : Map.of(secretField, apiKey);
    return new AiCredentialUpsert(agentType, payload, secret);
  }

  /** ai:settings 를 가진 테넌트 사용자(테넌트 1 의 ADMIN 롤, role_id=1 — 시드 고정값). */
  private long tenantUserWithAiSettings() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p7t7-admin-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);
    // 권한 조회는 같은 테넌트의 ACTIVE 멤버십을 요구한다(WD-2) — user_role 만으로는 권한이 0 이다.
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    inTenantFixture(
        () -> dsl.execute("insert into user_role (user_id, role_id) values (?, 1)", userId));
    return userId;
  }

  /** 어떤 테넌트 롤도 없는 사용자 — ai:settings 를 포함해 아무 권한도 없다. */
  private long tenantUserWithoutPermission() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(
            dsl, "p7t7-noperm-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);
    return userId;
  }

  private String tenantToken(long userId) {
    return jwtTokenProvider.generateAccessToken(userId, "u" + userId, DEFAULT_TEST_TENANT_ID);
  }

  private String json(Map<String, ?> body) throws Exception {
    return new ObjectMapper().writeValueAsString(body);
  }

  // -------------------------------------------------------------------------
  // 프로브 — 저장된 키 재사용은 현재 테넌트 행에서만 (브리프 Step 1의 핵심 두 시나리오)
  // -------------------------------------------------------------------------

  /**
   * 테넌트 행이 없는 상태에서 apiKey 를 생략하면 재사용할 키가 없어 400 이다. 옛 플랫폼 행(완전한 opencode 자격증명)을 심어 두어, 그 행을 읽는 폴백이
   * 되살아나면 플랫폼의 apiKey 를 테넌트가 지정한 임의 baseURL(evil.example)로 내보내게 되는 경로를 막는지 본다.
   */
  @Test
  void 프로브는_플랫폼_행의_키를_빌리지_않는다() throws Exception {
    plantPlatformCredential("opencode", "sk-PLATFORM");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            post("/api/v1/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://evil.example/v1"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void baseURL_이_저장된_값과_다르면_apiKey_를_요구한다() throws Exception {
    saveTenantCredential("opencode", "https://api.openai.com/v1", "sk-TENANT");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            post("/api/v1/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://other.example/v1"))))
        .andExpect(status().isBadRequest());
  }

  /** 가드가 실제로 통과하는 경로도 한 번은 확인한다 — 위 두 테스트가 전부 거부만 보고 있어서다. */
  @Test
  void 프로브는_apiKey_를_주면_저장된_값_없이도_동작한다() throws Exception {
    long userId = tenantUserWithAiSettings();

    // 사설 대역이라 BLOCKED_ADDRESS 로 막히지만(네트워크 왕복 없음), 여기까지 오려면
    // "재사용할 저장된 키가 없다"는 400 을 지나야 한다 — apiKey 를 직접 줬으니 그 관문은 통과한다.
    mockMvc
        .perform(
            post("/api/v1/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://10.0.0.5/v1", "apiKey", "sk-given"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false));
  }

  // -------------------------------------------------------------------------
  // 권한 — 각 라우트에서 애너테이션이 실제로 집행되는지(뮤테이션: 애너테이션 제거 → RED)
  // -------------------------------------------------------------------------

  @Test
  void 프로브는_쓰기_권한을_요구한다() throws Exception {
    long userId = tenantUserWithoutPermission();

    mockMvc
        .perform(
            post("/api/v1/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GET_은_권한이_없으면_403() throws Exception {
    long userId = tenantUserWithoutPermission();

    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void PUT_은_권한이_없으면_403() throws Exception {
    long userId = tenantUserWithoutPermission();

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("agentType", "sdk", "payload", Map.of(), "secret", Map.of()))))
        .andExpect(status().isForbidden());
  }

  // -------------------------------------------------------------------------
  // GET 동작
  // -------------------------------------------------------------------------

  @Test
  void GET_은_비밀_값을_내보내지_않는다() throws Exception {
    saveTenantCredential("sdk", null, "sk-secret");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"))
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(content().string(not(containsString("sk-secret"))));
  }

  // -------------------------------------------------------------------------
  // PUT — 로컬 검증(네트워크 없음)
  // -------------------------------------------------------------------------

  @Test
  void PUT_opencode_필수_필드_없으면_400() throws Exception {
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("agentType", "opencode", "payload", Map.of()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void PUT_opencode_reasoningEffort_가_정적_목록에_없으면_400() throws Exception {
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of(
                                "providerId", "openai",
                                "baseURL", "https://api.openai.com/v1",
                                "reasoningEffort", "ultra-mega")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void PUT_opencode_ai_model과_providerId가_다르면_400() throws Exception {
    long userId = tenantUserWithAiSettings();
    // AI 설정은 테넌트 전용 — 이 테넌트의 ai.model 을 고정한다(정리는 cleanup 이 한다).
    setTenantAiModel("openai/gpt-4o");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of(
                                "providerId",
                                "anthropic",
                                "baseURL",
                                "https://api.anthropic.com/v1"),
                            "secret",
                            Map.of("apiKey", "sk-x")))))
        .andExpect(status().isBadRequest());
  }

  /** SSRF 가드에 걸리는(=네트워크 왕복 없는) 요청 형태 실패도 저장을 막는다 — INVALID_URL. */
  @Test
  void PUT_opencode_baseURL_형식이_틀리면_400() throws Exception {
    long userId = tenantUserWithAiSettings();
    // ai.model 을 providerId 와 일치하게 고정해 둔다 — 그래야 이 400 이 "ai.model 정합성 검사"가
    // 아니라 실제로 의도한 경로(프로브의 INVALID_URL)에서 나온다는 것이 확실해진다. 고정하지
    // 않으면 시드된 ai.model 값에 따라 결과 상태 코드는 같아도(둘 다 400) 실제로 통과한 코드
    // 경로가 테스트마다 달라져, 이 테스트가 실제로 무엇을 지키는지 흐려진다.
    // AI 설정은 테넌트 전용 — 이 테넌트의 ai.model 을 고정한다(정리는 cleanup 이 한다).
    setTenantAiModel("openai/gpt-4o");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of("providerId", "openai", "baseURL", "not-a-url"),
                            "secret",
                            Map.of("apiKey", "sk-x")))))
        .andExpect(status().isBadRequest());
  }

  /**
   * Ruling #29(리뷰 라운드 1) — apiKey 를 생략한 채 baseURL 만 바꾸는 PUT 은 저장을 막지 않는다 (모델 검증만 건너뛴다) — 스펙의 "생략하면
   * 현재 값 유지"를 따르고, 막아도 얻는 게 없다(저장된 키는 이 PUT 의 승인 여부와 무관하게 어차피 새 baseURL 로 나간다). <b>반대로 {@code POST
   * /probe} 의 같은 모양(같은 baseURL 불일치 + 키 생략)은 여전히 400 이어야 한다</b> — 프로브는 그 자리에서 실제로 외부에 접속하는 별개의 행위이고,
   * 그 접속을 저장된 키로 몰래 다른 호스트에 쏠 수 없게 막는 것이 그 가드의 존재 이유이기 때문이다. 두 절반을 한 테스트에서 같이 봐야 "PUT 을 풀어주면서 실수로
   * probe 가드까지 함께 풀리지 않았는지"가 드러난다.
   */
  @Test
  void PUT은_apiKey_생략시_baseURL_변경도_허용하지만_probe는_여전히_불일치를_거부한다() throws Exception {
    long userId = tenantUserWithAiSettings();
    saveTenantCredential("opencode", "https://api.openai.com/v1", "sk-TENANT");

    // 1) PUT — baseURL 만 바꾸고 apiKey 는 생략한다. 프로브(네트워크)는 건너뛰지만, baseURL 의
    // SSRF 가드(Fix1)는 여전히 돈다 — 8.8.8.8(구글 DNS, 공인 IP 리터럴)을 써서 그 가드를 실제로
    // 통과시키면서도 DNS 조회 없이 결정적으로 검증한다.
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of("providerId", "openai", "baseURL", "https://8.8.8.8/v1")))))
        .andExpect(status().isNoContent());

    // 저장은 실제로 일어났고 비밀은 유지됐다.
    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.payload.baseURL").value("https://8.8.8.8/v1"))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"));

    // 2) 같은 모양(저장된 값 https://8.8.8.8/v1 과 다른 baseURL + 키 생략)의 POST /probe 는
    // 여전히 400 이다 — PUT 을 풀어준 것이 probe 가드까지 함께 풀지 않았다는 것을 확인한다.
    mockMvc
        .perform(
            post("/api/v1/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://9.9.9.9/v1"))))
        .andExpect(status().isBadRequest());
  }

  // -------------------------------------------------------------------------
  // 보안 리뷰 Fix1 — apiKey 생략이 baseURL 의 SSRF 가드까지 건너뛰면 안 된다
  // -------------------------------------------------------------------------

  /**
   * 리뷰 원문 시나리오 그대로: {@code ai:settings} 만 가진 테넌트 관리자가 {@code {agentType:"opencode",
   * payload:{providerId:"x", baseURL:"http://169.254.169.254/..."}, secret:{}}} 를 보낸다(화면이 아무것도 입력 안
   * 하면 실제로 이 모양을 보낸다, {@code useAiCredentialForm.ts:448}). Fix1 이전에는 apiKey 가 없다는 이유로 baseURL 검증
   * 자체를 건너뛰어 그대로 저장됐다 — 이후 ai.model 형식만 맞추면 AI_CLASSIFY 가 실제로 그 주소로 POST 했다. 지금은 저장 자체가 거부돼야 한다.
   *
   * <p><b>뮤테이션 체크</b>: 컨트롤러의 {@code validateTargetOnly(...)} 호출(및 그 결과로 만든 거부 응답)을 지우면 — 즉 예전 코드로
   * 되돌리면 — 이 테스트는 204 를 받아 RED 가 된다.
   */
  @Test
  void PUT_opencode는_apiKey_생략해도_사설링크로컬_baseURL을_거부한다() throws Exception {
    long userId = tenantUserWithAiSettings();
    // 저장 시도 전 상태를 알려진 값으로 고정해 둔다 — 아래 GET 단언이 "막혔다"를 마이그레이션
    // 시드값 추측이 아니라 실제 비교로 증명하게 한다.
    saveTenantCredential("cli-api", null, "sk-baseline");

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of(
                                "providerId",
                                "x",
                                "baseURL",
                                "http://169.254.169.254/latest/meta-data"),
                            "secret",
                            Map.of()))))
        .andExpect(status().isBadRequest());

    // 저장이 실제로 막혔다 — GET 이 여전히 저장 시도 전 상태(cli-api, sk-baseline)를 보고한다.
    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.agentType").value("cli-api"))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"));
  }

  /** 같은 시나리오를 https + 사설 IP(스킴 가드가 아니라 주소 판정 자체)로도 한 번 더 고정한다. */
  @Test
  void PUT_opencode는_apiKey_생략해도_사설대역_baseURL을_거부한다() throws Exception {
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of("providerId", "x", "baseURL", "https://10.0.0.5/v1"),
                            "secret",
                            Map.of()))))
        .andExpect(status().isBadRequest());
  }

  /**
   * Ruling #28(리뷰 라운드 1) — {@code ai.model} 에 슬래시가 없을 때(아직 opencode 형식이 아닐 때) providerId 정합성 검사를
   * 건너뛰는 완화가 실제로 막는 잠금을 고정한다. {@code sdk} 를 쓰던 테넌트의 {@code ai.model} 은 슬래시 없는 Anthropic 모델 id(예:
   * {@code claude-sonnet-4-...})다 — 엄격 검사였다면 opencode 로 <b>처음</b> 전환하는 이 PUT 자체가 항상 400 으로 막혔을
   * 것이다(ai.model 을 opencode 형식으로 바꾸려면 먼저 opencode 자격증명으로 모델을 프로브해야 하는데, 그 저장이 막혀 있는 순환 잠금).
   *
   * <p>apiKey 를 생략해 프로브(네트워크)를 건너뛰게 한다 — 이 테스트가 확인하려는 것은 "ai.model 정합성 검사가 막지 않는다"이지 "프로브가 성공한다"가
   * 아니므로, 실제 네트워크 호출 없이 그 한 가지만 결정적으로 확인한다. baseURL 은 8.8.8.8(구글 DNS, 공인 IP 리터럴)을 쓴다 — Fix1 의 SSRF
   * 가드가 apiKey 생략과 무관하게 항상 돌기 때문에(이 컨트롤러의 다른 테스트 참고), 실제 DNS 조회 없이 그 가드를 결정적으로 통과해야 이 테스트가 검증하려는
   * 지점(ai.model 정합성 완화)까지 도달한다.
   */
  @Test
  void PUT_opencode_전환시_슬래시_없는_ai_model은_막지_않는다() throws Exception {
    long userId = tenantUserWithAiSettings();
    saveTenantCredential("sdk", null, "sk-anthropic");
    // AI 설정은 테넌트 전용 — 이 테넌트의 ai.model 을 고정한다(정리는 cleanup 이 한다).
    setTenantAiModel("claude-sonnet-4-20250514");
    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of("providerId", "openai", "baseURL", "https://8.8.8.8/v1")))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.agentType").value("opencode"));
  }

  // -------------------------------------------------------------------------
  // #706 — 테넌트 전용 API 계약(web 이 의존한다)
  // -------------------------------------------------------------------------

  /**
   * 미설정 테넌트의 GET 응답 계약: {@code {agentType:"sdk", payload:{}, secretFieldNames:[],
   * configured:false}}. 옛 {@code tenantOwned} 필드는 사라졌다(web 이 {@code configured} 로 "AI 설정이 없습니다" 안내를
   * 띄운다). 완전한 플랫폼 행을 심어 두어, 그 값이 새어 나오지 않는지 (폴백이 되살아나면 agentType/secretFieldNames/configured 가
   * 바뀐다)까지 함께 본다.
   */
  @Test
  void GET_은_미설정이면_configured_false_와_빈_sdk_를_준다() throws Exception {
    plantPlatformCredential("cli-api", "sk-platform-must-not-leak");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentType").value("sdk"))
        .andExpect(jsonPath("$.payload").isEmpty())
        .andExpect(jsonPath("$.secretFieldNames").isEmpty())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.tenantOwned").doesNotExist())
        .andExpect(content().string(not(containsString("tenantOwned"))));
  }

  /**
   * {@code DELETE /api/v1/settings/ai-credential}(옛 "플랫폼 값으로 복귀")는 제거됐다 — 같은 경로에 GET/PUT 이 있어 404 가
   * 아니라 405 다. 테넌트 행을 실제로 만들어 두고, 호출 뒤에도 그대로 남아 있는지 확인한다(행이 없으면 "삭제할 게 없어 무동작"과 구분되지 않는다).
   */
  @Test
  void DELETE_는_제거돼_405이고_테넌트_값은_그대로다() throws Exception {
    saveTenantCredential("cli-api", null, "sk-tenant");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(
            delete("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isMethodNotAllowed());

    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.agentType").value("cli-api"))
        .andExpect(jsonPath("$.configured").value(true));
  }

  // -------------------------------------------------------------------------
  // PUT /settings 의 ai.model — 저장된 채팅 자격증명과 맞는지(WD-778)
  // -------------------------------------------------------------------------

  /** {@code PUT /api/v1/settings} 로 {@code ai.model} 하나를 저장한다. */
  private org.springframework.test.web.servlet.ResultActions putAiModel(long userId, String model)
      throws Exception {
    return mockMvc.perform(
        put("/api/v1/settings")
            .header("Authorization", "Bearer " + tenantToken(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("settings", Map.of("ai.model", model)))));
  }

  /**
   * Claude 계열(sdk) 자격증명이 저장된 테넌트에 opencode 형식(공급자/모델) 모델을 저장하면 400 이다 — 채팅 경로와 같은 검사·같은 문구. 수정 전에는
   * 저장은 204 로 되고 이후 모든 채팅이 「모델을 다시 선택하세요」로 실패했다.
   */
  @Test
  void PUT_settings_는_sdk_자격증명에_opencode_형식_모델이면_400이다() throws Exception {
    saveTenantCredential("sdk", null, "sk-ant-tenant");
    long userId = tenantUserWithAiSettings();

    putAiModel(userId, "anthropic/claude-sonnet-4")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(claudeModelProblem("anthropic/claude-sonnet-4")));
  }

  /** 같은 sdk 자격증명이라도 Claude 모델 이름(슬래시 없음)은 그대로 저장된다 — 검사가 정상 저장을 막지 않는다(양성 대조). */
  @Test
  void PUT_settings_는_sdk_자격증명에_Claude_모델이면_저장된다() throws Exception {
    saveTenantCredential("sdk", null, "sk-ant-tenant");
    long userId = tenantUserWithAiSettings();

    putAiModel(userId, "claude-sonnet-4").andExpect(status().isNoContent());
  }

  /**
   * opencode 자격증명이 저장돼 있으면 {@code PUT /settings} 는 모델을 검사하지 않는다 — opencode 쪽 공급자 일치·목록 확인은 네트워크
   * 프로브라 자격증명 PUT({@code OpencodePutValidator})이 지킨다(WD-778).
   */
  @Test
  void PUT_settings_는_opencode_자격증명이면_모델을_검사하지_않는다() throws Exception {
    saveTenantCredential("opencode", "https://api.openai.com/v1", "sk-tenant");
    long userId = tenantUserWithAiSettings();

    putAiModel(userId, "anthropic/claude-sonnet-4").andExpect(status().isNoContent());
    putAiModel(userId, "claude-sonnet-4").andExpect(status().isNoContent());
  }

  /** 자격증명이 아직 없으면 검사하지 않는다 — 모델을 먼저 고르고 자격증명을 나중에 넣는 순서를 막지 않는다. */
  @Test
  void PUT_settings_는_자격증명이_없으면_모델을_검사하지_않는다() throws Exception {
    long userId = tenantUserWithAiSettings();

    putAiModel(userId, "anthropic/claude-sonnet-4").andExpect(status().isNoContent());
  }

  // -------------------------------------------------------------------------
  // 채팅 자격증명 PUT 의 선택적 model — 자격증명과 ai.model 을 한 짝으로 검사·저장(WD-778)
  // -------------------------------------------------------------------------

  /** {@code PUT /api/v1/settings/ai-credential} 로 임의 바디를 보낸다(model 포함 여부는 호출부가 정한다). */
  private org.springframework.test.web.servlet.ResultActions putCredential(
      long userId, Map<String, ?> body) throws Exception {
    return mockMvc.perform(
        put("/api/v1/settings/ai-credential")
            .header("Authorization", "Bearer " + tenantToken(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  /** 지금 테넌트에 저장된 {@code ai.model} 행(없으면 null) — 기본값으로 메우지 않은 실제 저장 값. */
  private String storedTenantAiModel() {
    return inTenantFixture(() -> tenantSettingsRepository.findValue("ai.model").orElse(null));
  }

  /** Claude 계열 자격증명에 {@code model} 을 짝지을 때 채팅 경로가 내는 거부 문구(서버와 같은 {@code modelProblem}). */
  private static String claudeModelProblem(String model) {
    return new AiCredential.Sdk("", "x").modelProblem(model);
  }

  /** 지금 테넌트에 저장된 채팅 자격증명 유형. */
  private String storedCredentialType() {
    return inTenantFixture(() -> aiCredentialService.read().agentType());
  }

  /**
   * opencode 자격증명 바디 — apiKey 를 생략해 프로브(네트워크)를 건너뛴다. baseURL 은 공인 IP 리터럴(8.8.8.8)이라 SSRF 가드를 DNS 조회
   * 없이 결정적으로 통과한다(위 「PUT_opencode_전환시_슬래시_없는_ai_model은_막지_않는다」와 같은 이유).
   */
  private static Map<String, Object> opencodeBody(String providerId, String model) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("agentType", "opencode");
    body.put("payload", Map.of("providerId", providerId, "baseURL", "https://8.8.8.8/v1"));
    if (model != null) body.put("model", model);
    return body;
  }

  /**
   * 리뷰 구멍 (1) — sdk 자격증명 + 묵은 opencode 형식 모델({@code anthropic/claude-sonnet-4})에서 OpenCode(openai)
   * + {@code openai/gpt-4o} 로 바꾸는 한 번 저장. 수정 전에는 자격증명 PUT 이 <b>저장된</b> 묵은 모델과 새 공급자를 대조해 400, 모델을
   * 먼저 보내면 저장된 sdk 로 검사돼 400 이라 어느 순서로도 저장할 수 없었다. 이제 요청의 model 과 대조해 둘 다 저장된다.
   */
  @Test
  void PUT_credential_은_묵은_모델이_있어도_요청_model_과_짝으로_검사해_둘다_저장한다() throws Exception {
    saveTenantCredential("sdk", null, "sk-ant-tenant");
    setTenantAiModel("anthropic/claude-sonnet-4");
    long userId = tenantUserWithAiSettings();

    putCredential(userId, opencodeBody("openai", "openai/gpt-4o"))
        .andExpect(status().isNoContent());

    assertThat(storedCredentialType()).isEqualTo("opencode");
    assertThat(storedTenantAiModel()).isEqualTo("openai/gpt-4o");
  }

  /** OpenCode 공급자 바꾸기(anthropic → openai) + 모델 변경을 한 PUT 으로 — 요청 모델과 새 공급자가 맞으므로 저장된다. */
  @Test
  void PUT_credential_은_opencode_공급자_바꾸기와_모델을_한번에_저장한다() throws Exception {
    aiCredentialService.save(
        new AiCredentialUpsert(
            "opencode",
            Map.of("providerId", "anthropic", "baseURL", "https://8.8.8.8/v1"),
            Map.of("apiKey", "sk-tenant")),
        USER);
    setTenantAiModel("anthropic/claude-sonnet-4");
    long userId = tenantUserWithAiSettings();

    putCredential(userId, opencodeBody("openai", "openai/gpt-4o"))
        .andExpect(status().isNoContent());

    assertThat(storedTenantAiModel()).isEqualTo("openai/gpt-4o");
    mockMvc
        .perform(
            get("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.payload.providerId").value("openai"));
  }

  /** 요청 model 이 새 공급자와 어긋나면(opencode/openai + anthropic/…) 400 이고 아무것도 저장되지 않는다 — 검사가 요청 모델로 돈다. */
  @Test
  void PUT_credential_은_요청_model_이_새_공급자와_다르면_400이고_아무것도_저장하지_않는다() throws Exception {
    saveTenantCredential("sdk", null, "sk-ant-tenant");
    setTenantAiModel("claude-sonnet-4");
    long userId = tenantUserWithAiSettings();

    putCredential(userId, opencodeBody("openai", "anthropic/claude-sonnet-4"))
        .andExpect(status().isBadRequest());

    assertThat(storedCredentialType()).isEqualTo("sdk");
    assertThat(storedTenantAiModel()).isEqualTo("claude-sonnet-4");
  }

  /**
   * 리뷰 구멍 (3) — 자격증명만 OpenCode → sdk 로 바꾸는 PUT 인데 저장된 모델이 {@code openai/gpt-4o} 면 400 이다(채팅 경로와 같은
   * 문구). 수정 전에는 저장이 되고 이후 모든 채팅이 「모델을 다시 선택하세요」로 실패했다(WD-766 상태).
   */
  @Test
  void PUT_credential_만_Claude_계열로_바꾸는데_저장된_모델이_opencode_형식이면_400이고_저장하지_않는다() throws Exception {
    saveTenantCredential("opencode", "https://8.8.8.8/v1", "sk-tenant");
    setTenantAiModel("openai/gpt-4o");
    long userId = tenantUserWithAiSettings();

    putCredential(
            userId,
            Map.of("agentType", "sdk", "payload", Map.of(), "secret", Map.of("apiKey", "sk-ant-x")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(claudeModelProblem("openai/gpt-4o")));

    assertThat(storedCredentialType()).isEqualTo("opencode");
    assertThat(storedTenantAiModel()).isEqualTo("openai/gpt-4o");
  }

  /** 같은 OpenCode → sdk 전환이라도 Claude 모델을 함께 보내면 한 번에 둘 다 저장된다(화면의 정상 경로). */
  @Test
  void PUT_credential_은_Claude_계열_전환과_Claude_모델을_한번에_저장한다() throws Exception {
    saveTenantCredential("opencode", "https://8.8.8.8/v1", "sk-tenant");
    setTenantAiModel("openai/gpt-4o");
    long userId = tenantUserWithAiSettings();

    putCredential(
            userId,
            Map.of(
                "agentType",
                "sdk",
                "payload",
                Map.of(),
                "secret",
                Map.of("apiKey", "sk-ant-x"),
                "model",
                "claude-sonnet-4"))
        .andExpect(status().isNoContent());

    assertThat(storedCredentialType()).isEqualTo("sdk");
    assertThat(storedTenantAiModel()).isEqualTo("claude-sonnet-4");
  }

  /**
   * 자격증명 + 모델을 함께 보냈는데 모델 검사가 실패하면(sdk + {@code anthropic/claude-sonnet-4}) 400 이고 <b>자격증명도 모델도</b>
   * 저장되지 않는다 — 둘 중 하나만 남으면 그 자체로 채팅이 깨지는 어긋난 짝이다.
   */
  @Test
  void PUT_credential_과_model_중_모델_검사가_실패하면_400이고_둘다_저장하지_않는다() throws Exception {
    saveTenantCredential("opencode", "https://8.8.8.8/v1", "sk-tenant");
    setTenantAiModel("openai/gpt-4o");
    long userId = tenantUserWithAiSettings();

    putCredential(
            userId,
            Map.of(
                "agentType",
                "sdk",
                "payload",
                Map.of(),
                "secret",
                Map.of("apiKey", "sk-ant-x"),
                "model",
                "anthropic/claude-sonnet-4"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(claudeModelProblem("anthropic/claude-sonnet-4")));

    assertThat(storedCredentialType()).isEqualTo("opencode");
    assertThat(storedTenantAiModel()).isEqualTo("openai/gpt-4o");
  }

  /** 빈 model 은 opencode 검증보다 먼저 400 이고 아무것도 저장하지 않는다(PUT /settings 와 같은 문구). */
  @Test
  void PUT_credential_의_빈_model_은_400이고_저장하지_않는다() throws Exception {
    saveTenantCredential("sdk", null, "sk-ant-tenant");
    setTenantAiModel("claude-sonnet-4");
    long userId = tenantUserWithAiSettings();

    putCredential(userId, opencodeBody("openai", "  "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(SettingsService.MSG_MODEL_BLANK));

    assertThat(storedCredentialType()).isEqualTo("sdk");
    assertThat(storedTenantAiModel()).isEqualTo("claude-sonnet-4");
  }

  /**
   * 함께 저장한 모델은 {@code PUT /settings} 로 저장한 것과 같은 AI 설정 감사(「AI 설정 수정 (바뀐 항목: 모델)」)를 남긴다 — 저장 통로가
   * 바뀌어도 감사가 빠지지 않는다.
   */
  @Test
  void PUT_credential_과_함께_저장한_모델은_AI_설정_감사를_남긴다() throws Exception {
    saveTenantCredential("sdk", null, "sk-ant-tenant");
    setTenantAiModel("anthropic/claude-sonnet-4");
    long userId = tenantUserWithAiSettings();

    putCredential(userId, opencodeBody("openai", "openai/gpt-4o"))
        .andExpect(status().isNoContent());

    Integer aiSettingsAudits =
        inTenantFixture(
            () ->
                dsl.fetchCount(
                    dsl.selectFrom("audit_log")
                        .where(
                            "user_id = ? AND resource = ?",
                            userId,
                            com.smartfirehub.settings.service.SettingsAuditRecorder
                                .RESOURCE_AI_SETTINGS)));
    assertThat(aiSettingsAudits).isEqualTo(1);
  }
}
