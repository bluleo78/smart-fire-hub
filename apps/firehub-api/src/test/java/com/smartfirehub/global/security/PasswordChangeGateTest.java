package com.smartfirehub.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.service.UserService;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 첫 로그인 비밀번호 변경 강제(WD-2) — 실제 필터·인터셉터·JWT 로 검증한다.
 *
 * <p>비트랜잭션: login/refresh 가 커밋된 refresh_token 행을 필요로 한다. 실제 JwtAuthenticationFilter 가 요청마다
 * TenantContext 를 지우므로 @AfterEach 에서 기본 테넌트를 다시 세운 뒤 정리한다 (AiCredentialControllerTest:77-83 함정).
 */
@AutoConfigureMockMvc
class PasswordChangeGateTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private AuthService authService;
  @Autowired private UserService userService;
  @Autowired private com.smartfirehub.user.repository.UserRepository userRepository;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private long userId;
  private String username;

  @BeforeEach
  void setUp() {
    username = "pwc-" + System.nanoTime() + "@example.com";
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username,
                "TempPass1x",
                "PWC",
                DEFAULT_TEST_TENANT_ID)
            .id();
    // 일반 API(GET /users) 권한을 주기 위해 ADMIN 도 부여 — 게이트가 권한보다 먼저 막는지 보려는 것.
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID, "ADMIN");
    dsl.execute("update \"user\" set must_change_password = true where id = ?", userId);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TestUsers.cleanup(dsl, fixtureTransactionTemplate, userId, DEFAULT_TEST_TENANT_ID);
  }

  private String pwcToken() {
    return "Bearer "
        + jwtTokenProvider.generateAccessToken(userId, username, DEFAULT_TEST_TENANT_ID, true);
  }

  @Test
  void pwcToken_blocksGeneralApi_withCode() throws Exception {
    mockMvc
        .perform(get("/api/v1/users").header("Authorization", pwcToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
  }

  /** 게이트는 권한 검사보다 먼저 돈다 — 테넌트 미선택 토큰은 권한이 0 이라 순서가 바뀌면 코드 없는 403 이 나와 웹이 변경 화면으로 보낼 근거를 잃는다. */
  @Test
  void pwcToken_gateRunsBeforePermissionCheck() throws Exception {
    String noTenantPwc =
        "Bearer " + jwtTokenProvider.generateAccessToken(userId, username, null, true);
    mockMvc
        .perform(get("/api/v1/users").header("Authorization", noTenantPwc))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
  }

  @Test
  void normalToken_isNotBlocked() throws Exception {
    String token =
        "Bearer " + jwtTokenProvider.generateAccessToken(userId, username, DEFAULT_TEST_TENANT_ID);
    mockMvc.perform(get("/api/v1/users").header("Authorization", token)).andExpect(status().isOk());
  }

  @Test
  void pwcToken_allowlistedEndpointsPass() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").header("Authorization", pwcToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(true));
    mockMvc
        .perform(get("/api/v1/auth/memberships").header("Authorization", pwcToken()))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/auth/select-tenant")
                .header("Authorization", pwcToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":" + DEFAULT_TEST_TENANT_ID + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(true));
    // 쿠키 없는 refresh 는 401 — 403(게이트)이 아니라는 것이 "통과"의 증거다.
    mockMvc
        .perform(post("/api/v1/auth/refresh").header("Authorization", pwcToken()))
        .andExpect(status().isUnauthorized());
    // 틀린 현재 비밀번호 → 400. 403 이 아니면 게이트·권한을 모두 통과한 것.
    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", pwcToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Wrong1Pass\",\"newPassword\":\"NewPass1x\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/auth/signup-status").header("Authorization", pwcToken()))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/v1/auth/logout").header("Authorization", pwcToken()))
        .andExpect(status().isNoContent());
  }

  @Test
  void allowlist_isExactlyTheNineHandlers() {
    Set<String> actual = new TreeSet<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (info, method) -> {
              if (method.hasMethodAnnotation(AllowedDuringPasswordChange.class)) {
                String verb = info.getMethodsCondition().getMethods().iterator().next().name();
                info.getPatternValues().forEach(p -> actual.add(verb + " " + p));
              }
            });
    assertThat(actual)
        .containsExactlyInAnyOrderElementsOf(
            Arrays.asList(
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/signup",
                "GET /api/v1/auth/signup-status",
                "POST /api/v1/auth/refresh",
                "POST /api/v1/auth/logout",
                "GET /api/v1/auth/me",
                "GET /api/v1/auth/memberships",
                "POST /api/v1/auth/select-tenant",
                "PUT /api/v1/users/me/password"));
  }

  @Test
  void loginRefreshSelectTenant_carryPwcFromDb_untilPasswordChanged() {
    TokenResponse login = authService.login(new LoginRequest(username, "TempPass1x"));
    assertThat(login.mustChangePassword()).isTrue();
    assertThat(pwcOf(login)).isTrue();

    // refresh·selectTenant 도 DB 값을 다시 읽어 pwc 를 유지해야 한다(우회 구멍 방지).
    TokenResponse refreshed = authService.refresh(login.refreshToken());
    assertThat(pwcOf(refreshed)).isTrue();
    TokenResponse selected = authService.selectTenant(userId, DEFAULT_TEST_TENANT_ID);
    assertThat(pwcOf(selected)).isTrue();

    userService.changePassword(userId, "TempPass1x", "NewPass1x");

    // 변경은 이 사용자의 refresh 세션을 전부 폐기한다(리뷰 지적 2) — 옛 토큰은 더 못 쓰고, 호출자는
    // 컨트롤러가 발급하는 새 패밀리로 이어진다. 그 새 세션의 refresh 가 표식 없는 토큰을 내야 한다.
    assertThatThrownBy(() -> authService.refresh(refreshed.refreshToken()))
        .isInstanceOf(com.smartfirehub.auth.exception.InvalidTokenException.class);
    TokenResponse resumed =
        authService.startSessionAfterPasswordChange(userId, DEFAULT_TEST_TENANT_ID);
    assertThat(pwcOf(resumed)).isFalse();
    TokenResponse after = authService.refresh(resumed.refreshToken());
    assertThat(after.mustChangePassword()).isFalse();
    assertThat(pwcOf(after)).isFalse();
  }

  @Test
  void accountEndpoints_workWithoutTenant_andOnlyTouchCaller() throws Exception {
    // 멤버십이 여러 개인 사용자의 첫 로그인 = 테넌트 미선택 토큰. 권한(RLS 역할)이 0 이어도 본인 계정 작업은 돼야 한다.
    String other = "pwc-other-" + System.nanoTime() + "@example.com";
    long otherId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                other,
                other,
                "Other1Pass",
                "Other",
                DEFAULT_TEST_TENANT_ID)
            .id();
    try {
      String noTenantPwc =
          "Bearer " + jwtTokenProvider.generateAccessToken(userId, username, null, true);
      mockMvc
          .perform(
              put("/api/v1/users/me/password")
                  .header("Authorization", noTenantPwc)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"currentPassword\":\"TempPass1x\",\"newPassword\":\"NewPass1x\"}"))
          .andExpect(status().isNoContent());

      // 프로필은 허용 목록 밖이라 pwc 없는(변경 후 refresh 된) 테넌트 미선택 토큰으로 확인한다.
      String noTenant = "Bearer " + jwtTokenProvider.generateAccessToken(userId, username, null);
      mockMvc
          .perform(
              put("/api/v1/users/me")
                  .header("Authorization", noTenant)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"바뀐이름\",\"email\":\"" + username + "\"}"))
          .andExpect(status().isNoContent());
      // 다른 사용자의 이메일로는 바꿀 수 없다(본인 외 계정에 영향 불가).
      mockMvc
          .perform(
              put("/api/v1/users/me")
                  .header("Authorization", noTenant)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"x\",\"email\":\"" + other + "\"}"))
          .andExpect(status().isConflict());

      var me = userRepository.findById(userId).orElseThrow();
      assertThat(me.name()).isEqualTo("바뀐이름");
      assertThat(me.mustChangePassword()).isFalse();
      assertThat(
              passwordEncoder.matches(
                  "NewPass1x", userRepository.findPasswordById(userId).orElseThrow()))
          .isTrue();
      // 다른 사용자는 그대로다.
      assertThat(
              passwordEncoder.matches(
                  "Other1Pass", userRepository.findPasswordById(otherId).orElseThrow()))
          .isTrue();
      assertThat(userRepository.findById(otherId).orElseThrow().name()).isEqualTo("Other");
    } finally {
      TenantContext.set(DEFAULT_TEST_TENANT_ID);
      TestUsers.cleanup(dsl, fixtureTransactionTemplate, otherId, DEFAULT_TEST_TENANT_ID);
    }
  }

  /**
   * 리뷰 지적 1: 새 비밀번호가 현재(임시) 비밀번호와 같으면 서버가 거부해야 한다. 웹 스키마만 막으면 API 직접 호출로 같은 값을 보내 변경 강제 표식만 꺼 버릴 수
   * 있다(강제 변경 무력화).
   */
  @Test
  void changePassword_sameAsCurrent_rejected_andFlagStays() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/users/me/password")
                .header("Authorization", pwcToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"TempPass1x\",\"newPassword\":\"TempPass1x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("새 비밀번호는 현재 비밀번호와 달라야 합니다"));

    assertThat(userRepository.findById(userId).orElseThrow().mustChangePassword()).isTrue();
  }

  /**
   * 리뷰 지적 2: 비밀번호를 바꾸면 <b>다른 세션</b>(임시 비밀번호를 아는 다른 사람이 먼저 로그인해 둔 세션 등)의 refresh 가 막혀야 한다. 호출자 세션은
   * 응답의 새 refresh 쿠키로 이어진다 — 웹의 completePasswordChange 가 곧바로 /auth/refresh 를 부르는 흐름이 그대로 동작해야 한다.
   */
  @Test
  void changePassword_revokesOtherSessions_callerContinuesWithNewCookie() throws Exception {
    MvcResult sessionA = loginViaApi();
    MvcResult sessionB = loginViaApi();
    Cookie oldCookieA = sessionA.getResponse().getCookie("refreshToken");
    Cookie cookieB = sessionB.getResponse().getCookie("refreshToken");
    String accessA =
        com.jayway.jsonpath.JsonPath.read(
            sessionA.getResponse().getContentAsString(), "$.accessToken");

    MvcResult changed =
        mockMvc
            .perform(
                put("/api/v1/users/me/password")
                    .header("Authorization", "Bearer " + accessA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"TempPass1x\",\"newPassword\":\"NewPass1x\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    Cookie newCookie = changed.getResponse().getCookie("refreshToken");
    assertThat(newCookie).isNotNull();
    assertThat(newCookie.getPath()).isEqualTo("/api/v1/auth");
    assertThat(newCookie.isHttpOnly()).isTrue();

    // 호출자: 새 쿠키로 refresh → 200, 표식이 꺼진 토큰.
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(newCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
    // 다른 세션 B 와 호출자의 옛 쿠키는 더 이상 쓸 수 없다.
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(cookieB))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(oldCookieA))
        .andExpect(status().isUnauthorized());
  }

  private MvcResult loginViaApi() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"TempPass1x\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private boolean pwcOf(TokenResponse t) {
    return jwtTokenProvider.parseAccessToken(t.accessToken()).orElseThrow().mustChangePassword();
  }
}
