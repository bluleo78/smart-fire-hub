package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.repository.UserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트 격리: LoginAttemptRepository.incrementAttempts/clear가 REQUIRES_NEW로 독립 커밋하므로 테스트 @Transactional
 * 롤백을 빠져나가 login_attempts 테이블에 잔존 → 다음 실행 시 unknown@example.com이 잠금 상태로 시작해
 * InvalidCredentialsException 대신 AccountLockedException 반환(#flaky). 매 테스트 전 ISOLATED 트랜잭션으로 테이블을
 * 비운다.
 */
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

    // Try to use the revoked refresh token — reuse detection kicks in
    assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("이미 사용된 토큰입니다. 다시 로그인해 주세요.");
  }

  @Test
  void refresh_reusedToken_revokesEntireFamily() throws InterruptedException {
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

    String firstRefreshToken = loginResult.refreshToken();

    Thread.sleep(1100);

    // Normal rotation: use first token to get second token
    TokenResponse secondResult = authService.refresh(firstRefreshToken);
    String secondRefreshToken = secondResult.refreshToken();

    // Simulate attacker reusing the first (already rotated) token
    // This should revoke the entire token family, including the second token
    assertThatThrownBy(() -> authService.refresh(firstRefreshToken))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("이미 사용된 토큰입니다. 다시 로그인해 주세요.");

    // The legitimate second token should also be revoked (family revocation)
    assertThatThrownBy(() -> authService.refresh(secondRefreshToken))
        .isInstanceOf(InvalidTokenException.class);
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
        .hasMessage("비활성화된 계정입니다.");
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
}
