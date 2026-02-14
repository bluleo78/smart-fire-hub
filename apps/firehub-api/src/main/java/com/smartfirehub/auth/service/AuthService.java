package com.smartfirehub.auth.service;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.UsernameAlreadyExistsException;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public UserResponse signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new UsernameAlreadyExistsException("Username already exists: " + request.username());
        }
        if (request.email() != null && !request.email().isBlank()
                && userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException("Email already exists: " + request.email());
        }

        boolean isFirstUser = userRepository.countAll(null) == 0;

        String encodedPassword = passwordEncoder.encode(request.password());
        UserResponse user = userRepository.save(request.username(), request.email(), encodedPassword, request.name());

        // Assign roles: first user gets ADMIN + USER, subsequent users get USER only
        roleRepository.findByName("USER")
                .ifPresent(role -> userRepository.addRole(user.id(), role.id()));

        if (isFirstUser) {
            roleRepository.findByName("ADMIN")
                    .ifPresent(role -> userRepository.addRole(user.id(), role.id()));
        }

        return user;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserResponse user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        String storedPassword = userRepository.findPasswordByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), storedPassword)) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        if (!user.isActive()) {
            throw new UserDeactivatedException("User account is deactivated");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.id(), user.username());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.id());

        storeRefreshToken(user.id(), refreshToken);

        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtProperties.accessExpiration() / 1000);
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        String tokenHash = hashToken(rawRefreshToken);
        if (!refreshTokenRepository.existsValidToken(tokenHash)) {
            throw new InvalidTokenException("Refresh token has been revoked");
        }

        refreshTokenRepository.revokeByTokenHash(tokenHash);

        Long userId = jwtTokenProvider.getUserIdFromToken(rawRefreshToken);
        UserResponse user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User not found for token"));

        String accessToken = jwtTokenProvider.generateAccessToken(user.id(), user.username());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.id());

        storeRefreshToken(user.id(), newRefreshToken);

        return new TokenResponse(accessToken, newRefreshToken, "Bearer", jwtProperties.accessExpiration() / 1000);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
    }

    public UserResponse getCurrentUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("User not found"));
    }

    private void storeRefreshToken(Long userId, String refreshToken) {
        String tokenHash = hashToken(refreshToken);
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(jwtProperties.refreshExpiration() / 1000);
        refreshTokenRepository.save(userId, tokenHash, expiresAt);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
