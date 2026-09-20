package com.smartfirehub.settings.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.platform.controller.PlatformAiCredentialController;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import com.smartfirehub.settings.repository.SettingsRepository;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.OpencodeProbeService;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult;
import com.smartfirehub.settings.service.OpencodeProbeService.ProbeResult.Reason;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.tenant.repository.MembershipRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code OpencodeProbeService} 를 mock 해 <b>실제 네트워크가 필요한</b> {@link Reason} 값(도달
 * 불가/타임아웃/공급자 거부 등)이 PUT 의 HTTP 상태로 정확히 옮겨지는지 확인한다.
 *
 * <p>{@link AiCredentialControllerTest} 는 실제 {@code OpencodeProbeService} 로 SSRF 가드가
 * 네트워크 없이 걸러내는 4개 Reason(INVALID_URL/SCHEME_NOT_ALLOWED/PORT_NOT_ALLOWED/
 * BLOCKED_ADDRESS)을 검증하고, {@link OpencodeCredentialValidationTest} 가 12개 값 전수를 순수
 * 로직으로 검증한다. 이 파일은 그 둘 사이 — "실제 HTTP 요청이 mock 이 준 Reason 을 진짜로 그
 * 상태 코드로 응답하는가"를 확인해, {@link OpencodeCredentialValidation#statusFor} 가 실제로
 * 컨트롤러에서 호출되고 있다는 배선을 증명한다(순수 유닛 테스트만으로는 그 호출부가 실제로
 * 연결됐는지 보장하지 못한다).
 */
