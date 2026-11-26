package com.smartfirehub.auth.service;

import static com.smartfirehub.jooq.Tables.REFRESH_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.RefreshTokenReusedException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

/** 트랜잭션 없는 다른 테스트 클래스가 잠긴 login_attempts 행을 남길 수 있어, 매 테스트 전 그 테이블을 비운다(#flaky). */
@Transactional
@Sql(
    statements = "DELETE FROM login_attempts",
    config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED),
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuthServiceTest extends IntegrationTestBase {

  @Autowired private AuthService authService;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private UserRepository userRepository;
  @Autowired private JwtTokenProvider jwtTokenProvider;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  // 가입 규칙은 SignupClosureTest 가 소유한다(WD-2 — 첫 사용자 전용).

  @Test
  void login_success() {
    TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        "test@example.com",
        "test@example.com",
        "Password123",
        "Test User",
        DEFAULT_TEST_TENANT_ID);

    TokenResponse result = authService.login(new LoginRequest("test@example.com", "Password123"));

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.tokenType()).isEqualTo("Bearer");
    assertThat(result.expiresIn()).isGreaterThan(0);
  }

  /** 로그인 응답에 비밀번호 변경 강제 표식이 DB 값 그대로 실린다(WD-2) — 웹이 변경 화면으로 보낼 근거. */
  @Test
  void login_returnsMustChangePasswordFlag() {
    var u =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "pwc@example.com",
            "pwc@example.com",
            "Password123",
            "PWC",
            DEFAULT_TEST_TENANT_ID);
    dsl.execute("update \"user\" set must_change_password = true where id = ?", u.id());
    var token = authService.login(new LoginRequest("pwc@example.com", "Password123"));
    assertThat(token.mustChangePassword()).isTrue();
  }

  @Test
  void login_wrongPassword_throws() {
    TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        "test@example.com",
        "test@example.com",
        "Password123",
        "Test User",
        DEFAULT_TEST_TENANT_ID);

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("test@example.com", "wrongpassword")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void login_userNotFound_throws() {
    assertThatThrownBy(() -> authService.login(new LoginRequest("unknown@example.com", "password")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void refresh_success() throws InterruptedException {
    TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        "test@example.com",
        "test@example.com",
        "Password123",
        "Test User",
        DEFAULT_TEST_TENANT_ID);
    TokenResponse loginResult =
        authService.login(new LoginRequest("test@example.com", "Password123"));

    Thread.sleep(1100); // JWT uses second-precision timestamps; ensure new token differs

    TokenResponse result = authService.refresh(loginResult.refreshToken());

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.accessToken()).isNotEqualTo(loginResult.accessToken());
  }

  @Test
  void refresh_invalidToken_throws() {
    assertThatThrownBy(() -> authService.refresh("invalid-token"))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void refresh_revokedToken_throws() {
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "test@example.com",
            "test@example.com",
            "Password123",
            "Test User",
            DEFAULT_TEST_TENANT_ID);
    TokenResponse loginResult =
        authService.login(new LoginRequest("test@example.com", "Password123"));

    // Logout revokes all tokens
    authService.logout(user.id());

    // 회전 유예(WD-1141)는 폐기 직후 5초만 관용한다 — 유예 밖 재제시가 재사용 탐지에 걸리는지 본다.
    dsl.update(REFRESH_TOKEN)
        .set(REFRESH_TOKEN.REVOKED_AT, RefreshTokenRepository.revokedAtNow().minusSeconds(6))
        .where(
            REFRESH_TOKEN
                .TOKEN_HASH
                .eq(RefreshTokenHasher.hash(loginResult.refreshToken())))
        .execute();

    // Try to use the revoked refresh token — reuse detection kicks in
    assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("이미 사용된 토큰입니다. 다시 로그인해 주세요.");
  }

  @Test
  void logout_revokesAllTokens() {
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "test@example.com",
            "test@example.com",
            "Password123",
            "Test User",
            DEFAULT_TEST_TENANT_ID);
    TokenResponse loginResult =
        authService.login(new LoginRequest("test@example.com", "Password123"));

    authService.logout(user.id());

    assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
        .isInstanceOf(InvalidTokenException.class);
  }

  /** 비활성 계정 + 맞는 비밀번호 → 계정이 막혔다고 알린다(WD-278). 화면은 이 예외의 코드로 자격증명 오류와 구분한다. */
  @Test
  void login_deactivatedUser_correctPassword_throwsDeactivated() {
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "test@example.com",
            "test@example.com",
            "Password123",
            "Test User",
            DEFAULT_TEST_TENANT_ID);
    userRepository.setActive(user.id(), false);

    assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "Password123")))
        .isInstanceOf(UserDeactivatedException.class)
        .hasMessage(UserDeactivatedException.MESSAGE);
  }

  /** 비활성 계정이어도 비밀번호가 틀리면 일반 자격증명 오류 — 비밀번호를 모르는 사람에게 계정 상태를 드러내지 않는다(WD-278). */
  @Test
  void login_deactivatedUser_wrongPassword_throwsInvalidCredentials() {
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "test@example.com",
            "test@example.com",
            "Password123",
            "Test User",
            DEFAULT_TEST_TENANT_ID);
    userRepository.setActive(user.id(), false);

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("test@example.com", "wrongpassword")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void refresh_deactivatedUser_throws() {
    UserResponse user =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "test@example.com",
            "test@example.com",
            "Password123",
            "Test User",
            DEFAULT_TEST_TENANT_ID);
    TokenResponse loginResult =
        authService.login(new LoginRequest("test@example.com", "Password123"));

    userRepository.setActive(user.id(), false);

    assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
        .isInstanceOf(UserDeactivatedException.class)
        .hasMessage(UserDeactivatedException.MESSAGE);
  }

  @Test
  void getCurrentUser_success() {
    UserResponse created =
        TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            "test@example.com",
            "test@example.com",
            "Password123",
            "Test User",
            DEFAULT_TEST_TENANT_ID);

    UserResponse result = authService.getCurrentUser(created.id());

    assertThat(result.id()).isEqualTo(created.id());
    assertThat(result.username()).isEqualTo("test@example.com");
  }

  private UserResponse createLogoutUser() {
    return TestUsers.createMember(
        dsl,
        fixtureTransactionTemplate,
        passwordEncoder,
        "logout-session@example.com",
        "logout-session@example.com",
        "Password123",
        "Logout Session",
        DEFAULT_TEST_TENANT_ID);
  }

  private TokenResponse loginLogoutUser() {
    return authService.login(new LoginRequest("logout-session@example.com", "Password123"));
  }

  /** 이 브라우저 세션 로그아웃(WD-1176) — 이 패밀리만 끝나고 같은 사용자의 다른 패밀리는 살아 있다. */
  @Test
  void logoutSession_revokesOnlyThisFamily() {
    createLogoutUser();
    TokenResponse a = loginLogoutUser();
    TokenResponse b = loginLogoutUser();

    authService.logoutSession(a.refreshToken(), null);

    assertThatThrownBy(() -> authService.refresh(a.refreshToken()))
        .isInstanceOfAny(InvalidTokenException.class, RefreshTokenReusedException.class);
    assertThat(authService.refresh(b.refreshToken()).accessToken()).isNotBlank();
  }

  /** 이미 회전돼 폐기된 옛 토큰으로 불러도 그 패밀리(= 새 토큰 포함)가 끝난다. */
  @Test
  void logoutSession_withRotatedToken_revokesItsFamily() {
    createLogoutUser();
    TokenResponse a = loginLogoutUser();
    TokenResponse rotated = authService.refresh(a.refreshToken());

    authService.logoutSession(a.refreshToken(), null);

    assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
        .isInstanceOfAny(InvalidTokenException.class, RefreshTokenReusedException.class);
  }

  /** 없음·빈값·모르는 문자열은 조용히 무시하고 다른 세션에 영향이 없다. */
  @Test
  void logoutSession_unknownOrBlank_doesNothing() {
    createLogoutUser();
    TokenResponse other = loginLogoutUser();

    authService.logoutSession(null, null);
    authService.logoutSession("", null);
    authService.logoutSession("garbage", null);

    assertThat(authService.refresh(other.refreshToken()).accessToken()).isNotBlank();
  }

  /** 운영자(플랫폼) 평면 토큰은 이 엔드포인트가 건드리지 않는다. */
  @Test
  void logoutSession_platformToken_doesNothing() {
    UserResponse user = createLogoutUser();
    String platformRefresh = jwtTokenProvider.generatePlatformRefreshToken(user.id());
    refreshTokenRepository.save(
        user.id(),
        RefreshTokenHasher.hash(platformRefresh),
        LocalDateTime.now().plusDays(1),
        UUID.randomUUID(),
        RefreshTokenRepository.PLANE_PLATFORM);

    authService.logoutSession(platformRefresh, null);

    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(platformRefresh)))
        .isFalse();
  }

  /** 폐기가 일어나면 LOGOUT 감사 행이 하나 남는다. */
  @Test
  void logoutSession_writesLogoutAudit() {
    UserResponse user = createLogoutUser();
    TokenResponse a = loginLogoutUser();

    authService.logoutSession(a.refreshToken(), null);

    assertThat(sessionLogoutAuditRows(user.id())).isEqualTo(1);
  }

  /** expectedUserId 가 쿠키 행의 사용자와 다르면 아무것도 폐기하지 않고 false(쿠키를 지우지 말라). */
  @Test
  void logoutSession_otherExpectedUser_keepsFamily_returnsFalse() {
    UserResponse user = createLogoutUser();
    TokenResponse a = loginLogoutUser();

    boolean clear = authService.logoutSession(a.refreshToken(), user.id() + 1000);

    assertThat(clear).isFalse();
    assertThat(authService.refresh(a.refreshToken()).accessToken()).isNotBlank();
  }

  /** 일치하면 폐기하고 true, null(옛 호출)도 폐기하고 true. */
  @Test
  void logoutSession_matchingOrNullExpectedUser_revokes_returnsTrue() {
    UserResponse user = createLogoutUser();
    TokenResponse a = loginLogoutUser();
    TokenResponse b = loginLogoutUser();

    assertThat(authService.logoutSession(a.refreshToken(), user.id())).isTrue();
    assertThat(authService.logoutSession(b.refreshToken(), null)).isTrue();

    assertThatThrownBy(() -> authService.refresh(a.refreshToken()))
        .isInstanceOfAny(InvalidTokenException.class, RefreshTokenReusedException.class);
    assertThatThrownBy(() -> authService.refresh(b.refreshToken()))
        .isInstanceOfAny(InvalidTokenException.class, RefreshTokenReusedException.class);
  }

  /** 만료된 웹 토큰(행은 남아 있음)도 패밀리가 폐기되고 LOGOUT 감사 행이 남는다. */
  @Test
  void logoutSession_expiredWebToken_revokesAndAudits() {
    UserResponse user = createLogoutUser();
    TokenResponse a = loginLogoutUser();
    dsl.execute(
        "update refresh_token set expires_at = ? where user_id = ?",
        LocalDateTime.now().minusDays(1),
        user.id());

    assertThat(authService.logoutSession(a.refreshToken(), user.id())).isTrue();

    assertThat(refreshTokenRepository.isTokenRevoked(RefreshTokenHasher.hash(a.refreshToken())))
        .isTrue();
    assertThat(sessionLogoutAuditRows(user.id())).isEqualTo(1);
  }

  /** 만료된 운영자(PLATFORM) 행은 JWT 파싱이 실패해도 행의 평면으로 걸러져 폐기되지 않고 false 다. */
  @Test
  void logoutSession_expiredPlatformRow_notRevoked() {
    UserResponse user = createLogoutUser();
    String platformRefresh = jwtTokenProvider.generatePlatformRefreshToken(user.id());
    String hash = RefreshTokenHasher.hash(platformRefresh);
    refreshTokenRepository.save(
        user.id(),
        hash,
        LocalDateTime.now().minusDays(1),
        UUID.randomUUID(),
        RefreshTokenRepository.PLANE_PLATFORM);

    assertThat(authService.logoutSession(platformRefresh, null)).isFalse();

    assertThat(refreshTokenRepository.isTokenRevoked(hash)).isFalse();
  }

  /** 이 기기 세션 로그아웃(WD-1176) 감사 행 수. */
  private int sessionLogoutAuditRows(Long userId) {
    return dsl.fetchOne(
            "select count(*)::int from audit_log where user_id = ? and action_type = 'LOGOUT'"
                + " and description = ?",
            userId,
            "로그아웃(이 기기 세션)")
        .get(0, Integer.class);
  }
}
