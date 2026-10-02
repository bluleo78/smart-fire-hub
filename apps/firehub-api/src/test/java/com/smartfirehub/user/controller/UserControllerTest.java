package com.smartfirehub.user.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.auth.controller.RefreshTokenCookies;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.service.AuthService;
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
import java.util.Optional;
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
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RefreshTokenCookies.class})
class UserControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private UserService userService;

  /** 비밀번호 변경 후 새 refresh 세션 발급(리뷰 지적 2) — 컨트롤러가 호출한다. */
  @MockitoBean private AuthService authService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
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
    // /me 는 전역 정체성 경로이므로 테넌트로 좁히지 않는 getMyProfile 을 탄다.
    when(userService.getMyProfile(1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/users/me").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("testuser"))
        .andExpect(jsonPath("$.roles[0].name").value("USER"));
  }

  /** 계정 단위 엔드포인트는 인증만 요구한다(WD-2) — 권한 0·테넌트 미선택 토큰으로도 204. */
  @Test
  void updateMe_authenticated_returnsUpdated() throws Exception {
    mockAuthentication();
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

  /** 비밀번호 변경도 인증만 요구한다(WD-2) — 권한 0·테넌트 미선택 토큰으로도 204. */
  @Test
  void changePassword_authenticated_returnsNoContent() throws Exception {
    mockAuthentication();
    ChangePasswordRequest request = new ChangePasswordRequest("oldpassword", "newPassword123");
    when(authService.startSessionAfterPasswordChange(eq(1L), any()))
        .thenReturn(
            new TokenResponse("a", "new-refresh", "Bearer", 1800L, null, List.of(), false));

    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent())
        // 호출자 세션은 새 refresh 쿠키로 이어진다(리뷰 지적 2).
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("refreshToken=new-refresh")));

    verify(userService).changePassword(1L, "oldpassword", "newPassword123");
  }

  /** 권한 게이트를 뗐어도 인증은 여전히 필요하다 — 토큰 없으면 401. */
  @Test
  void changePassword_unauthenticated_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"oldPassword1\",\"newPassword\":\"NewPassword1\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getUsers_withPermission_returnsList() throws Exception {
    mockAuthentication("user:read");
    PageResponse<UserListResponse> page =
        new PageResponse<>(
            List.of(
                new UserListResponse(
                    1L,
                    "testuser",
                    "test@example.com",
                    "Test User",
                    true,
                    LocalDateTime.now(),
                    List.of(new RoleResponse(1L, "ADMIN", null, true)),
                    null)),
            0,
            20,
            1,
            1);
    when(userService.getUsers(null, 0, 20)).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/users").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].username").value("testuser"))
        // 목록 응답에도 역할이 포함되는지 확인 (#586 회귀 방지)
        .andExpect(jsonPath("$.content[0].roles[0].name").value("ADMIN"))
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

    verify(userService).setUserRoles(eq(2L), eq(List.of(1L, 2L)), any());
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

    verify(userService).setUserActive(eq(2L), eq(false), any());
  }

  @Test
  void getUsers_unauthenticated_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
  }

  @Test
  void addMember_withUserWrite_noRoles_returnsCreated() throws Exception {
    mockAuthentication("user:write");
    when(userService.addMember(any(), eq(1L))).thenReturn(new AddMemberResponse(7L, true));

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@acme.io\",\"name\":\"N\",\"temporaryPassword\":\"TempPass1x\",\"roleIds\":[]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(7))
        .andExpect(jsonPath("$.created").value(true));
  }

  @Test
  void addMember_withRoles_requiresRoleAssign() throws Exception {
    mockAuthentication("user:write");

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@acme.io\",\"name\":\"N\",\"temporaryPassword\":\"TempPass1x\",\"roleIds\":[2]}"))
        .andExpect(status().isForbidden());
    verify(userService, never()).addMember(any(), any());
  }

  @Test
  void addMember_withRolesAndRoleAssign_returnsCreated() throws Exception {
    mockAuthentication("user:write", "role:assign");
    when(userService.addMember(any(), eq(1L))).thenReturn(new AddMemberResponse(8L, true));

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@acme.io\",\"name\":\"N\",\"temporaryPassword\":\"TempPass1x\",\"roleIds\":[2]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(8));
    verify(userService).addMember(any(), eq(1L));
  }

  @Test
  void addMember_withoutUserWrite_forbidden() throws Exception {
    mockAuthentication("user:read", "role:assign");

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@acme.io\",\"name\":\"N\",\"temporaryPassword\":\"TempPass1x\",\"roleIds\":[]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void addMember_weakPassword_badRequest() throws Exception {
    mockAuthentication("user:write");

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@acme.io\",\"name\":\"N\",\"temporaryPassword\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.temporaryPassword").exists());
  }

  @Test
  void removeMember_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("user:write");
    mockMvc
        .perform(delete("/api/v1/users/2/membership").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
    verify(userService).removeMember(eq(2L), any());
  }

  @Test
  void removeMember_withoutPermission_forbidden() throws Exception {
    mockAuthentication("user:read");
    mockMvc
        .perform(delete("/api/v1/users/2/membership").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }
}
