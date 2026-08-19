package com.smartfirehub.auth.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.TenantAccessDeniedException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final JwtProperties jwtProperties;
  private final RefreshTokenRepository refreshTokenRepository;
  private final LoginAttemptService loginAttemptService;
  private final AuditLogService auditLogService;
  private final MembershipRepository membershipRepository;
  private final SignupTransaction signupTransaction;
  private final CurrentTransactionTenant currentTransactionTenant;

  /**
   * 회원가입.
   *
   * <p>트랜잭션이 없는 이유: /api/v1/auth/signup 은 permitAll 이라 JWT 가 없고 TenantContext 도
   * 비어 있다. 그런데 내부에서 role 을 이름으로 조회하므로, role 에 RLS 가 걸리면 컨텍스트 없이는
   * 0행이 되어 가입이 깨진다. GUC 는 트랜잭션이 열리는 순간(doBegin)에 확정되므로, 트랜잭션
   * 경계보다 <b>바깥에서</b> 기본 테넌트를 세워야 한다.
   */
  public UserResponse signup(SignupRequest request) {
    return TenantContext.runScopedGet(
        MembershipRepository.DEFAULT_TENANT_ID, () -> signupTransaction.execute(request));
  }

  @Transactional
  public TokenResponse login(LoginRequest request) {
    if (loginAttemptService.isBlocked(request.username())) {
      // 과도한 로그인 실패로 계정이 잠긴 경우 한국어 메시지 반환
      throw new AccountLockedException("로그인 시도 횟수를 초과하였습니다. 잠시 후 다시 시도해 주세요.");
    }

    UserResponse user =
        userRepository
            .findByUsername(request.username())
            .orElseThrow(
                () -> {
                  loginAttemptService.loginFailed(request.username());
                  // 사용자 존재 여부 노출 방지를 위해 동일한 메시지 반환
                  return new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
                });

    String storedPassword =
        userRepository
            .findPasswordByUsername(request.username())
            .orElseThrow(
                () -> {
                  loginAttemptService.loginFailed(request.username());
                  // 사용자 존재 여부 노출 방지를 위해 동일한 메시지 반환
                  return new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
                });

    if (!passwordEncoder.matches(request.password(), storedPassword)) {
      loginAttemptService.loginFailed(request.username());
      throw new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    if (!user.isActive()) {
      // 비활성화된 계정 접근 시 한국어 메시지 반환
      throw new UserDeactivatedException("비활성화된 계정입니다.");
    }

    loginAttemptService.loginSucceeded(request.username());

    // 소속이 정확히 하나면 즉시 자동 선택해 테넌트 스코프 토큰을 바로 발급한다(선택 화면 생략).
    // 0개 또는 2개 이상이면 테넌트 미선택 토큰을 주고 클라이언트가 select-tenant 를 호출한다.
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(user.id());
    Long activeTenantId = MembershipResponse.soleActiveTenant(memberships).orElse(null);

    // 로그인은 새 리프레시 토큰 패밀리를 시작한다.
    TokenResponse tokenResponse =
        issueTokenPair(user.id(), user.username(), activeTenantId, UUID.randomUUID(), memberships);

    // 로그인 감사 로그 (#60/#92).
    //
    // 테넌트 컨텍스트 안에서 기록해야 하는 이유: /auth/login 은 permitAll 이라 JWT 도 TenantContext
    // 도 없다. 그대로 쓰면 audit_log.tenant_id 가 NULL 이 되고, V99 의 형태 (b) USING 정책이 NULL
    // 테넌트 행을 모든 테넌트 스코프 조회에서 감춘다 — 감사 화면·대시보드에 LOGOUT(JWT 아래에서
    // 기록되어 테넌트가 붙는다)만 보이고 짝이 되는 LOGIN 이 사라진다.
    //
    // GUC 는 트랜잭션이 열리는 순간(doBegin)에만 심긴다. 이 메서드는 @Transactional 이라 트랜잭션이
    // 이미 열린 뒤에야 activeTenantId 가 정해지므로, 여기서 TenantContext 를 세워도 효과가 없다.
    // 그래서 현재 트랜잭션에 GUC 를 직접 심는다(CurrentTransactionTenant 주석 참고). 감사 기록은
    // 로그인 성공이 확정되고 토큰까지 발급된 뒤 마지막에 일어나므로, 남은 문장에 GUC 가 붙어도
    // 영향을 받는 조회가 없다.
    //
    // 활성 테넌트가 확정되지 않은 로그인(멤버십 0개 또는 2개 이상)과, 테넌트 해석 이전에 기록되는
    // 로그인 실패 감사 행은 설계상 NULL 테넌트로 남는다 — 어느 테넌트의 사건인지 알 수 없기 때문이다.
    String[] requestInfo = extractRequestInfo();
    if (activeTenantId != null) {
      currentTransactionTenant.apply(activeTenantId);
    }
    auditLogService.log(
        user.id(),
        user.username(),
        "LOGIN",
        "auth",
        null,
        "로그인 성공",
        requestInfo[0],
        requestInfo[1],
        "SUCCESS",
        null,
        null);

    return tokenResponse;
  }

  @Transactional
  public TokenResponse refresh(String rawRefreshToken) {
    if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
      // 만료되었거나 유효하지 않은 리프레시 토큰 — 한국어 메시지 반환
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }

    String tokenHash = hashToken(rawRefreshToken);

    // Token reuse detection: if the token was already revoked, an attacker may have
    // stolen a previously used token. Revoke the entire token family for safety.
    if (refreshTokenRepository.isTokenRevoked(tokenHash)) {
      refreshTokenRepository
          .findFamilyIdByTokenHash(tokenHash)
          .ifPresent(refreshTokenRepository::revokeByFamilyId);
      // 이미 사용된 토큰 재사용 시도 — 보안 이슈, 한국어 메시지 반환
      throw new InvalidTokenException("이미 사용된 토큰입니다. 다시 로그인해 주세요.");
    }

    if (!refreshTokenRepository.existsValidToken(tokenHash)) {
      // 폐기된 토큰으로 갱신 시도 — 한국어 메시지 반환
      throw new InvalidTokenException("만료되었거나 폐기된 토큰입니다. 다시 로그인해 주세요.");
    }

    // Look up the family before revoking the current token
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
      // 비활성화된 계정으로 토큰 갱신 시도 — 한국어 메시지 반환
      throw new UserDeactivatedException("비활성화된 계정입니다.");
    }

    // 갱신 시 활성 테넌트를 유지하되 멤버십/테넌트 상태를 재검증한다. 정지됐으면 테넌트 미선택으로
    // 강등해, 정지된 테넌트가 무기한 사용되지 않게 한다.
    Long claimedTenantId = jwtTokenProvider.getTenantIdFromToken(rawRefreshToken);
    Long activeTenantId =
        (claimedTenantId != null
                && membershipRepository.hasActiveMembership(user.id(), claimedTenantId))
            ? claimedTenantId
            : null;

    // activeTenantId 가 강등(null)되어도 memberships 는 실제 목록을 채운다 — 비워두면 클라이언트가
    // 재선택이 필요하다는 사실 자체를 알 방법이 없어 토큰만 테넌트-미선택으로 멈춘 채 복구 불가능해진다.
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(user.id());

    // 갱신은 기존 패밀리를 이어간다(재사용 탐지가 패밀리 단위로 이루어지므로).
    return issueTokenPair(user.id(), user.username(), activeTenantId, familyId, memberships);
  }

  @Transactional
  public void logout(Long userId) {
    refreshTokenRepository.revokeAllByUserId(userId);
    // 로그아웃 감사 로그 (#60/#92)
    userRepository
        .findById(userId)
        .ifPresent(
            user -> {
              String[] requestInfo = extractRequestInfo();
              auditLogService.log(
                  userId,
                  user.username(),
                  "LOGOUT",
                  "auth",
                  null,
                  "로그아웃",
                  requestInfo[0],
                  requestInfo[1],
                  "SUCCESS",
                  null,
                  null);
            });
  }

  @Transactional(readOnly = true)
  public UserResponse getCurrentUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new InvalidTokenException("User not found"));
  }

  /** 사용자가 선택할 수 있는 테넌트 목록. 테넌트 전환 UI 가 사용한다. */
  @Transactional(readOnly = true)
  public List<MembershipResponse> getMemberships(Long userId) {
    return membershipRepository.findActiveByUser(userId);
  }

  /**
   * 활성 테넌트를 선택한다. 테넌트 전환도 같은 경로를 쓴다 — 액세스/리프레시 토큰을 모두 재발급해
   * 이후 요청이 새 테넌트 컨텍스트로 흐르게 한다.
   *
   * @throws TenantAccessDeniedException 소속이 아니거나 멤버십/테넌트가 정지된 경우
   */
  @Transactional
  public TokenResponse selectTenant(Long userId, Long tenantId) {
    if (!membershipRepository.hasActiveMembership(userId, tenantId)) {
      throw new TenantAccessDeniedException("해당 워크스페이스에 접근할 수 없습니다.");
    }

    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));

    if (!user.isActive()) {
      throw new UserDeactivatedException("비활성화된 계정입니다.");
    }

    // 테넌트 전환 시 새 리프레시 토큰 패밀리를 시작한다(이전 패밀리와 섞이지 않게).
    return issueTokenPair(userId, user.username(), tenantId, UUID.randomUUID(), List.of());
  }

  /**
   * 액세스/리프레시 토큰을 발급하고 리프레시 토큰을 저장한 뒤 응답을 만든다.
   *
   * <p>login/refresh/selectTenant 는 tenantId 산출 방식, familyId 를 새로 시작할지 재사용할지,
   * memberships 를 실제 목록으로 채울지 비워둘지만 다르고 그 이후 처리(토큰 발급·저장·응답 조립)는
   * 동일하므로 이 부분만 공유한다.
   */
  private TokenResponse issueTokenPair(
      Long userId,
      String username,
      Long tenantId,
      UUID familyId,
      List<MembershipResponse> memberships) {
    String accessToken = jwtTokenProvider.generateAccessToken(userId, username, tenantId);
    String refreshToken = jwtTokenProvider.generateRefreshToken(userId, tenantId);

    storeRefreshToken(userId, refreshToken, familyId);

    return new TokenResponse(
        accessToken,
        refreshToken,
        "Bearer",
        jwtProperties.accessExpiration() / 1000,
        tenantId,
        memberships);
  }

  private void storeRefreshToken(Long userId, String refreshToken, UUID familyId) {
    String tokenHash = hashToken(refreshToken);
    LocalDateTime expiresAt =
        LocalDateTime.now().plusSeconds(jwtProperties.refreshExpiration() / 1000);
    refreshTokenRepository.save(userId, tokenHash, expiresAt, familyId);
  }

  /** HTTP 요청 컨텍스트에서 IP와 User-Agent를 추출한다. 감사 로그에 사용 */
  private String[] extractRequestInfo() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes sra) {
      HttpServletRequest req = sra.getRequest();
      String forwarded = req.getHeader("X-Forwarded-For");
      String ip =
          (forwarded != null && !forwarded.isBlank())
              ? forwarded.split(",")[0].trim()
              : req.getRemoteAddr();
      return new String[] {ip, req.getHeader("User-Agent")};
    }
    return new String[] {null, null};
  }

  /**
   * 저장용 토큰 해시.
   *
   * <p>구현은 {@link RefreshTokenHasher} 로 옮겼다 — 운영자 평면(P7-a)이 같은 {@code refresh_token}
   * 테이블을 쓰므로 두 평면이 반드시 같은 해시를 만들어야 한다.
   */
  private String hashToken(String token) {
    return RefreshTokenHasher.hash(token);
  }
}
