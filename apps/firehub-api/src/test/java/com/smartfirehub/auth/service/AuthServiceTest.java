package com.smartfirehub.auth.service;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class AuthServiceTest extends IntegrationTestBase {

    @Autowired
    private AuthService authService;

    @Test
    void signup_firstUser_assignsAdminAndUserRoles() {
        SignupRequest request = new SignupRequest("test@example.com", "test@example.com", "password123", "Test User");

        UserResponse result = authService.signup(request);

        assertThat(result.username()).isEqualTo("test@example.com");
        assertThat(result.id()).isNotNull();
    }

    @Test
    void signup_subsequentUser_assignsUserRoleOnly() {
        authService.signup(new SignupRequest("first@example.com", "first@example.com", "password123", "First User"));

        SignupRequest request = new SignupRequest("second@example.com", "second@example.com", "password123", "Second User");
        UserResponse result = authService.signup(request);

        assertThat(result.username()).isEqualTo("second@example.com");
    }

    @Test
    void signup_duplicateUsername_throws() {
        authService.signup(new SignupRequest("test@example.com", "test1@example.com", "password123", "Test User"));

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("test@example.com", "test2@example.com", "password123", "Test User 2")))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    void signup_duplicateEmail_throws() {
        authService.signup(new SignupRequest("user1@example.com", "same@example.com", "password123", "User 1"));

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("user2@example.com", "same@example.com", "password123", "User 2")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void signup_nullEmail_success() {
        SignupRequest request = new SignupRequest("test@example.com", null, "password123", "Test User");

        UserResponse result = authService.signup(request);

        assertThat(result.username()).isEqualTo("test@example.com");
    }

    @Test
    void login_success() {
        authService.signup(new SignupRequest("test@example.com", "test@example.com", "password123", "Test User"));

        TokenResponse result = authService.login(new LoginRequest("test@example.com", "password123"));

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isGreaterThan(0);
    }

    @Test
    void login_wrongPassword_throws() {
        authService.signup(new SignupRequest("test@example.com", "test@example.com", "password123", "Test User"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "wrongpassword")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_userNotFound_throws() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("unknown@example.com", "password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_success() throws InterruptedException {
        authService.signup(new SignupRequest("test@example.com", "test@example.com", "password123", "Test User"));
        TokenResponse loginResult = authService.login(new LoginRequest("test@example.com", "password123"));

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
        UserResponse user = authService.signup(new SignupRequest("test@example.com", "test@example.com", "password123", "Test User"));
        TokenResponse loginResult = authService.login(new LoginRequest("test@example.com", "password123"));

        // Logout revokes all tokens
        authService.logout(user.id());

        // Try to use the revoked refresh token
        assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Refresh token has been revoked");
    }

    @Test
    void logout_revokesAllTokens() {
        UserResponse user = authService.signup(new SignupRequest("test@example.com", "test@example.com", "password123", "Test User"));
        TokenResponse loginResult = authService.login(new LoginRequest("test@example.com", "password123"));

        authService.logout(user.id());

        assertThatThrownBy(() -> authService.refresh(loginResult.refreshToken()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void getCurrentUser_success() {
        UserResponse created = authService.signup(new SignupRequest("test@example.com", "test@example.com", "password123", "Test User"));

        UserResponse result = authService.getCurrentUser(created.id());

        assertThat(result.id()).isEqualTo(created.id());
        assertThat(result.username()).isEqualTo("test@example.com");
    }
}
