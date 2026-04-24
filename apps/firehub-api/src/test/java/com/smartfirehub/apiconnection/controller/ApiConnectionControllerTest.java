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
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ApiConnectionService apiConnectionService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
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

  @Test
  void delete_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/api-connections/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(apiConnectionService).delete(1L);
  }

  @Test
  void getAll_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/api-connections")).andExpect(status().isUnauthorized());
  }

  // ── 신규 엔드포인트 테스트 ──────────────────────────────────────────────────────

  /**
   * GET /selectable — 인증만 있으면 관리자가 아니어도 접근 가능해야 한다.
   * PermissionInterceptor는 @RequirePermission 어노테이션이 없으면 통과시킨다.
   */
  @Test
  void getSelectable_authenticated_returnsOk() throws Exception {
    when(apiConnectionService.findSelectable())
        .thenReturn(
            List.of(new ApiConnectionSelectableResponse(1L, "GitHub API", "BEARER", "https://api.github.com")));

    mockMvc
        .perform(get("/api/v1/api-connections/selectable").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].name").value("GitHub API"));
  }

  /**
   * GET /selectable — 인증 없이 접근 시 401 Unauthorized.
   */
  @Test
  void getSelectable_noAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/api-connections/selectable"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * POST /{id}/test — apiconnection:write 권한 보유 시 정상 응답.
   * PermissionInterceptor가 permissionService.getUserPermissions()로 검증한다.
   */
  @Test
  void postTest_withPermission_returnsResult() throws Exception {
    when(apiConnectionService.testConnection(1L))
        .thenReturn(new TestConnectionResponse(true, 200, 42L, null));

    mockMvc
        .perform(post("/api/v1/api-connections/1/test").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.status").value(200));
  }

  /**
   * POST /refresh-all — apiconnection:write 권한 보유 시 jobId 반환.
   */
  @Test
  void postRefreshAll_withPermission_returnsJobId() throws Exception {
    String jobId = java.util.UUID.randomUUID().toString();
    when(apiConnectionService.refreshAllAsync(any())).thenReturn(jobId);

    mockMvc
        .perform(post("/api/v1/api-connections/refresh-all").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobId.toString()));
  }
}
