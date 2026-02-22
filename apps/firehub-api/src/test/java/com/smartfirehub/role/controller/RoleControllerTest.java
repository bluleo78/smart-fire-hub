package com.smartfirehub.role.controller;

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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
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

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private RoleService roleService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
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
    when(roleService.createRole("MODERATOR", "Moderator role")).thenReturn(created);

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

    verify(roleService).updateRole(3L, "MOD", "Updated moderator");
  }

  @Test
  void deleteRole_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("role:delete");

    mockMvc
        .perform(delete("/api/v1/roles/3").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());

    verify(roleService).deleteRole(3L);
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

    verify(roleService).setRolePermissions(1L, List.of(1L, 2L, 3L));
  }

  @Test
  void getRoles_unauthenticated_returnsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/roles")).andExpect(status().isForbidden());
  }
}
