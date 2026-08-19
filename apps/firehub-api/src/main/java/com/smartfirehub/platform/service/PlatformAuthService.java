package com.smartfirehub.platform.service;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.auth.service.LoginAttemptService;
import com.smartfirehub.auth.service.RefreshTokenHasher;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.platform.dto.PlatformMeResponse;
import com.smartfirehub.platform.dto.PlatformTokenResponse;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자(플랫폼) 평면 인증. 테넌트 평면의 {@code AuthService} 와 같은 자격증명·잠금·리프레시 회전
 * 기계를 쓰되, <b>플랫폼 롤 보유</b>를 추가 조건으로 요구하고 테넌트를 일절 해석하지 않는다.
 *
 * <p>{@code refresh_token} 테이블은 전역(테넌트 컬럼·RLS 없음)이라 두 평면이 같은 회전·재사용 탐지
 * 기계를 공유한다 — 운영자 토큰도 폐기 가능하고 재사용 탐지 대상이다. 해시는
 * {@link RefreshTokenHasher} 로 통일한다.
 */
@Service
@RequiredArgsConstructor
public class PlatformAuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final JwtProperties jwtProperties;
  private final RefreshTokenRepository refreshTokenRepository;
  private final LoginAttemptService loginAttemptService;
  private final PlatformRoleRepository platformRoleRepository;

  /**
   * 운영자 로그인.
   *
   * <p>자격증명 검증 뒤 <b>플랫폼 롤 보유</b>를 추가로 요구하고, 롤이 없으면 자격증명 오류와
   * <b>완전히 같은 예외·같은 메시지</b>로 거부한다. 다른 응답을 주면 "이 계정은 운영자다"를 밖에서
   * 판별할 수 있는 열거 오라클이 된다. 로그인 시도 카운터도 함께 올려, 롤 없는 계정으로 비밀번호를
   * 무한 시도하는 경로를 남기지 않는다.
   *
   * <p>비활성 계정 검사를 롤 검사보다 <b>먼저</b> 두는 이유: 그러면 비활성 계정은 운영자든 아니든
   * 같은 응답(비활성)을 받는다. 순서를 뒤집으면 비활성 비운영자만 자격증명 오류로 갈려 차이가 생긴다.
   */
  @Transactional
  public PlatformTokenResponse login(LoginRequest request) {
    if (loginAttemptService.isBlocked(request.username())) {
      throw new AccountLockedException("로그인 시도 횟수를 초과하였습니다. 잠시 후 다시 시도해 주세요.");
    }

    UserResponse user =
        userRepository.findByUsername(request.username()).orElseThrow(() -> failed(request));
    String storedPassword =
        userRepository.findPasswordByUsername(request.username()).orElseThrow(() -> failed(request));

    if (!passwordEncoder.matches(request.password(), storedPassword)) {
      throw failed(request);
    }
    if (!user.isActive()) {
      throw new UserDeactivatedException("비활성화된 계정입니다.");
    }
    // 운영자 자격. 여기서 갈리는 것이 이 서비스와 테넌트 로그인의 유일한 차이다.
    if (!platformRoleRepository.hasAnyPlatformRole(user.id())) {
      throw failed(request);
    }

    loginAttemptService.loginSucceeded(request.username());
    return issue(user.id(), user.username(), UUID.randomUUID());
  }

  /**
   * 운영자 토큰 갱신.
   *
   * <p>세 가지를 검사한다: (1) <b>평면</b> — 테넌트 리프레시 토큰으로는 플랫폼 토큰을 받을 수 없다.
   * 이것이 없으면 일반 사용자가 자기 리프레시 토큰으로 운영자 평면에 올라서는 승격 경로가 생긴다.
   * (2) 재사용 탐지와 폐기 여부(테넌트 평면과 동일한 기계). (3) <b>롤 재검증</b> — 운영자에서 내려온
   * 사용자는 갱신 시점에 끊긴다. 액세스 토큰 만료(30분)까지는 지연 반영되며, 그것이 의도된 절충이다.
   */
  @Transactional
  public PlatformTokenResponse refresh(String rawRefreshToken) {
    if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }
    if (!jwtTokenProvider.isPlatformToken(rawRefreshToken)) {
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }

    String tokenHash = RefreshTokenHasher.hash(rawRefreshToken);

    // 이미 폐기된 토큰이 다시 오면 탈취를 의심해 패밀리 전체를 폐기한다(테넌트 평면과 같은 정책).
    if (refreshTokenRepository.isTokenRevoked(tokenHash)) {
      refreshTokenRepository
          .findFamilyIdByTokenHash(tokenHash)
          .ifPresent(refreshTokenRepository::revokeByFamilyId);
      throw new InvalidTokenException("이미 사용된 토큰입니다. 다시 로그인해 주세요.");
    }
    if (!refreshTokenRepository.existsValidToken(tokenHash)) {
      throw new InvalidTokenException("만료되었거나 폐기된 토큰입니다. 다시 로그인해 주세요.");
    }

    UUID familyId =
        refreshTokenRepository
            .findFamilyIdByTokenHash(tokenHash)
            .orElseThrow(() -> new InvalidTokenException("토큰 정보를 찾을 수 없습니다. 다시 로그인해 주세요."));
    refreshTokenRepository.revokeByTokenHash(tokenHash);

    Long userId = jwtTokenProvider.getUserIdFromToken(rawRefreshToken);
    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("토큰에 해당하는 사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));
    if (!user.isActive()) {
      throw new UserDeactivatedException("비활성화된 계정입니다.");
    }
    if (!platformRoleRepository.hasAnyPlatformRole(userId)) {
      // 운영자 권한이 회수된 계정. 새 토큰을 주지 않는다.
      throw new InvalidTokenException("운영자 권한이 없습니다. 다시 로그인해 주세요.");
    }

    return issue(userId, user.username(), familyId);
  }

  /** 이 사용자의 모든 리프레시 토큰을 폐기한다. 운영자 로그아웃. */
  @Transactional
  public void logout(Long userId) {
    refreshTokenRepository.revokeAllByUserId(userId);
  }

  /** 현재 운영자 정보와 보유 권한. */
  @Transactional(readOnly = true)
  public PlatformMeResponse me(Long userId) {
    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("토큰에 해당하는 사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));
    return new PlatformMeResponse(
        user.id(),
        user.username(),
        user.name(),
        List.copyOf(platformRoleRepository.findPlatformPermissionCodes(userId)));
  }

  /** 액세스·리프레시 토큰을 발급하고 리프레시를 저장한다. */
  private PlatformTokenResponse issue(Long userId, String username, UUID familyId) {
    String accessToken = jwtTokenProvider.generatePlatformAccessToken(userId, username);
    String refreshToken = jwtTokenProvider.generatePlatformRefreshToken(userId);

    LocalDateTime expiresAt =
        LocalDateTime.now().plusSeconds(jwtProperties.refreshExpiration() / 1000);
    refreshTokenRepository.save(userId, RefreshTokenHasher.hash(refreshToken), expiresAt, familyId);

    return new PlatformTokenResponse(
        accessToken,
        refreshToken,
        "Bearer",
        jwtProperties.accessExpiration() / 1000,
        List.copyOf(platformRoleRepository.findPlatformPermissionCodes(userId)));
  }

  /** 실패 카운터를 올리고 열거 방지용 공통 예외를 만든다. */
  private InvalidCredentialsException failed(LoginRequest request) {
    loginAttemptService.loginFailed(request.username());
    return new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
  }
}