@WebMvcTest({AiCredentialController.class, PlatformAiCredentialController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AiCredentialProbeStatusMappingTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AiCredentialService aiCredentialService;
  @MockitoBean private OpencodeProbeService opencodeProbeService;
  @MockitoBean private SettingsService settingsService;
  @MockitoBean private SettingsRepository settingsRepository;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private PlatformRoleRepository platformRoleRepository;
  @MockitoBean private MembershipRepository membershipRepository;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /**
   * 보안 리뷰 Fix1 — {@code validateOpencode} 가 이제 apiKey 유무와 무관하게 {@code
   * opencodeProbeService.validateTargetOnly(...)} 를 항상 부른다({@code AiCredentialController}
   * javadoc 참고). 이 클래스는 {@code OpencodeProbeService} 전체를 mock 하므로(실제 SSRF 가드는
   * {@code AiCredentialControllerTest} 가 진짜 서비스로 검증한다), 스텁하지 않으면 mock 이
   * {@code null} 을 돌려줘 컨트롤러가 {@code targetCheck.ok()} 에서 NPE 를 낸다 — 이 파일의
   * 관심사(Reason→상태 매핑)와 무관한 실패다. 기본값을 OK 로 깔아 두고, 개별 테스트가 필요하면
   * 다시 스텁해 덮어쓴다.
   *
   * <p>{@code TargetCheck} 는 생성자가 {@code private} 이다(가드를 통과하지 않은 인스턴스를
   * 만들 수 없게 하려는 의도 — {@code OpencodeProbeService.TargetCheck} javadoc 참고). 그래서
   * 여기서는 값을 직접 만들지 않고 mock 으로 {@code ok()=true} 만 흉내 낸다.
   */
  @BeforeEach
  void stubTargetValidationOk() {
    OpencodeProbeService.TargetCheck okCheck =
        org.mockito.Mockito.mock(OpencodeProbeService.TargetCheck.class);
    when(okCheck.ok()).thenReturn(true);
    when(opencodeProbeService.validateTargetOnly(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(okCheck);
  }

  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, 1L, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  /** 플랫폼 토큰 인증 mock — {@code JwtAuthenticationFilter} 가 플랫폼 분기에서는 {@code
   * platformRoleRepository} 로만 권한을 읽는다({@code permissionService} 는 타지 않는다). */
  private void mockPlatformAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("platform-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, true)));
    when(platformRoleRepository.findPlatformPermissionCodes(1L)).thenReturn(Set.of(permissions));
  }

  private String content(Map<String, Object> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }

  private Map<String, Object> opencodeBody() {
    return Map.of(
        "agentType",
        "opencode",
        "payload",
        Map.of("providerId", "openai", "baseURL", "https://api.openai.com/v1"),
        "secret",
        Map.of("apiKey", "sk-x"));
  }

  /** 도달 불가 → 502. 브리프 Step 1의 {@code validOpencodeWithUnreachableHost()} 시나리오와 같은 값이다. */
  @Test
  void PUT_도달불가는_502() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getValue("ai.model")).thenReturn(Optional.empty());
    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(false, List.of(), "공급자에 연결할 수 없습니다", Reason.UNREACHABLE));

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(opencodeBody())))
        .andExpect(status().isBadGateway());
  }

  /**
   * 위 테스트는 <b>테넌트</b> 컨트롤러만 지난다 — {@code PlatformAiCredentialController} 의
   * {@code statusFor(...)} 호출부는 이 테스트가 없으면 전혀 exercise 되지 않는다(플랫폼 PUT 이
   * 실제로 실패하는 프로브까지 도달하는 다른 테스트가 없었다 — apiKey 생략 시나리오는 프로브
   * 자체를 건너뛰고, cli-api 저장 테스트는 opencode 가 아니다). 이 테스트가 그 배선을 증명한다.
   */
  @Test
  void 플랫폼_PUT_도달불가는_502() throws Exception {
    mockPlatformAuth("platform:settings:write");
    when(settingsRepository.getValue("ai.model")).thenReturn(Optional.empty());
    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(false, List.of(), "공급자에 연결할 수 없습니다", Reason.UNREACHABLE));

    mockMvc
        .perform(
            put("/api/platform/settings/ai-credential")
                .header("Authorization", "Bearer platform-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(opencodeBody())))
        .andExpect(status().isBadGateway());
  }

  @Test
  void PUT_타임아웃은_504() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getValue("ai.model")).thenReturn(Optional.empty());
    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(false, List.of(), "타임아웃", Reason.TIMEOUT));

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(opencodeBody())))
        .andExpect(status().isGatewayTimeout());
  }

  @Test
  void PUT_공급자_거부는_422() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getValue("ai.model")).thenReturn(Optional.empty());
    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(false, List.of(), "공급자가 거부했습니다", Reason.PROVIDER_REJECTED));

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(opencodeBody())))
        .andExpect(status().isUnprocessableEntity());
  }

  /**
   * 뮤테이션 체크: 서로 다른 두 Reason(UNREACHABLE=502, PROVIDER_REJECTED=422)이 같은 상태로
   * 뭉개지면 위 두 테스트 중 하나가 바로 RED 가 된다 — 이 테스트는 그 대비가 실제로 서로 다른
   * 값임을 한 곳에서 다시 확인한다.
   */
  @Test
  void 서로_다른_reason은_서로_다른_상태다() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getValue("ai.model")).thenReturn(Optional.empty());

    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(false, List.of(), "unreachable", Reason.UNREACHABLE));
    int unreachableStatus =
        mockMvc
            .perform(
                put("/api/v1/settings/ai-credential")
                    .header("Authorization", "Bearer valid-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content(opencodeBody())))
            .andReturn()
            .getResponse()
            .getStatus();

    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(false, List.of(), "rejected", Reason.PROVIDER_REJECTED));
    int rejectedStatus =
        mockMvc
            .perform(
                put("/api/v1/settings/ai-credential")
                    .header("Authorization", "Bearer valid-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content(opencodeBody())))
            .andReturn()
            .getResponse()
            .getStatus();

    org.assertj.core.api.Assertions.assertThat(unreachableStatus).isNotEqualTo(rejectedStatus);
  }

  /**
   * 프로브 성공 + 모델이 목록에 있으면 저장으로 진행한다(204) — 실패 경로만 보다가 "항상 실패
   * 응답을 반환하도록 뮤테이션해도 초록"이 되는 함정을 막는다.
   */
  @Test
  void PUT_프로브가_성공하고_모델이_목록에_있으면_저장된다() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/gpt-4o"));
    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(true, List.of("gpt-4o"), null, Reason.OK));

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(opencodeBody())))
        .andExpect(status().isNoContent());
  }

  /**
   * 테넌트 PUT 성공 경로가 <b>실제로 테넌트 평면</b>({@code save(..., false)})으로 저장을
   * 요청하는지 확인한다.
   *
   * <p><b>왜 필요한가(리뷰 라운드 1, 뮤테이션으로 실증됨).</b> {@code AiCredentialController
   * .put()} 의 {@code aiCredentialService.save(toUpsert(request), userId, false)} 를
   * {@code true} 로 바꿔도(=테넌트 관리자의 저장이 {@code system_settings} 로 가서 <b>전
   * 테넌트</b>에 적용된다) 이 파일에 있던 기존 PUT 성공 테스트는 전부 opencode 400/4xx 경로
   * 아니면 상태 코드(204)만 보고 있어 그 뮤테이션을 잡지 못했다. {@code sdk} 처럼 프로브가
   * 필요 없는 유형으로 가장 단순한 성공 경로를 만들고, 저장 호출의 세 번째 인자를 직접
   * 단언한다.
   */
  @Test
  void PUT_은_테넌트_평면에_저장한다() throws Exception {
    mockAuth("ai:settings");

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(Map.of("agentType", "sdk", "payload", Map.of(), "secret", Map.of("apiKey", "sk-x")))))
        .andExpect(status().isNoContent());

    verify(aiCredentialService).save(any(), any(), eq(false));
  }

  /**
   * {@code ai:read} 는 실재하는 권한 코드다(V12, AI 세션 조회용) — {@code ai:settings} 와는
   * 다른 리소스이고 더 널리 부여돼 있다. 통합 테스트 픽스처는 "ADMIN(전부)" 아니면 "무권한"
   * 둘 중 하나만 만들어 왔기 때문에, {@code @RequirePermission("ai:settings")} 를
   * {@code "ai:read"} 로 바꿔도(전혀 다른 리소스 권한으로) 그 사이를 지나는 어떤 테스트도
   * 잡지 못했다 — 이 두 테스트가 그 틈을 메운다.
   */
  @Test
  void GET_은_ai_read_권한만으로는_403() throws Exception {
    mockAuth("ai:read");

    mockMvc
        .perform(get("/api/v1/settings/ai-credential").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void DELETE_는_ai_read_권한만으로는_403() throws Exception {
    mockAuth("ai:read");

    mockMvc
        .perform(delete("/api/v1/settings/ai-credential").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  /** 프로브가 성공했지만 저장된 모델이 목록에 없으면 422 — save() 를 절대 부르지 않는다. */
  @Test
  void PUT_모델이_목록에_없으면_422이고_저장하지_않는다() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/does-not-exist"));
    when(opencodeProbeService.probe(any(OpencodeProbeService.TargetCheck.class), any()))
        .thenReturn(new ProbeResult(true, List.of("gpt-4o"), null, Reason.OK));

    mockMvc
        .perform(
            put("/api/v1/settings/ai-credential")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(opencodeBody())))
        .andExpect(status().isUnprocessableEntity());

    org.mockito.Mockito.verify(aiCredentialService, org.mockito.Mockito.never())
        .save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  /**
   * 컨트롤러가 요청의 apiKey 를 <b>그대로</b> 프로브에 전달하는지(별도 두 평면 해석 없이) —
   * {@code /probe} 엔드포인트에서 확인한다. apiKey 를 생략한 요청이 {@code probe(baseURL, null)}
   * 로 정확히 넘어가야 한다.
   */
  @Test
  void 프로브는_apiKey_생략을_그대로_전달한다() throws Exception {
    mockAuth("ai:settings");
    when(opencodeProbeService.probe(eq("https://evil.example/v1"), isNull()))
        .thenReturn(new ProbeResult(true, List.of(), null, Reason.OK));

    mockMvc
        .perform(
            post("/api/v1/settings/ai-credential/probe")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content(Map.of("baseURL", "https://evil.example/v1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    verify(opencodeProbeService).probe("https://evil.example/v1", null);
  }
}
