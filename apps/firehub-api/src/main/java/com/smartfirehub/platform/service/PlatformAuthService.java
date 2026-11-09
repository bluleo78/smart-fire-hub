package com.smartfirehub.platform.service;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.RefreshTokenReusedException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.auth.service.LoginAttemptService;
import com.smartfirehub.auth.service.LoginIpThrottleService;
import com.smartfirehub.auth.service.RefreshTokenHasher;
import com.smartfirehub.auth.service.RefreshTokenRotation;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.util.ClientIpExtractor;
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
 * 운영자(플랫폼) 평면 인증. 테넌트 평면의 {@code AuthService} 와 같은 자격증명·잠금·리프레시 회전 기계를 쓰되, <b>플랫폼 롤 보유</b>를 추가 조건으로
 * 요구하고 테넌트를 일절 해석하지 않는다.
 *
 * <p>{@code refresh_token} 테이블은 전역(테넌트 컬럼·RLS 없음)이라 두 평면이 같은 회전·재사용 탐지 기계를 공유한다 — 운영자 토큰도 폐기 가능하고
 * 재사용 탐지 대상이다. 해시는 {@link RefreshTokenHasher} 로 통일한다.
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
  private final RefreshTokenRotation refreshTokenRotation;

  /** 같은 IP 에서 여러 계정에 돌려 보는 비밀번호 스프레이를 막는 IP 기준 제한(WD-578) — 테넌트 로그인과 같은 정책·같은 카운터. */
  private final LoginIpThrottleService loginIpThrottleService;

  /** IP 제한의 요청 IP 를 신뢰 프록시 기준으로 고른다(WD-402) — X-Forwarded-For 위조로 IP 키를 바꿔 피하지 못하게. */
  private final ClientIpExtractor clientIpExtractor;

  /**
   * 운영자 로그인.
   *
   * <p>자격증명 검증 뒤 <b>플랫폼 롤 보유</b>를 추가로 요구하고, 롤이 없으면 자격증명 오류와 <b>완전히 같은 예외·같은 메시지</b>로 거부한다. 다른 응답을
   * 주면 "이 계정은 운영자다"를 밖에서 판별할 수 있는 열거 오라클이 된다. 로그인 시도 카운터도 함께 올려, 롤 없는 계정으로 비밀번호를 무한 시도하는 경로를 남기지
   * 않는다.
   *
   * <p>비활성 계정 검사를 롤 검사보다 <b>먼저</b> 두는 이유: 그러면 비활성 계정은 운영자든 아니든 같은 응답(비활성)을 받는다. 순서를 뒤집으면 비활성 비운영자만
   * 자격증명 오류로 갈려 차이가 생긴다.
   *
   * <p>실패 예외(잠금·자격 증명 불일치·비활성)에서는 롤백하지 않는다(WD-552) — 실패 카운터가 이 트랜잭션에 실리므로(커넥션 1개) 롤백되면 카운터가 사라져
   * 브루트포스 잠금이 무력해진다. 실패 경로에서 이 트랜잭션이 쓰는 것은 카운터뿐이라 커밋돼도 안전하다.
   *
   * <p>계정 잠금보다 먼저 IP 기준 제한(WD-578)을 본다. 실패 카운터를 올리는 모든 경로({@link #failed}: 없는 계정·비밀번호 불일치·롤 없음)에서
   * IP 실패도 함께 기록한다 — 테넌트 평면과 카운터를 공유하므로 두 로그인 화면을 번갈아 써도 피할 수 없다.
   *
   * <p>롤 없음은 비밀번호가 맞았는데도 IP 실패로 센다(WD-630 리뷰). 롤 없음의 401 은 열거 방지를 위해 비밀번호 불일치와 <b>똑같다</b>. 그런데 맞는
   * 비밀번호만 IP 카운터에서 빠지면, 공격자가 한도 직전에서 후보 비밀번호를 넣어 보고 다음 요청이 429 인지 아닌지로 "방금 비밀번호가 맞았다"를 알아낼 수 있다(IP
   * 카운터가 비밀번호 오라클이 된다). 테넌트의 비활성 계정은 응답이 달라(비활성 안내) 이미 맞았음을 알리므로 같은 문제가 없다.
   *
   * <p>같은 IP 키 줄 세우기는 트랜잭션 밖 {@code LoginIpGate} 가 한다(WD-630).
   */
  @Transactional(
      noRollbackFor = {
        AccountLockedException.class,
        InvalidCredentialsException.class,
        UserDeactivatedException.class
      })
  public PlatformTokenResponse login(LoginRequest request) {
    // 대소문자·앞뒤 공백만 다른 입력도 같은 계정으로 본다(WD-269, 테넌트 평면과 대칭).
    String username = userRepository.resolveLoginUsername(request.username());
    // 요청 컨텍스트가 없으면(내부 호출·테스트) null 이고 IP 제한을 적용하지 않는다.
    String clientIp = clientIpExtractor.currentRequestIp();
    if (loginIpThrottleService.blockedKey(clientIp).isPresent()) {
      throw new AccountLockedException(LoginIpThrottleService.BLOCKED_MESSAGE);
    }
    if (loginAttemptService.isBlocked(username)) {
      throw new AccountLockedException("로그인 시도 횟수를 초과하였습니다. 잠시 후 다시 시도해 주세요.");
    }

    UserResponse user =
        userRepository.findByUsername(username).orElseThrow(() -> failed(username, clientIp));
    String storedPassword =
        userRepository
            .findPasswordByUsername(username)
            .orElseThrow(() -> failed(username, clientIp));

    if (!passwordEncoder.matches(request.password(), storedPassword)) {
      throw failed(username, clientIp);
    }
    if (!user.isActive()) {
      // 테넌트 로그인과 같은 문구 — 비밀번호가 맞을 때만 알리고 할 일까지 적는다(WD-278)
      throw new UserDeactivatedException();
    }
    // 운영자 자격. 여기서 갈리는 것이 이 서비스와 테넌트 로그인의 유일한 차이다.
    if (!platformRoleRepository.hasAnyPlatformRole(user.id())) {
      // 비밀번호가 맞았어도 IP 실패로 센다 — 응답이 같으므로 빼면 IP 카운터가 비밀번호 오라클이 된다(WD-630 리뷰).
      throw failed(username, clientIp);
    }

    loginAttemptService.loginSucceeded(username);
    return issue(user.id(), user.username(), UUID.randomUUID());
  }

  /**
   * 운영자 토큰 갱신.
   *
   * <p>세 가지를 검사한다: (1) <b>평면</b> — 테넌트 리프레시 토큰으로는 플랫폼 토큰을 받을 수 없다. 이것이 없으면 일반 사용자가 자기 리프레시 토큰으로 운영자
   * 평면에 올라서는 승격 경로가 생긴다. (2) 재사용 탐지와 폐기 여부(테넌트 평면과 동일한 기계). (3) <b>롤 재검증</b> — 운영자에서 내려온 사용자는 갱신
   * 시점에 끊긴다. 액세스 토큰 만료(30분)까지는 지연 반영되며, 그것이 의도된 절충이다.
   */
  // 재사용 탐지가 쓴 패밀리 폐기는 401 과 함께 커밋돼야 한다(WD-268, RefreshTokenRotation 호출자 계약).
  @Transactional(noRollbackFor = RefreshTokenReusedException.class)
  public PlatformTokenResponse refresh(String rawRefreshToken) {
    if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }
    if (!jwtTokenProvider.isPlatformToken(rawRefreshToken)) {
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }

    // 회전·재사용 탐지는 두 평면이 공유하는 정책이다(RefreshTokenRotation 주석 참고).
    UUID familyId =
        refreshTokenRotation.revokeAndGetFamily(RefreshTokenHasher.hash(rawRefreshToken));

    Long userId = jwtTokenProvider.getUserIdFromToken(rawRefreshToken);
    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("토큰에 해당하는 사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));
    if (!user.isActive()) {
      throw new UserDeactivatedException();
    }
    if (!platformRoleRepository.hasAnyPlatformRole(userId)) {
      // 운영자 권한이 회수된 계정. 새 토큰을 주지 않는다.
      throw new InvalidTokenException("운영자 권한이 없습니다. 다시 로그인해 주세요.");
    }

    return issue(userId, user.username(), familyId);
  }

  /**
   * 운영자 로그아웃. 제시된 리프레시 토큰의 <b>패밀리만</b> 폐기한다.
   *
   * <p>{@code revokeAllByUserId} 를 쓰지 않는 이유: {@code refresh_token} 은 전역 테이블이고 평면 컬럼이 없어서 사용자 단위 폐기는
   * <b>평면을 가리지 않는다</b>. 운영자이면서 일반 사용자인 계정이 운영자 콘솔에서 로그아웃하면 firehub-web 세션까지 끊긴다 — 쿠키 이름과 path 를
   * 평면별로 분리해 "서로의 세션을 끊지 않게" 만든 것을 로그아웃이 되돌리는 셈이다. 패밀리 단위 폐기는 회전 체인이 로그인마다 새로 시작되므로 정확히 이 콘솔 세션만
   * 끊는다.
   *
   * <p>쿠키가 없으면 서버 측에 끊을 세션이 특정되지 않으므로 아무것도 폐기하지 않는다(컨트롤러가 쿠키를 지우고, 액세스 토큰은 만료로 사라진다). 평면별 전체 로그아웃이
   * 필요해지면 {@code refresh_token.plane}(V154, WD-314)으로 거를 수 있다 — 웹 로그아웃은 이미 웹 평면만 폐기한다.
   */
  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
    refreshTokenRepository
        .findFamilyIdByTokenHash(RefreshTokenHasher.hash(rawRefreshToken))
        .ifPresent(refreshTokenRepository::revokeByFamilyId);
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
    // 운영자 평면으로 저장한다(WD-314) — 웹 로그아웃(웹 평면 폐기)이 이 세션을 끊지 않게.
    refreshTokenRepository.save(
        userId,
        RefreshTokenHasher.hash(refreshToken),
        expiresAt,
        familyId,
        RefreshTokenRepository.PLANE_PLATFORM);

    return new PlatformTokenResponse(
        accessToken,
        refreshToken,
        "Bearer",
        jwtProperties.accessExpiration() / 1000,
        List.copyOf(platformRoleRepository.findPlatformPermissionCodes(userId)));
  }

  /** 계정 실패 카운터와 IP 실패 기록(WD-578)을 함께 올리고 열거 방지용 공통 예외를 만든다. */
  private InvalidCredentialsException failed(String username, String clientIp) {
    loginAttemptService.loginFailed(username);
    loginIpThrottleService.loginFailed(clientIp, username);
    return new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
  }
}
