package com.smartfirehub.auth.service;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private AuthService authService;

    private UserResponse testUser;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(
                "dGhpcyBpcyBhIHZlcnkgbG9uZyBzZWNyZXQga2V5IGZvciBIUzI1NiBhbGdvcml0aG0gdGhhdCBpcyBhdCBsZWFzdCAyNTYgYml0cw==",
                1800000L,
                604800000L
        );
        authService = new AuthService(userRepository, roleRepository, passwordEncoder, jwtTokenProvider, jwtProperties, refreshTokenRepository);
        testUser = new UserResponse(1L, "test@example.com", "test@example.com", "Test User", true, LocalDateTime.now());
    }

    @Test
    void signup_firstUser_assignsAdminAndUserRoles() {
        SignupRequest request = new SignupRequest("test@example.com", "test@example.com", "password123", "Test User");
        RoleResponse adminRole = new RoleResponse(1L, "ADMIN", "시스템 관리자", true);
        RoleResponse userRole = new RoleResponse(2L, "USER", "일반 사용자", true);

        when(userRepository.existsByUsername("test@example.com")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.countAll(null)).thenReturn(0L);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save("test@example.com", "test@example.com", "encoded", "Test User")).thenReturn(testUser);
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));

        UserResponse result = authService.signup(request);

        assertThat(result.username()).isEqualTo("test@example.com");
        verify(userRepository).addRole(1L, 2L);
        verify(userRepository).addRole(1L, 1L);
    }

    @Test
    void signup_subsequentUser_assignsUserRoleOnly() {
        SignupRequest request = new SignupRequest("test@example.com", "test@example.com", "password123", "Test User");
        RoleResponse userRole = new RoleResponse(2L, "USER", "일반 사용자", true);

        when(userRepository.existsByUsername("test@example.com")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.countAll(null)).thenReturn(1L);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save("test@example.com", "test@example.com", "encoded", "Test User")).thenReturn(testUser);
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));

        UserResponse result = authService.signup(request);

        assertThat(result.username()).isEqualTo("test@example.com");
        verify(userRepository).addRole(1L, 2L);
    }

    @Test
    void signup_duplicateUsername_throws() {
        SignupRequest request = new SignupRequest("test@example.com", "test@example.com", "password123", "Test User");
        when(userRepository.existsByUsername("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    void signup_duplicateEmail_throws() {
        SignupRequest request = new SignupRequest("test@example.com", "personal@example.com", "password123", "Test User");
        when(userRepository.existsByUsername("test@example.com")).thenReturn(false);
        when(userRepository.existsByEmail("personal@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void signup_nullEmail_success() {
        SignupRequest request = new SignupRequest("test@example.com", null, "password123", "Test User");
        RoleResponse userRole = new RoleResponse(2L, "USER", "일반 사용자", true);

        when(userRepository.existsByUsername("test@example.com")).thenReturn(false);
        when(userRepository.countAll(null)).thenReturn(1L);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save("test@example.com", null, "encoded", "Test User")).thenReturn(testUser);
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(userRole));

        UserResponse result = authService.signup(request);

        assertThat(result.username()).isEqualTo("test@example.com");
    }

    @Test
    void login_success() {
        LoginRequest request = new LoginRequest("test@example.com", "password123");

        when(userRepository.findByUsername("test@example.com")).thenReturn(Optional.of(testUser));
        when(userRepository.findPasswordByUsername("test@example.com")).thenReturn(Optional.of("encoded"));
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(1L, "test@example.com")).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("refresh-token");

        TokenResponse result = authService.login(request);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(1800);
        verify(refreshTokenRepository).save(eq(1L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void login_wrongPassword_throws() {
        LoginRequest request = new LoginRequest("test@example.com", "wrong");

        when(userRepository.findByUsername("test@example.com")).thenReturn(Optional.of(testUser));
        when(userRepository.findPasswordByUsername("test@example.com")).thenReturn(Optional.of("encoded"));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_userNotFound_throws() {
        LoginRequest request = new LoginRequest("unknown@example.com", "password");

        when(userRepository.findByUsername("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_success() {
        String rawToken = "valid-refresh-token";

        when(jwtTokenProvider.validateRefreshToken(rawToken)).thenReturn(true);
        when(refreshTokenRepository.existsValidToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(rawToken)).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(jwtTokenProvider.generateAccessToken(1L, "test@example.com")).thenReturn("new-access");
        when(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("new-refresh");

        TokenResponse result = authService.refresh(rawToken);

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenRepository).revokeByTokenHash(anyString());
        verify(refreshTokenRepository).save(eq(1L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void refresh_invalidToken_throws() {
        when(jwtTokenProvider.validateRefreshToken("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh("bad-token"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refresh_revokedToken_throws() {
        String rawToken = "revoked-token";

        when(jwtTokenProvider.validateRefreshToken(rawToken)).thenReturn(true);
        when(refreshTokenRepository.existsValidToken(anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(rawToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Refresh token has been revoked");
    }

    @Test
    void logout_revokesAllTokens() {
        authService.logout(1L);

        verify(refreshTokenRepository).revokeAllByUserId(1L);
    }

    @Test
    void getCurrentUser_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        UserResponse result = authService.getCurrentUser(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.username()).isEqualTo("test@example.com");
    }
}
