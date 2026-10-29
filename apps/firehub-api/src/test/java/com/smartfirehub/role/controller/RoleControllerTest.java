package com.smartfirehub.role.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.role.dto.*;
import com.smartfirehub.role.service.RoleService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(RoleController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RoleControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private RoleService roleService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void getRoles_withPermission_returnsList() throws Exception {
    mockAuthentication("role:read");
    List<RoleResponse> roles =
        List.of(
            new RoleResponse(1L, "ADMIN", "Administrator", true),
            new RoleResponse(2L, "USER", "Regular user", true));
    when(roleService.getAllRoles()).thenReturn(roles);

    mockMvc
        .perform(get("/api/v1/roles").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("ADMIN"))
        .andExpect(jsonPath("$[1].name").value("USER"));
  }

  @Test
  void getRoleById_withPermission_returnsRole() throws Exception {
    mockAuthentication("role:read");
    RoleDetailResponse detail =
        new RoleDetailResponse(
            1L,
            "ADMIN",
            "Administrator",
            true,
            List.of(new PermissionResponse(1L, "user:read", "Read users", "user")));
    when(roleService.getRoleById(1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/roles/1").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("ADMIN"))
        .andExpect(jsonPath("$.permissions[0].code").value("user:read"));
  }

  @Test
  void createRole_withPermission_returnsCreated() throws Exception {
    mockAuthentication("role:write");
    CreateRoleRequest request = new CreateRoleRequest("MODERATOR", "Moderator role");
    RoleResponse created = new RoleResponse(3L, "MODERATOR", "Moderator role", false);
    when(roleService.createRole("MODERATOR", "Moderator role", 1L)).thenReturn(created);

    mockMvc
        .perform(
            post("/api/v1/roles")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("MODERATOR"));
  }

  @Test
  void updateRole_withPermission_returnsOk() throws Exception {
    mockAuthentication("role:write");
    UpdateRoleRequest request = new UpdateRoleRequest("MOD", "Updated moderator");

    mockMvc
        .perform(
            put("/api/v1/roles/3")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(roleService).updateRole(3L, "MOD", "Updated moderator", 1L);
  }

  /**
   * WD-279: 이름 수정도 생성과 같은 이름 규칙(대문자·숫자·밑줄)을 서버에서 검사한다. 한글·공백·소문자와, 시스템 역할 이름 뒤에 공백만 붙인 「ADMIN 」
   * 「USER 」 처럼 목록에서 시스템 역할과 똑같이 보이는 이름은 400 으로 거부되고 서비스까지 가지 않는다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"qa267 tmp ", "멤버 관리자 ", "ADMIN ", "USER ", " ADMIN", "admin", "Mod"})
  void updateRole_nameBreakingRule_returnsBadRequest(String name) throws Exception {
    mockAuthentication("role:write");

    mockMvc
        .perform(
            put("/api/v1/roles/3")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new UpdateRoleRequest(name, "임시"))))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("역할 이름은 대문자, 숫자, 밑줄만 허용됩니다")));

    verify(roleService, never()).updateRole(any(), any(), any(), any());
  }

  @Test
  void deleteRole_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("role:delete");

    mockMvc
        .perform(delete("/api/v1/roles/3").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());

    verify(roleService).deleteRole(3L, 1L);
  }

  @Test
  void setPermissions_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("role:write");
    SetPermissionsRequest request = new SetPermissionsRequest(List.of(1L, 2L, 3L));

    mockMvc
        .perform(
            put("/api/v1/roles/1/permissions")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(roleService).setRolePermissions(1L, List.of(1L, 2L, 3L), 1L);
  }

  @Test
  void getRoles_unauthenticated_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/roles")).andExpect(status().isUnauthorized());
  }
}
