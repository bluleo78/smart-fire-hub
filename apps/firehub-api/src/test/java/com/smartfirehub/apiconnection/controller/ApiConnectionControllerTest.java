package com.smartfirehub.apiconnection.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNotFoundException;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
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

@SuppressWarnings("null")
@WebMvcTest(ApiConnectionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ApiConnectionControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ApiConnectionService apiConnectionService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("apiconnection:read", "apiconnection:write", "apiconnection:delete"));
  }

  private ApiConnectionResponse sampleConnection() {
    return new ApiConnectionResponse(
        1L,
        "GitHub API",
        "GitHub REST API connection",
        "BEARER",
        Map.of("token", "***"),
        "https://api.github.com",
        null,
        null,
        null,
        null,
        null,
        1L,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @Test
  void getAll_withPermission_returnsList() throws Exception {
    when(apiConnectionService.getAll()).thenReturn(List.of(sampleConnection()));

    mockMvc
        .perform(get("/api/v1/api-connections").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].name").value("GitHub API"))
        .andExpect(jsonPath("$[0].authType").value("BEARER"));
  }

  @Test
  void create_withPermission_returnsCreated() throws Exception {
    CreateApiConnectionRequest request =
        new CreateApiConnectionRequest(
            "GitHub API",
            "GitHub REST API connection",
            "BEARER",
            Map.of("token", "ghp_secret123"),
            "https://api.github.com",
            null);

    when(apiConnectionService.create(any(CreateApiConnectionRequest.class), anyLong()))
        .thenReturn(sampleConnection());

    mockMvc
        .perform(
            post("/api/v1/api-connections")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("GitHub API"));
  }

  /**
   * 가린 인증정보(****…) 거절(WD-179)은 서비스가 IllegalArgumentException 으로 던지고 HTTP 400 으로 나가야 한다. WD-179 당시에는
   * 이 패키지의 ApiConnectionException 이 404 로 매핑돼 있어 예외 종류를 바꾸면 400 이 404 로 뒤바뀌었다(WD-180 에서 「없음」만 404 로
   * 분리됨) — 400 이 유지되는지 지킨다.
   */
  @Test
  void update_maskedSecretRejected_returnsBadRequest() throws Exception {
    String message = "authConfig.token 에 가려진 값(****…)은 저장할 수 없습니다.";
    when(apiConnectionService.update(anyLong(), any(UpdateApiConnectionRequest.class), anyLong()))
        .thenThrow(new IllegalArgumentException(message));

    mockMvc
        .perform(
            put("/api/v1/api-connections/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpdateApiConnectionRequest(
                            null, null, null, Map.of("token", "****0000"), null, null))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
  }

  /** 없는(또는 다른 워크스페이스의) 연결 조회는 404 · 한국어 문구다(WD-180). 예전에는 「ApiConnection not found: N」 영어 원문이었다. */
  @Test
  void getById_notFound_returns404InKorean() throws Exception {
    when(apiConnectionService.getById(999L)).thenThrow(new ApiConnectionNotFoundException(999L));

    mockMvc
        .perform(get("/api/v1/api-connections/999").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("API 연결을 찾을 수 없습니다: 999"));
  }

  /**
   * 수정의 입력 오류(지원하지 않는 인증 방식 등)는 404 가 아니라 400 이다(WD-180). 예전에는 ApiConnectionException 전체가 404 로 매핑돼
   * 「찾을 수 없음」처럼 나갔다.
   */
  @Test
  void update_unsupportedAuthType_returns400() throws Exception {
    String message = "지원하지 않는 인증 방식입니다: OAUTH2 (사용 가능: API_KEY, BEARER)";
    when(apiConnectionService.update(anyLong(), any(UpdateApiConnectionRequest.class), anyLong()))
        .thenThrow(new ApiConnectionException(message));

    mockMvc
        .perform(
            put("/api/v1/api-connections/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpdateApiConnectionRequest(null, null, "OAUTH2", null, null, null))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
  }

  /** 저장 전 테스트의 내부 주소 거절도 입력 오류라 400 이다(WD-180). */
  @Test
  void testPayload_internalAddressRejected_returns400() throws Exception {
    String message = "내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1";
    when(apiConnectionService.testConnectionPayload(any(CreateApiConnectionRequest.class)))
        .thenThrow(new ApiConnectionException(message));

    mockMvc
        .perform(
            post("/api/v1/api-connections/test")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateApiConnectionRequest(
                            "Loopback",
                            null,
                            "BEARER",
                            Map.of("token", "tok"),
                            "http://127.0.0.1:6030",
                            null))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
  }

  /** 수정은 로그인한 사용자를 감사 행위자로 서비스에 넘긴다(WD-181). */
  @Test
  void update_passesCallerAsAuditActor() throws Exception {
    when(apiConnectionService.update(anyLong(), any(UpdateApiConnectionRequest.class), anyLong()))
        .thenReturn(sampleConnection());

    mockMvc
        .perform(
            put("/api/v1/api-connections/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpdateApiConnectionRequest("Renamed", null, null, null, null, null))))
        .andExpect(status().isOk());

    verify(apiConnectionService).update(eq(1L), any(UpdateApiConnectionRequest.class), eq(1L));
  }

  @Test
  void delete_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/api-connections/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(apiConnectionService).delete(1L, 1L);
  }

  @Test
  void getAll_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/api-connections")).andExpect(status().isUnauthorized());
  }

  /** GET /{id}/references — 삭제 전 참조 파이프라인 확인용 신규 엔드포인트(#605). */
  @Test
  void getReferences_withPermission_returnsPipelines() throws Exception {
    when(apiConnectionService.getReferences(1L))
        .thenReturn(
            new com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse(
                1L,
                List.of(
                    new com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse
                        .ReferenceItem(56L, "inspector-fk-test")),
                1));

    mockMvc
        .perform(
            get("/api/v1/api-connections/1/references")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiConnectionId").value(1))
        .andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.pipelines[0].id").value(56))
        .andExpect(jsonPath("$.pipelines[0].name").value("inspector-fk-test"));
  }

  @Test
  void getReferences_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/api-connections/1/references"))
        .andExpect(status().isUnauthorized());
  }

  // ── 신규 엔드포인트 테스트 ──────────────────────────────────────────────────────

  /**
   * GET /selectable — 인증만 있으면 관리자가 아니어도 접근 가능해야 한다. PermissionInterceptor는 @RequirePermission
   * 어노테이션이 없으면 통과시킨다.
   */
  @Test
  void getSelectable_authenticated_returnsOk() throws Exception {
    when(apiConnectionService.findSelectable())
        .thenReturn(
            List.of(
                new ApiConnectionSelectableResponse(
                    1L, "GitHub API", "BEARER", "https://api.github.com")));

    mockMvc
        .perform(
            get("/api/v1/api-connections/selectable").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].name").value("GitHub API"));
  }

  /** GET /selectable — 인증 없이 접근 시 401 Unauthorized. */
  @Test
  void getSelectable_noAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/api-connections/selectable")).andExpect(status().isUnauthorized());
  }

  /**
   * POST /{id}/test — apiconnection:write 권한 보유 시 정상 응답. PermissionInterceptor가
   * permissionService.getUserPermissions()로 검증한다.
   */
  @Test
  void postTest_withPermission_returnsResult() throws Exception {
    when(apiConnectionService.testConnection(1L))
        .thenReturn(
            new TestConnectionResponse(
                true,
                200,
                42L,
                null,
                "https://api.example.com",
                "{}",
                java.util.Map.of(),
                "application/json"));

    mockMvc
        .perform(
            post("/api/v1/api-connections/1/test").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.status").value(200));
  }

  /** POST /test — payload 기반 dry-run 연결 테스트. /{id}/test 라우팅에 가로채이지 않고 정상 호출되어야 한다. (#113) */
  @Test
  void postTestPayload_withPermission_returnsResult() throws Exception {
    CreateApiConnectionRequest request =
        new CreateApiConnectionRequest(
            "Query Param Test",
            null,
            "API_KEY",
            Map.of("placement", "query", "paramName", "serviceKey", "apiKey", "test-key"),
            "https://apis.example.com/service",
            null);

    when(apiConnectionService.testConnectionPayload(any(CreateApiConnectionRequest.class)))
        .thenReturn(
            new TestConnectionResponse(
                true,
                200,
                42L,
                null,
                "https://apis.example.com/service?serviceKey=test-key",
                "{}",
                java.util.Map.of(),
                "application/json"));

    mockMvc
        .perform(
            post("/api/v1/api-connections/test")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.status").value(200));

    verify(apiConnectionService).testConnectionPayload(any(CreateApiConnectionRequest.class));
  }

  /**
   * POST /test 는 /{id:\\d+}/test 에 라우팅되지 않아야 한다. "test" 를 Long 으로 변환 시도하면 500이 아닌
   * testConnectionPayload 로 정상 처리되어야 한다. (#113 regression 재확인)
   */
  @Test
  void postTestPayload_routingDoesNotConflictWithIdTest() throws Exception {
    // POST /test 경로가 /{id}/test 에 가로채이지 않음을 verify 로 확인
    // — testConnectionPayload 가 호출되고 testConnection(Long) 은 호출되지 않아야 한다
    CreateApiConnectionRequest request =
        new CreateApiConnectionRequest(
            "Routing Check",
            null,
            "BEARER",
            Map.of("token", "tok"),
            "https://routing.example.com",
            null);

    when(apiConnectionService.testConnectionPayload(any(CreateApiConnectionRequest.class)))
        .thenReturn(
            new TestConnectionResponse(
                false,
                401,
                100L,
                "HTTP 401",
                "https://routing.example.com",
                null,
                java.util.Map.of(),
                null));

    mockMvc
        .perform(
            post("/api/v1/api-connections/test")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk()); // 500 이 아닌 200(서비스 레벨 실패)

    // testConnection(Long) 이 아닌 testConnectionPayload 가 호출되어야 한다
    verify(apiConnectionService).testConnectionPayload(any(CreateApiConnectionRequest.class));
    org.mockito.Mockito.verify(apiConnectionService, org.mockito.Mockito.never())
        .testConnection(org.mockito.ArgumentMatchers.anyLong());
  }

  // ── WD-175: 인증정보 원문 HTTP 노출 경로 제거 ─────────────────────────────────────

  /** 테스트가 심어 두는 원문 토큰. 응답 본문 어디에도 이 문자열이 나오면 안 된다. */
  private static final String PLAINTEXT_SECRET = "ghp_wd175_plaintext_secret";

  /**
   * {@code GET /{id}/decrypted} 를 호출하고 "원문이 나가지 않는다"를 단언한다.
   *
   * <p>서비스 목이 원문을 돌려주도록 심어 둔다 — 그래야 엔드포인트가 되살아나면 상태(200)·본문(원문)·호출 검증이 모두 실패한다. 스텁이 없으면 목이 빈 Map 을
   * 돌려줘 본문 단언이 공허해진다. 삭제 후 이 경로는 어떤 매핑에도 걸리지 않아 404 다(실측).
   */
  private void assertDecryptedEndpointRemoved() throws Exception {
    when(apiConnectionService.getDecryptedAuthConfig(1L))
        .thenReturn(Map.of("authType", "BEARER", "token", PLAINTEXT_SECRET));

    String body =
        mockMvc
            .perform(
                get("/api/v1/api-connections/1/decrypted")
                    .header("Authorization", "Bearer test-token"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(PLAINTEXT_SECRET);
    org.mockito.Mockito.verify(apiConnectionService, org.mockito.Mockito.never())
        .getDecryptedAuthConfig(anyLong());
  }

  /**
   * WD-175: 일반 사용자(USER 역할 — {@code apiconnection:read} 만 보유)가 {@code /decrypted} 로 토큰·API 키 원문을 받던
   * 결함. 엔드포인트를 지웠으므로 404 이고 본문에 원문이 없다.
   */
  @Test
  void getDecrypted_endpointRemoved_userWithReadOnly_returnsNotFoundWithoutSecret()
      throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("apiconnection:read"));

    assertDecryptedEndpointRemoved();
  }

  /** WD-175: 쓰기·삭제 권한까지 가진 관리자(ADMIN)에게도 원문을 HTTP 로 내보내는 경로는 없다. */
  @Test
  void getDecrypted_endpointRemoved_adminWithAllPermissions_returnsNotFoundWithoutSecret()
      throws Exception {
    // setUp 이 read·write·delete 를 모두 심어 둔다.
    assertDecryptedEndpointRemoved();
  }

  /** POST /refresh-all — apiconnection:write 권한 보유 시 jobId 반환. */
  @Test
  void postRefreshAll_withPermission_returnsJobId() throws Exception {
    String jobId = java.util.UUID.randomUUID().toString();
    when(apiConnectionService.refreshAllAsync(any())).thenReturn(jobId);

    mockMvc
        .perform(
            post("/api/v1/api-connections/refresh-all")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobId.toString()));
  }
}
