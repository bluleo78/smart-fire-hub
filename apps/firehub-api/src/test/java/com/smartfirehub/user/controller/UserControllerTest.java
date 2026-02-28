package com.smartfirehub.user.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.user.dto.*;
import com.smartfirehub.user.service.UserService;
import java.time.LocalDateTime;
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
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class UserControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private UserService userService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void getMe_authenticated_returnsProfile() throws Exception {
    mockAuthentication();
    UserDetailResponse detail =
        new UserDetailResponse(
            1L,
            "testuser",
            "test@example.com",
            "Test User",
            true,
            LocalDateTime.now(),
            List.of(new RoleResponse(1L, "USER", "Regular user", true)));
    when(userService.getUserById(1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("testuser"))
        .andExpect(jsonPath("$.roles[0].name").value("USER"));
  }

  @Test
  void updateMe_authenticated_returnsUpdated() throws Exception {
    mockAuthentication("user:write:self");
    UpdateProfileRequest request = new UpdateProfileRequest("New Name", "new@example.com");

    mockMvc
        .perform(
            put("/api/v1/users/me")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(userService).updateProfile(1L, "New Name", "new@example.com");
  }

  @Test
  void changePassword_authenticated_returnsNoContent() throws Exception {
    mockAuthentication("user:write:self");
    ChangePasswordRequest request = new ChangePasswordRequest("oldpassword", "newPassword123");

    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(userService).changePassword(1L, "oldpassword", "newPassword123");
  }

  @Test
  void getUsers_withPermission_returnsList() throws Exception {
    mockAuthentication("user:read");
    PageResponse<UserResponse> page =
        new PageResponse<>(
            List.of(
                new UserResponse(
                    1L, "testuser", "test@example.com", "Test User", true, LocalDateTime.now())),
            0,
            20,
            1,
            1);
    when(userService.getUsers(null, 0, 20)).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/users").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].username").value("testuser"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void getUserById_withPermission_returnsUser() throws Exception {
    mockAuthentication("user:read");
    UserDetailResponse detail =
        new UserDetailResponse(
            2L,
            "otheruser",
            "other@example.com",
            "Other User",
            true,
            LocalDateTime.now(),
            List.of());
    when(userService.getUserById(2L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/users/2").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("otheruser"));
  }

  @Test
  void setUserRoles_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("role:assign");
    SetRolesRequest request = new SetRolesRequest(List.of(1L, 2L));

    mockMvc
        .perform(
            put("/api/v1/users/2/roles")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(userService).setUserRoles(2L, List.of(1L, 2L));
  }

  @Test
  void setUserActive_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("user:write");
    SetActiveRequest request = new SetActiveRequest(false);

    mockMvc
        .perform(
            put("/api/v1/users/2/active")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(userService).setUserActive(2L, false);
  }

  @Test
  void getUsers_unauthenticated_returnsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/users")).andExpect(status().isForbidden());
  }
}
