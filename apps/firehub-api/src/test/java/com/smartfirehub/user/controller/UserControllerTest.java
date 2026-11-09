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
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
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

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

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

  /** WD-125: 이름 상한은 DB 컬럼과 같은 50자 — 51자는 DB 오류가 아니라 검증 400(이름 칸 오류)으로 막고 저장하지 않는다. */
  @Test
  void updateMe_nameOver50_returnsBadRequestWithoutSaving() throws Exception {
    mockAuthentication();
    UpdateProfileRequest request = new UpdateProfileRequest("가".repeat(51), "new@example.com");

    mockMvc
        .perform(
            put("/api/v1/users/me")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.name").value("이름은 50자 이하여야 합니다"));

    verify(userService, never()).updateProfile(any(), any(), any());
  }

  /** WD-125: 정확히 50자는 통과한다(경계). */
  @Test
  void updateMe_name50_returnsNoContent() throws Exception {
    mockAuthentication();
    String name50 = "가".repeat(50);
    UpdateProfileRequest request = new UpdateProfileRequest(name50, "new@example.com");

    mockMvc
        .perform(
            put("/api/v1/users/me")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(userService).updateProfile(1L, name50, "new@example.com");
  }

  /** WD-275: 공백만 있는 이름은 한국어 문구의 이름 칸 오류(400)로 막고 저장하지 않는다. */
  @Test
  void updateMe_blankName_returnsBadRequestWithKoreanMessage() throws Exception {
    mockAuthentication();

    mockMvc
        .perform(
            put("/api/v1/users/me")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \",\"email\":\"new@example.com\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.name").value("이름을 입력하세요"));

    verify(userService, never()).updateProfile(any(), any(), any());
  }

  /** WD-275: 이름 앞뒤 공백은 잘라서 저장한다. */
  @Test
  void updateMe_nameWithSurroundingSpaces_savesStripped() throws Exception {
    mockAuthentication();

    mockMvc
        .perform(
            put("/api/v1/users/me")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  모바일 테스터  \",\"email\":\"new@example.com\"}"))
        .andExpect(status().isNoContent());

    verify(userService).updateProfile(1L, "모바일 테스터", "new@example.com");
  }

  /** 비밀번호 변경도 인증만 요구한다(WD-2) — 권한 0·테넌트 미선택 토큰으로도 204. */
  @Test
  void changePassword_authenticated_returnsNoContent() throws Exception {
    mockAuthentication();
    ChangePasswordRequest request = new ChangePasswordRequest("oldpassword", "newPassword123");
    when(authService.startSessionAfterPasswordChange(eq(1L), any()))
        .thenReturn(new TokenResponse("a", "new-refresh", "Bearer", 1800L, null, List.of(), false));

    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent())
        // 호출자 세션은 새 refresh 쿠키로 이어진다(리뷰 지적 2).
        .andExpect(
            header()
                .string(
                    "Set-Cookie",
                    org.hamcrest.Matchers.containsString("refreshToken=new-refresh")));

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

  /**
   * WD-605: BCrypt 는 앞 72바이트만 쓴다 — 그보다 긴 새 비밀번호는 뒤가 버려져 "앞 72자만 알면 로그인" 이 됐다. 그래서 설정 시점에 UTF-8
   * 72바이트를 넘으면 400 + 한국어 필드 문구로 거부하고 서비스는 부르지 않는다. 73바이트(영문)는 거부, 72바이트는 통과.
   */
  @Test
  void changePassword_newPassword73Bytes_returnsKoreanByteLimitError() throws Exception {
    mockAuthentication();
    // "Aa1" + 70자 = 73바이트(ASCII 1자=1바이트), 129자 상한 안이라 예전 규칙은 통과시켰다.
    ChangePasswordRequest request =
        new ChangePasswordRequest("oldPassword1", "Aa1" + "a".repeat(70));

    putChangePassword(request)
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.newPassword").value("새 비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다"));
    verify(userService, never()).changePassword(anyLong(), any(), any());
  }

  /** WD-605: 정확히 72바이트(영문)는 경계 안 — 204. */
  @Test
  void changePassword_newPassword72Bytes_returnsNoContent() throws Exception {
    mockAuthentication();
    stubSessionAfterPasswordChange();
    String newPassword = "Aa1" + "a".repeat(69);

    putChangePassword(new ChangePasswordRequest("oldPassword1", newPassword))
        .andExpect(status().isNoContent());
    verify(userService).changePassword(1L, "oldPassword1", newPassword);
  }

  /**
   * WD-605: 한글은 UTF-8 로 1자 3바이트 — "Aa1" + 한글 24자 = 27자지만 75바이트라 거부한다(글자 수 상한으로는 못 막는다). 순수 한글은
   * 대·소문자·숫자 규칙에 먼저 걸리므로 규칙을 만족하는 접두를 붙였다.
   */
  @Test
  void changePassword_koreanNewPassword75Bytes_returnsKoreanByteLimitError() throws Exception {
    mockAuthentication();

    putChangePassword(new ChangePasswordRequest("oldPassword1", "Aa1" + "가".repeat(24)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.newPassword").value("새 비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다"));
    verify(userService, never()).changePassword(anyLong(), any(), any());
  }

  /** WD-605: "Aa1" + 한글 23자 = 72바이트 — 통과. */
  @Test
  void changePassword_koreanNewPassword72Bytes_returnsNoContent() throws Exception {
    mockAuthentication();
    stubSessionAfterPasswordChange();
    String newPassword = "Aa1" + "가".repeat(23);

    putChangePassword(new ChangePasswordRequest("oldPassword1", newPassword))
        .andExpect(status().isNoContent());
    verify(userService).changePassword(1L, "oldPassword1", newPassword);
  }

  /**
   * WD-605: 상한은 "설정하는" 비밀번호에만 건다. 예전에 72바이트보다 길게 만든 기존 비밀번호로도 바꿀 수 있어야 하므로 현재 비밀번호 칸은 길이를 보지
   * 않는다(검증은 BCrypt matches 가 예전과 같이 앞 72바이트로 한다).
   */
  @Test
  void changePassword_longCurrentPassword_isNotRejected() throws Exception {
    mockAuthentication();
    stubSessionAfterPasswordChange();
    String longCurrent = "Old1" + "x".repeat(96);

    putChangePassword(new ChangePasswordRequest(longCurrent, "NewPassword1"))
        .andExpect(status().isNoContent());
    verify(userService).changePassword(1L, longCurrent, "NewPassword1");
  }

  /** 비밀번호 변경 PUT 공통 호출. */
  private org.springframework.test.web.servlet.ResultActions putChangePassword(
      ChangePasswordRequest request) throws Exception {
    return mockMvc.perform(
        put("/api/v1/users/me/password")
            .header("Authorization", "Bearer valid-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  /** 변경 성공 뒤 컨트롤러가 여는 새 세션(리뷰 지적 2) 스텁. */
  private void stubSessionAfterPasswordChange() {
    when(authService.startSessionAfterPasswordChange(eq(1L), any()))
        .thenReturn(new TokenResponse("a", "new-refresh", "Bearer", 1800L, null, List.of(), false));
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
                    null,
                    true)),
            0,
            20,
            1,
            1);
    when(userService.getUsers(null, 0, 20)).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/users").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].username").value("testuser"))
        .andExpect(jsonPath("$.content[0].accountActive").value(true))
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
        .andExpect(jsonPath("$.username").value("otheruser"))
        .andExpect(jsonPath("$.accountActive").value(true));
  }

  @Test
  void setUserRoles_withPermission_returnsNoContent() throws Exception {
    mockAuthentication("role:assign");
    // 화면이 본 역할 목록(WD-440)도 서비스까지 그대로 넘어가야 한다.
    SetRolesRequest request = new SetRolesRequest(List.of(1L, 2L), List.of(2L));

    mockMvc
        .perform(
            put("/api/v1/users/2/roles")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(userService).setUserRoles(eq(2L), eq(List.of(1L, 2L)), eq(List.of(2L)), any());
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

  /** WD-596: active 를 보내면 그 값 그대로(true=재활성화) 서비스에 넘어간다. */
  @Test
  void setUserActive_activeTrue_reactivates() throws Exception {
    mockAuthentication("user:write");

    putActive("{\"active\":true}").andExpect(status().isNoContent());

    verify(userService).setUserActive(eq(2L), eq(true), any());
  }

  /** WD-596: 빈 본문 {} 이 false 로 묶여 사용자가 몰래 비활성화되면 안 된다 — 400 이고 서비스는 호출되지 않는다. */
  @Test
  void setUserActive_emptyBody_returnsBadRequestAndKeepsUser() throws Exception {
    mockAuthentication("user:write");

    putActive("{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.active").value("활성 여부(active)는 필수입니다"));

    verify(userService, never()).setUserActive(anyLong(), anyBoolean(), any());
  }

  /** WD-596: 필드 이름 오타(actvie)도 active 누락으로 보고 400 — 사용자 상태는 바뀌지 않는다. */
  @Test
  void setUserActive_typoField_returnsBadRequestAndKeepsUser() throws Exception {
    mockAuthentication("user:write");

    putActive("{\"actvie\":true}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.active").value("활성 여부(active)는 필수입니다"));

    verify(userService, never()).setUserActive(anyLong(), anyBoolean(), any());
  }

  /** WD-596: 명시적 null 도 누락과 같이 400 이다. */
  @Test
  void setUserActive_nullActive_returnsBadRequestAndKeepsUser() throws Exception {
    mockAuthentication("user:write");

    putActive("{\"active\":null}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.active").value("활성 여부(active)는 필수입니다"));

    verify(userService, never()).setUserActive(anyLong(), anyBoolean(), any());
  }

  /** 사용자 2번의 활성 상태 변경 요청을 주어진 JSON 본문 그대로 보낸다(WD-596 바인딩 검증용). */
  private org.springframework.test.web.servlet.ResultActions putActive(String body)
      throws Exception {
    return mockMvc.perform(
        put("/api/v1/users/2/active")
            .header("Authorization", "Bearer valid-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** WD-293: 사용자 목록도 page ≥ 0, 1 ≤ size ≤ 100 으로 보정해 서비스에 넘긴다(상한 없음·size=0·음수 오류 방지). */
  @Test
  void getUsers_clampsPageAndSize() throws Exception {
    mockAuthentication("user:read");
    when(userService.getUsers(any(), anyInt(), anyInt()))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

    mockMvc
        .perform(
            get("/api/v1/users")
                .param("size", "100000")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/users")
                .param("page", "-1")
                .param("size", "0")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());

    verify(userService).getUsers(null, 0, 100);
    verify(userService).getUsers(null, 0, 1);
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
        .andExpect(status().isForbidden())
        // WD-127: 웹이 그대로 토스트로 보여 주는 문구라 영어 원문이 아닌 한국어 안내여야 한다
        .andExpect(
            jsonPath("$.message").value("이 작업을 할 권한이 없습니다. 관리자에게 권한을 요청하세요. (필요 권한: role:assign)"));
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
        .andExpect(status().isForbidden())
        // WD-127: @RequirePermission 인터셉터의 403 도 한국어 안내 + 필요 권한 코드
        .andExpect(
            jsonPath("$.message").value("이 작업을 할 권한이 없습니다. 관리자에게 권한을 요청하세요. (필요 권한: user:write)"));
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

  /** WD-605: 관리자가 정하는 임시 비밀번호도 UTF-8 72바이트 상한 — 73바이트면 400, 서비스 호출 없음. */
  @Test
  void addMember_temporaryPasswordOver72Bytes_badRequest() throws Exception {
    mockAuthentication("user:write");

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@acme.io\",\"name\":\"N\",\"temporaryPassword\":\"Aa1"
                        + "a".repeat(70)
                        + "\",\"roleIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.temporaryPassword")
                .value("비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다"));
    verify(userService, never()).addMember(any(), any());
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
