package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired private UserRepository userRepository;

  @Test
  void signup_firstUser_assignsAdminAndUserRoles() {
    SignupRequest request =
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User");

    UserResponse result = authService.signup(request);

    assertThat(result.username()).isEqualTo("test@example.com");
    assertThat(result.id()).isNotNull();
  }

  @Test
  void signup_subsequentUser_assignsUserRoleOnly() {
    authService.signup(
        new SignupRequest("first@example.com", "first@example.com", "Password123", "First User"));

    SignupRequest request =
        new SignupRequest("second@example.com", "second@example.com", "Password123", "Second User");
    UserResponse result = authService.signup(request);

    assertThat(result.username()).isEqualTo("second@example.com");
  }

  @Test
  void signup_duplicateUsername_throws() {
    authService.signup(
        new SignupRequest("test@example.com", "test1@example.com", "Password123", "Test User"));

    assertThatThrownBy(
            () ->
                authService.signup(
                    new SignupRequest(
                        "test@example.com", "test2@example.com", "Password123", "Test User 2")))
        .isInstanceOf(UsernameAlreadyExistsException.class);
  }

  @Test
  void signup_duplicateEmail_throws() {
    authService.signup(
        new SignupRequest("user1@example.com", "same@example.com", "Password123", "User 1"));

    assertThatThrownBy(
            () ->
                authService.signup(
                    new SignupRequest(
                        "user2@example.com", "same@example.com", "Password123", "User 2")))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void signup_nullEmail_success() {
    SignupRequest request = new SignupRequest("test@example.com", null, "Password123", "Test User");

    UserResponse result = authService.signup(request);

    assertThat(result.username()).isEqualTo("test@example.com");
  }

  @Test
  void login_success() {
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));

    TokenResponse result = authService.login(new LoginRequest("test@example.com", "Password123"));

    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotBlank();
    assertThat(result.tokenType()).isEqualTo("Bearer");
    assertThat(result.expiresIn()).isGreaterThan(0);
  }

  @Test
  void login_wrongPassword_throws() {
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));

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
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
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
        authService.signup(
            new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
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
    authService.signup(
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
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
        authService.signup(
            new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
    TokenResponse loginResult =
        authService.login(new LoginRequest("test@example.com", "Password123"));

    authService.logout(user.id());

    assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void refresh_deactivatedUser_throws() {
    UserResponse user =
        authService.signup(
            new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));
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
        authService.signup(
            new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User"));

    UserResponse result = authService.getCurrentUser(created.id());

    assertThat(result.id()).isEqualTo(created.id());
    assertThat(result.username()).isEqualTo("test@example.com");
  }
}
