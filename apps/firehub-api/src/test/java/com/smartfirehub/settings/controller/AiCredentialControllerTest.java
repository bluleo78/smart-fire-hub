package com.smartfirehub.settings.controller;

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
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
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
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code AiCredentialController}/{@code PlatformAiCredentialController} 통합 테스트(Task 7).
 *
 * <p><b>실제 {@code OpencodeProbeService} 빈을 쓴다 — mock 하지 않는다.</b> 이 파일의 핵심 단언
 * 두 개("프로브는 평면을 건너 폴백하지 않는다", "baseURL 이 저장된 값과 다르면 apiKey 를
 * 요구한다")는 그 서비스 <b>내부</b>의 실제 분기(재사용할 저장된 키가 없음/baseURL 불일치)가
 * 실행돼야 의미가 있다 — mock 으로 그 예외를 흉내 내면 "컨트롤러가 예외를 400 으로 옮기는지"만
 * 증명하지 "컨트롤러가 스스로 두 평면을 해석해 키를 새어 보내지 않는지"는 증명하지 못한다. 다행히
 * 두 시나리오 모두 {@code resolveApiKey} 의 사전 검증 단계에서 끝나 실제 네트워크 호출 전에
 * {@code IllegalArgumentException} 이 던져지므로(해당 서비스의 javadoc 참고) 네트워크 없이도
 * 빠르고 결정적이다. 같은 이유로 Reason→상태 매핑의 <b>일부</b>(INVALID_URL/SCHEME_NOT_ALLOWED/
 * PORT_NOT_ALLOWED/BLOCKED_ADDRESS)도 여기서 실제 서비스로 검증한다 — 전부 SSRF 가드에서
 * 걸려 DNS/소켓 연결 전에 끝나는 것들만 골랐다. 나머지(도달 불가/타임아웃/공급자 거부 등, 실제
 * 네트워크가 필요한 값들)는 {@link OpencodeCredentialValidationTest}(순수 유닛, 12개 값 전수)와
 * mock 기반의 {@link AiCredentialProbeStatusMappingTest} 가 다룬다.
 */
@AutoConfigureMockMvc
class AiCredentialControllerTest extends IntegrationTestBase {

  private static final String CREDENTIAL_KEY = "ai.credential";
  private static final Long USER = null;

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private AiCredentialService aiCredentialService;
  @Autowired private SettingsService settingsService;
  @Autowired private TenantSettingsRepository tenantSettingsRepository;
  @Autowired private DSLContext dsl;

  private String platformCredentialOriginal;
  private Long platformCredentialOriginalUpdatedBy;
  private final List<Long> createdUserIds = new ArrayList<>();
  private final List<Long> createdPlatformRoleIds = new ArrayList<>();

  @BeforeEach
  void captureOriginalPlatformValue() {
    platformCredentialOriginal = SettingsTestSupport.rawSystemSettingValue(dsl, CREDENTIAL_KEY);
    var row = dsl.fetchOne("select updated_by from system_settings where key = ?", CREDENTIAL_KEY);
    platformCredentialOriginalUpdatedBy = row == null ? null : row.get(0, Long.class);
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
    SettingsTestSupport.restoreSystemSettingValue(dsl, CREDENTIAL_KEY, platformCredentialOriginal);
    // restoreSystemSettingValue 는 value 컬럼만 되돌린다(SettingsTestSupport 계약) — updated_by 는
    // 그대로 남는다. 플랫폼 PUT 테스트가 실제 생성한 테스트 사용자를 인증 주체로 써서 저장하면
    // 그 사용자 id 가 updated_by 로 남고, 아래에서 그 사용자를 지우려 할 때
    // system_settings_updated_by_fkey 위반으로 막힌다 — 그래서 여기서 updated_by 도 함께 되돌린다.
    if (platformCredentialOriginal != null) {
      dsl.execute(
          "update system_settings set updated_by = ? where key = ?",
          platformCredentialOriginalUpdatedBy,
          CREDENTIAL_KEY);
    }

    for (Long userId : createdUserIds) {
      inTenantFixture(() -> dsl.execute("delete from user_role where user_id = ?", userId));
      dsl.execute("delete from platform_user_role where user_id = ?", userId);
      TenantRlsTestSupport.deleteUser(dsl, userId);
    }
    for (Long roleId : createdPlatformRoleIds) {
      dsl.execute("delete from platform_role where id = ?", roleId); // role_permission/user_role 은 CASCADE
    }
  }

  // -------------------------------------------------------------------------
  // 픽스처 헬퍼
  // -------------------------------------------------------------------------

  private void saveTenantCredential(String agentType, String baseURL, String apiKey) {
    aiCredentialService.save(upsert(agentType, baseURL, apiKey), USER, false);
  }

  private void savePlatformCredential(String agentType, String baseURL, String apiKey) {
    aiCredentialService.save(upsert(agentType, baseURL, apiKey), USER, true);
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
        TenantRlsTestSupport.insertUserWithPassword(dsl, "p7t7-admin-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);
    inTenantFixture(() -> dsl.execute("insert into user_role (user_id, role_id) values (?, 1)", userId));
    return userId;
  }

  /** 어떤 테넌트 롤도 없는 사용자 — ai:settings 를 포함해 아무 권한도 없다. */
  private long tenantUserWithoutPermission() {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, "p7t7-noperm-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);
    return userId;
  }

  private String tenantToken(long userId) {
    return jwtTokenProvider.generateAccessToken(userId, "u" + userId, DEFAULT_TEST_TENANT_ID);
  }

  /** 지정한 권한 코드만 가진 플랫폼 사용자. 임시 platform_role 을 만들어 그 권한만 붙인다. */
  private long platformUserWithPermissions(String... codes) {
    long userId =
        TenantRlsTestSupport.insertUserWithPassword(dsl, "p7t7-plat-" + System.nanoTime(), "{noop}x");
    createdUserIds.add(userId);

    Long roleId =
        dsl.insertInto(DSL.table(DSL.name("platform_role")))
            .set(DSL.field(DSL.name("platform_role", "name"), String.class), "P7T7_" + System.nanoTime())
            .returning(DSL.field(DSL.name("platform_role", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("platform_role", "id"), Long.class));
    createdPlatformRoleIds.add(roleId);

    for (String code : codes) {
      dsl.execute(
          "insert into platform_role_permission (platform_role_id, permission_id)"
              + " select ?, id from permission where code = ?",
          roleId,
          code);
    }
    dsl.execute(
        "insert into platform_user_role (user_id, platform_role_id) values (?, ?)", userId, roleId);
    return userId;
  }

  private String platformToken(long userId) {
    return jwtTokenProvider.generatePlatformAccessToken(userId, "op" + userId);
  }

  private String json(Map<String, ?> body) throws Exception {
    return new ObjectMapper().writeValueAsString(body);
  }

  // -------------------------------------------------------------------------
  // 프로브 — 평면 교차 폴백 금지 (브리프 Step 1의 핵심 두 시나리오)
  // -------------------------------------------------------------------------

  /**
   * 테넌트가 opencode 로 재정의하지 않은 상태에서 apiKey 를 생략하면, 두 평면을 해석하는 폴백이
   * 있었다면 플랫폼의 apiKey 를 테넌트가 지정한 임의 baseURL(evil.example)로 내보냈을 것이다.
   *
   * <p><b>뮤테이션 체크(브리프 Step 5).</b> 이 컨트롤러가 apiKey 를 {@code opencodeProbeService
   * .probe(...)} 에 그대로 넘기지 않고 대신 {@code settingsService.getValue(...)} 같은 두 평면
   * 해석기로 직접 채워 넘기도록 바꾸면, 그 해석은 테넌트 오버라이드가 없으니 플랫폼 값(sk-PLATFORM)
   * 으로 떨어지고 프로브가 거부 없이 진행돼(또는 다른 상태로) 이 단언이 깨진다 — RED.
   */
  @Test
  void 프로브는_평면을_건너_폴백하지_않는다() throws Exception {
    savePlatformCredential("opencode", "https://api.openai.com/v1", "sk-PLATFORM");
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
        .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
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

  @Test
  void DELETE_는_권한이_없으면_403() throws Exception {
    long userId = tenantUserWithoutPermission();

    mockMvc
        .perform(
            delete("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isForbidden());
  }

  // -------------------------------------------------------------------------
  // GET / DELETE 동작
  // -------------------------------------------------------------------------

  @Test
  void GET_은_비밀_값을_내보내지_않는다() throws Exception {
    saveTenantCredential("sdk", null, "sk-secret");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"))
        .andExpect(jsonPath("$.tenantOwned").value(true))
        .andExpect(content().string(not(containsString("sk-secret"))));
  }

  @Test
  void DELETE_후에는_플랫폼_값을_상속한다() throws Exception {
    savePlatformCredential("sdk", null, "sk-platform");
    saveTenantCredential("cli-api", null, "sk-tenant");
    long userId = tenantUserWithAiSettings();

    mockMvc
        .perform(delete("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
        .andExpect(jsonPath("$.agentType").value("sdk"))
        .andExpect(jsonPath("$.tenantOwned").value(false));
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
    String original = SettingsTestSupport.rawSystemSettingValue(dsl, "ai.model");
    try {
      settingsService.updatePlatformSettings(Map.of("ai.model", "openai/gpt-4o"), null);

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
                              Map.of("providerId", "anthropic", "baseURL", "https://api.anthropic.com/v1"),
                              "secret",
                              Map.of("apiKey", "sk-x")))))
          .andExpect(status().isBadRequest());
    } finally {
      SettingsTestSupport.restoreSystemSettingValue(dsl, "ai.model", original);
    }
  }

  /** SSRF 가드에 걸리는(=네트워크 왕복 없는) 요청 형태 실패도 저장을 막는다 — INVALID_URL. */
  @Test
  void PUT_opencode_baseURL_형식이_틀리면_400() throws Exception {
    long userId = tenantUserWithAiSettings();
    // ai.model 을 providerId 와 일치하게 고정해 둔다 — 그래야 이 400 이 "ai.model 정합성 검사"가
    // 아니라 실제로 의도한 경로(프로브의 INVALID_URL)에서 나온다는 것이 확실해진다. 고정하지
    // 않으면 시드된 ai.model 값에 따라 결과 상태 코드는 같아도(둘 다 400) 실제로 통과한 코드
    // 경로가 테스트마다 달라져, 이 테스트가 실제로 무엇을 지키는지 흐려진다.
    String original = SettingsTestSupport.rawSystemSettingValue(dsl, "ai.model");
    try {
      settingsService.updatePlatformSettings(Map.of("ai.model", "openai/gpt-4o"), null);

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
    } finally {
      SettingsTestSupport.restoreSystemSettingValue(dsl, "ai.model", original);
    }
  }

  /**
   * Ruling #29(리뷰 라운드 1) — 테넌트 PUT 도 플랫폼 PUT 과 대칭이어야 한다. apiKey 를 생략한 채
   * baseURL 만 바꾸는 PUT 은 저장을 막지 않는다(모델 검증만 건너뛴다) — 스펙의 "생략하면 현재 값
   * 유지"는 평면을 가리지 않고, 막아도 얻는 게 없다(저장된 키는 이 PUT 의 승인 여부와 무관하게
   * 어차피 새 baseURL 로 나간다). <b>반대로 {@code POST /probe} 의 같은 모양(같은 baseURL 불일치 +
   * 키 생략)은 여전히 400 이어야 한다</b> — 프로브는 그 자리에서 실제로 외부에 접속하는 별개의
   * 행위이고, 그 접속을 저장된 키로 몰래 다른 호스트에 쏠 수 없게 막는 것이 그 가드의 존재
   * 이유이기 때문이다. 두 절반을 한 테스트에서 같이 봐야 "PUT 을 풀어주면서 실수로 probe 가드까지
   * 함께 풀리지 않았는지"가 드러난다.
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
        .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
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
   * 리뷰 원문 시나리오 그대로: {@code ai:settings} 만 가진 테넌트 관리자가
   * {@code {agentType:"opencode", payload:{providerId:"x", baseURL:"http://169.254.169.254/..."},
   * secret:{}}} 를 보낸다(화면이 아무것도 입력 안 하면 실제로 이 모양을 보낸다,
   * {@code useAiCredentialForm.ts:448}). Fix1 이전에는 apiKey 가 없다는 이유로 baseURL 검증
   * 자체를 건너뛰어 그대로 저장됐다 — 이후 ai.model 형식만 맞추면 AI_CLASSIFY 가 실제로 그 주소로
   * POST 했다. 지금은 저장 자체가 거부돼야 한다.
   *
   * <p><b>뮤테이션 체크</b>: 컨트롤러의 {@code validateTargetOnly(...)} 호출(및 그 결과로 만든
   * 거부 응답)을 지우면 — 즉 예전 코드로 되돌리면 — 이 테스트는 204 를 받아 RED 가 된다.
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
                            Map.of("providerId", "x", "baseURL", "http://169.254.169.254/latest/meta-data"),
                            "secret",
                            Map.of()))))
        .andExpect(status().isBadRequest());

    // 저장이 실제로 막혔다 — GET 이 여전히 저장 시도 전 상태(cli-api, sk-baseline)를 보고한다.
    mockMvc
        .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
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

  /** 플랫폼 평면의 거울 — 플랫폼 관리자도 같은 가드를 받는다. */
  @Test
  void 플랫폼_PUT_opencode는_apiKey_생략해도_사설대역_baseURL을_거부한다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:write", "platform:settings:read");
    savePlatformCredential("cli-api", null, "sk-baseline");

    mockMvc
        .perform(
            put("/api/platform/settings/ai-credential")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of("providerId", "x", "baseURL", "http://169.254.169.254/latest/meta-data"),
                            "secret",
                            Map.of()))))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/platform/settings/ai-credential").header("Authorization", "Bearer " + platformToken(userId)))
        .andExpect(jsonPath("$.agentType").value("cli-api"))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"));
  }

  /**
   * Ruling #28(리뷰 라운드 1) — {@code ai.model} 에 슬래시가 없을 때(아직 opencode 형식이 아닐
   * 때) providerId 정합성 검사를 건너뛰는 완화가 실제로 막는 잠금을 고정한다. {@code sdk} 를
   * 쓰던 테넌트의 {@code ai.model} 은 슬래시 없는 Anthropic 모델 id(예:
   * {@code claude-sonnet-4-...})다 — 엄격 검사였다면 opencode 로 <b>처음</b> 전환하는 이 PUT
   * 자체가 항상 400 으로 막혔을 것이다(ai.model 을 opencode 형식으로 바꾸려면 먼저 opencode
   * 자격증명으로 모델을 프로브해야 하는데, 그 저장이 막혀 있는 순환 잠금).
   *
   * <p>apiKey 를 생략해 프로브(네트워크)를 건너뛰게 한다 — 이 테스트가 확인하려는 것은
   * "ai.model 정합성 검사가 막지 않는다"이지 "프로브가 성공한다"가 아니므로, 실제 네트워크
   * 호출 없이 그 한 가지만 결정적으로 확인한다. baseURL 은 8.8.8.8(구글 DNS, 공인 IP 리터럴)을
   * 쓴다 — Fix1 의 SSRF 가드가 apiKey 생략과 무관하게 항상 돌기 때문에(이 컨트롤러의 다른 테스트
   * 참고), 실제 DNS 조회 없이 그 가드를 결정적으로 통과해야 이 테스트가 검증하려는 지점(ai.model
   * 정합성 완화)까지 도달한다.
   */
  @Test
  void PUT_opencode_전환시_슬래시_없는_ai_model은_막지_않는다() throws Exception {
    long userId = tenantUserWithAiSettings();
    saveTenantCredential("sdk", null, "sk-anthropic");
    String original = SettingsTestSupport.rawSystemSettingValue(dsl, "ai.model");
    try {
      settingsService.updatePlatformSettings(Map.of("ai.model", "claude-sonnet-4-20250514"), null);

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
          .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer " + tenantToken(userId)))
          .andExpect(jsonPath("$.agentType").value("opencode"));
    } finally {
      SettingsTestSupport.restoreSystemSettingValue(dsl, "ai.model", original);
    }
  }

  // -------------------------------------------------------------------------
  // 플랫폼 평면
  // -------------------------------------------------------------------------

  @Test
  void 플랫폼_응답에는_tenantOwned_가_없다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:read");

    mockMvc
        .perform(get("/api/platform/settings/ai-credential").header("Authorization", "Bearer " + platformToken(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantOwned").doesNotExist())
        // doesNotExist() 는 "tenantOwned":null 도 통과시키므로, 문자열 자체에 그 키가
        // 없다는 것까지 별도로 단언한다(응답 record 에 필드가 아예 없어야 이게 통과한다).
        .andExpect(content().string(not(containsString("tenantOwned"))));
  }

  @Test
  void 플랫폼_GET은_읽기_권한이_없으면_403() throws Exception {
    long userId = platformUserWithPermissions(); // 권한 없음

    mockMvc
        .perform(get("/api/platform/settings/ai-credential").header("Authorization", "Bearer " + platformToken(userId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void 플랫폼_PUT은_쓰기_권한이_없으면_403() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:read");

    mockMvc
        .perform(
            put("/api/platform/settings/ai-credential")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("agentType", "sdk", "payload", Map.of(), "secret", Map.of()))))
        .andExpect(status().isForbidden());
  }

  /** 프로브는 읽기 권한만으로는 안 된다 — 쓰기 권한과의 구분이 실제로 갈리는 지점(테넌트 평면엔 없다). */
  @Test
  void 플랫폼_프로브는_읽기_권한만으로는_403() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:read");

    mockMvc
        .perform(
            post("/api/platform/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://api.openai.com/v1", "apiKey", "sk-x"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 플랫폼_프로브는_쓰기_권한이면_apiKey를_줬을_때_동작한다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:write");

    mockMvc
        .perform(
            post("/api/platform/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://10.0.0.5/v1", "apiKey", "sk-x"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false));
  }

  /**
   * Ruling #24 — 플랫폼 평면은 상위 평면이 없어 apiKey 생략을 허용하지 않는다. apiKey 를 생략한 채
   * 보내면, 폴백을 시도조차 하지 않고 곧바로 400 이어야 한다(프로브 자체를 호출하지 않는다 —
   * 그래서 baseURL 이 어떤 값이든 네트워크로 나가지 않는다).
   */
  @Test
  void 플랫폼_프로브는_apiKey_생략을_허용하지_않는다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:write");

    mockMvc
        .perform(
            post("/api/platform/settings/ai-credential/probe")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseURL", "https://api.openai.com/v1"))))
        .andExpect(status().isBadRequest());
  }

  /**
   * PUT 은 프로브(Ruling #24)와 다르다 — apiKey 를 생략해도 저장 자체는 막지 않는다(스펙
   * "PUT 의 비밀 의미: 생략하면 유지"는 평면을 가리지 않는다). 대신 저장 전 모델 검증(프로브)만
   * 건너뛴다 — 플랫폼 평면에는 저장된 apiKey 를 복호화해 재사용할 접근자가 없기 때문이다
   * ({@code PlatformAiCredentialController#validateOpencode} javadoc 참고).
   */
  @Test
  void 플랫폼_PUT_opencode는_apiKey_생략시_프로브만_건너뛰고_기존_값을_유지한다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:write", "platform:settings:read");
    savePlatformCredential("opencode", "https://api.openai.com/v1", "sk-platform-existing");

    // baseURL 만 바꾸고 apiKey 는 생략한다. baseURL 은 8.8.8.8(구글 DNS, 공인 IP 리터럴)을 쓴다 —
    // 이전에는 여기서 사설 대역(10.0.0.5)을 써서 "프로브가 실제로 불렸다면 막혔겠지만 건너뛰므로
    // 204"를 보였는데, Fix1 이후로는 baseURL 의 SSRF 가드가 apiKey 생략과 무관하게 항상 돌기
    // 때문에 그 값 자체가 이제 (프로브 여부와 무관하게) 거부된다. 이 테스트가 실제로 보고 싶은
    // 것은 "apiKey 생략이 (네트워크가 필요한) 모델 프로브만 건너뛴다"이므로, 가드를 통과하는
    // 공인 주소로 바꿔 그 지점만 결정적으로 확인한다 — 사설 대역이 거부되는 것은 별도 테스트
    // (플랫폼_PUT_opencode는_apiKey_생략해도_사설대역_baseURL을_거부한다)가 고정한다.
    mockMvc
        .perform(
            put("/api/platform/settings/ai-credential")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "agentType",
                            "opencode",
                            "payload",
                            Map.of("providerId", "openai", "baseURL", "https://8.8.8.8/v1")))))
        .andExpect(status().isNoContent());

    // 저장은 실제로 일어났고(save() 의 "생략=유지" 규칙대로) 비밀은 그대로 살아 있다.
    mockMvc
        .perform(get("/api/platform/settings/ai-credential").header("Authorization", "Bearer " + platformToken(userId)))
        .andExpect(jsonPath("$.agentType").value("opencode"))
        .andExpect(jsonPath("$.payload.baseURL").value("https://8.8.8.8/v1"))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"));
  }

  /** 플랫폼 평면에는 DELETE 라우트가 없다 — 405(매핑 자체가 없다는 뜻, 404 가 아니라 405 인 이유는 GET/PUT/POST 가 같은 경로에 매핑돼 있기 때문이다). */
  @Test
  void 플랫폼_DELETE는_없다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:write", "platform:settings:read");

    mockMvc
        .perform(
            delete("/api/platform/settings/ai-credential")
                .header("Authorization", "Bearer " + platformToken(userId)))
        .andExpect(status().isMethodNotAllowed());
  }

  /** 플랫폼 저장은 platform_settings(system_settings) 평면에 쓴다 — 실제로 조회 가능한지까지 확인한다. */
  @Test
  void 플랫폼_PUT은_저장하고_조회된다() throws Exception {
    long userId = platformUserWithPermissions("platform:settings:write", "platform:settings:read");

    mockMvc
        .perform(
            put("/api/platform/settings/ai-credential")
                .header("Authorization", "Bearer " + platformToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(Map.of("agentType", "cli-api", "payload", Map.of(), "secret", Map.of("apiKey", "sk-p")))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/platform/settings/ai-credential").header("Authorization", "Bearer " + platformToken(userId)))
        .andExpect(jsonPath("$.agentType").value("cli-api"))
        .andExpect(jsonPath("$.secretFieldNames[0]").value("apiKey"));
  }
}
