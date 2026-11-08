package com.smartfirehub.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.exception.InvalidCredentialsException;
import com.smartfirehub.auth.exception.InvalidTokenException;
import com.smartfirehub.auth.exception.RefreshTokenReusedException;
import com.smartfirehub.auth.exception.TenantAccessDeniedException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.ClientIpExtractor;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.dto.UnavailableWorkspace;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserDeactivatedException;
import com.smartfirehub.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider jwtTokenProvider;
  private final JwtProperties jwtProperties;
  private final RefreshTokenRepository refreshTokenRepository;
  private final LoginAttemptService loginAttemptService;

  /** 같은 IP 에서 여러 계정에 돌려 보는 비밀번호 스프레이를 막는 IP 기준 제한(WD-578). */
  private final LoginIpThrottleService loginIpThrottleService;

  private final AuditLogService auditLogService;
  private final MembershipRepository membershipRepository;
  private final SignupTransaction signupTransaction;
  private final CurrentTransactionTenant currentTransactionTenant;
  private final RefreshTokenRotation refreshTokenRotation;
  private final PlatformTransactionManager transactionManager;

  /** 감사 IP 를 신뢰 프록시 기준으로 고른다(WD-402) — X-Forwarded-For 위조로 감사 IP 를 속이지 못하게. */
  private final ClientIpExtractor clientIpExtractor;

  /**
   * 회원가입.
   *
   * <p>트랜잭션이 없는 이유: /api/v1/auth/signup 은 permitAll 이라 JWT 가 없고 TenantContext 도 비어 있다. 그런데 내부에서
   * role 을 이름으로 조회하므로, role 에 RLS 가 걸리면 컨텍스트 없이는 0행이 되어 가입이 깨진다. GUC 는 트랜잭션이 열리는 순간(doBegin)에
   * 확정되므로, 트랜잭션 경계보다 <b>바깥에서</b> 기본 테넌트를 세워야 한다.
   */
  public UserResponse signup(SignupRequest request) {
    return TenantContext.runScopedGet(
        MembershipRepository.DEFAULT_TENANT_ID, () -> signupTransaction.execute(request));
  }

  /**
   * 공개 가입이 열려 있는가 = 사용자가 한 명도 없는가. 로그인 화면이 가입 링크를 숨길지 정한다. 잠금 없이 읽는다 — 표시용 힌트일 뿐이고 최종 판정은 {@link
   * SignupTransaction} 이 잠금 안에서 한다.
   */
  @Transactional(readOnly = true)
  public boolean isSignupOpen() {
    return !userRepository.existsAnyUser();
  }

  /**
   * 로그인.
   *
   * <p>실패 예외(잠금·자격 증명 불일치·비활성)에서는 롤백하지 않는다(WD-290) — 실패 감사 행을 이 트랜잭션에 쓰고 401/403 과 함께 커밋해야 하기 때문이다.
   * 실패 경로에서 이 트랜잭션이 쓰는 것은 감사 행과 실패 카운터뿐이다. 실패 카운터도 이 트랜잭션에 쓴다 — 커넥션 1개(WD-552).
   *
   * <p>계정 잠금보다 먼저 IP 기준 제한(WD-578)을 본다 — 한 IP 에서 서로 다른 계정이 15분 안에 10개 이상 실패했으면 맞는 비밀번호여도 429 로
   * 거절한다. 없는 계정·비밀번호 불일치는 계정 카운터와 함께 IP 실패도 기록한다. 비활성 계정(비밀번호는 맞음)은 비밀번호를 맞힌 것이라 스프레이 실패로 세지 않는다.
   */
  @Transactional(
      noRollbackFor = {
        AccountLockedException.class,
        InvalidCredentialsException.class,
        UserDeactivatedException.class
      })
  public TokenResponse login(LoginRequest request) {
    // 대소문자·앞뒤 공백만 다른 입력도 같은 계정으로 본다(WD-269). 카운터 키도 이 값이라 대소문자별로 갈리지 않는다.
    String username = userRepository.resolveLoginUsername(request.username());
    // 요청 IP 는 신뢰 프록시 기준(WD-402) — 신뢰 프록시가 아닌 곳의 X-Forwarded-For 는 읽지 않고, 해석 안 되는 값은 공용 키로 센다.
    // 요청 컨텍스트가 없으면 null 이고 IP 제한을 적용하지 않는다.
    String clientIp = clientIpExtractor.currentRequestIp();
    Optional<String> blockedIpKey = loginIpThrottleService.blockedKey(clientIp);
    if (blockedIpKey.isPresent()) {
      // 스프레이는 요청마다 아이디를 바꾸므로 IP 키로 합친다 — 아이디로 합치면 감사 행이 요청 수만큼 쌓인다.
      auditRejectionOncePerMinute(blockedIpKey.get(), username, LoginFailure.IP_LIMITED);
      throw new AccountLockedException(LoginIpThrottleService.BLOCKED_MESSAGE);
    }
    if (loginAttemptService.isBlocked(username)) {
      // 잠긴 동안에는 실패 카운터가 더 오르지 않으므로 아이디(감사 행과 같이 50자로 자른 값)로 합친다.
      auditRejectionOncePerMinute(
          AuditLogService.truncate(username, AuditLogService.USERNAME_MAX),
          username,
          LoginFailure.LOCKED);
      // 과도한 로그인 실패로 계정이 잠긴 경우 한국어 메시지 반환
      throw new AccountLockedException("로그인 시도 횟수를 초과하였습니다. 잠시 후 다시 시도해 주세요.");
    }

    UserResponse user = userRepository.findByUsername(username).orElse(null);
    if (user == null) {
      recordLoginFailure(username, clientIp);
      auditLoginFailure(username, null, LoginFailure.UNKNOWN_USER);
      // 사용자 존재 여부 노출 방지를 위해 동일한 메시지 반환
      throw new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    String storedPassword = userRepository.findPasswordByUsername(username).orElse(null);
    if (storedPassword == null || !passwordEncoder.matches(request.password(), storedPassword)) {
      boolean justLocked = recordLoginFailure(username, clientIp);
      auditLoginFailure(username, user, LoginFailure.BAD_PASSWORD);
      if (justLocked) {
        auditAccountLocked(user);
      }
      // 사용자 존재 여부 노출 방지를 위해 동일한 메시지 반환
      throw new InvalidCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    if (!user.isActive()) {
      auditLoginFailure(username, user, LoginFailure.DEACTIVATED);
      // 비밀번호가 맞을 때만 알린다 — 계정 존재 여부는 드러나지 않는다. 사용자가 할 일(관리자 문의)까지 적는다(WD-278).
      throw new UserDeactivatedException();
    }

    loginAttemptService.loginSucceeded(username);

    // 소속이 정확히 하나면 즉시 자동 선택해 테넌트 스코프 토큰을 바로 발급한다(선택 화면 생략).
    // 0개 또는 2개 이상이면 테넌트 미선택 토큰을 주고 클라이언트가 select-tenant 를 호출한다.
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(user.id());
    Long activeTenantId = MembershipResponse.soleActiveTenant(memberships).orElse(null);

    // 로그인은 새 리프레시 토큰 패밀리를 시작한다.
    TokenResponse tokenResponse =
        issueTokenPair(
            user.id(),
            user.username(),
            activeTenantId,
            UUID.randomUUID(),
            memberships,
            user.mustChangePassword(),
            // 정지된 워크스페이스도 사유와 함께 알린다 — 빼면 선택 화면이 「초대 없음」과 구분하지 못한다(WD-281).
            membershipRepository.findUnavailableByUser(user.id()));

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
    // 활성 테넌트가 확정되지 않은 로그인(멤버십 0개 또는 2개 이상)은 설계상 NULL 테넌트로 남는다 — 어느
    // 테넌트의 사건인지 알 수 없기 때문이다. 로그인 실패 감사 행도 같은 규칙을 따른다(applySoleTenant).
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

  // 재사용 탐지가 쓴 패밀리 폐기는 401 과 함께 커밋돼야 한다(WD-268, RefreshTokenRotation 호출자 계약).
  @Transactional(noRollbackFor = RefreshTokenReusedException.class)
  public TokenResponse refresh(String rawRefreshToken) {
    if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
      // 만료되었거나 유효하지 않은 리프레시 토큰 — 한국어 메시지 반환
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }
    // 평면 대칭. PlatformAuthService.refresh 가 테넌트 토큰을 거부하는 것과 짝을 이룬다.
    // refresh_token 테이블은 전역이라 두 평면이 같은 회전 기계를 공유하는데, 이 검사가 없으면
    // 운영자 리프레시 토큰을 테넌트 갱신 경로에 넣어 같은 패밀리를 테넌트 토큰으로 갈아탈 수 있다.
    // (권한 상승은 아니지만 평면 표식이 조용히 사라지므로, 표식은 한쪽에서만 지켜서는 안 된다.)
    if (jwtTokenProvider.isPlatformToken(rawRefreshToken)) {
      throw new InvalidTokenException("유효하지 않거나 만료된 토큰입니다.");
    }

    // 회전·재사용 탐지는 두 평면이 공유하는 정책이다(RefreshTokenRotation 주석 참고).
    UUID familyId = refreshTokenRotation.revokeAndGetFamily(hashToken(rawRefreshToken));

    Long userId = jwtTokenProvider.getUserIdFromToken(rawRefreshToken);
    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("토큰에 해당하는 사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));

    if (!user.isActive()) {
      // 비활성화된 계정으로 토큰 갱신 시도 — 로그인과 같은 문구·코드(WD-278)
      throw new UserDeactivatedException();
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
    // pwc 는 방금 읽은 DB 값 — 비밀번호를 바꾼 뒤 refresh 하면 표식이 꺼진 토큰이 나온다(WD-2).
    return issueTokenPair(
        user.id(),
        user.username(),
        activeTenantId,
        familyId,
        memberships,
        user.mustChangePassword(),
        // 세션 중 정지돼 강등된 경우에도 선택 화면이 사유를 보여줄 수 있게 함께 싣는다(WD-281).
        membershipRepository.findUnavailableByUser(user.id()));
  }

  @Transactional
  public void logout(Long userId) {
    // 웹 평면 토큰만 폐기한다(WD-314) — 사용자 단위 전체 폐기는 같은 사람의 운영자 콘솔 세션까지 끊었다.
    refreshTokenRepository.revokeAllWebByUserId(userId);
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
   * 활성 테넌트를 선택한다. 테넌트 전환도 같은 경로를 쓴다 — 액세스/리프레시 토큰을 모두 재발급해 이후 요청이 새 테넌트 컨텍스트로 흐르게 한다.
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
      throw new UserDeactivatedException();
    }

    // 테넌트 전환 시 새 리프레시 토큰 패밀리를 시작한다(이전 패밀리와 섞이지 않게).
    // pwc 를 여기서도 DB 값으로 싣는다 — 빠지면 "워크스페이스 선택" 이 비밀번호 변경 게이트 우회 구멍이
    // 된다(WD-2).
    return issueTokenPair(
        userId,
        user.username(),
        tenantId,
        UUID.randomUUID(),
        List.of(),
        user.mustChangePassword(),
        // memberships 와 같이 이 경로는 목록을 싣지 않는다 — 웹은 응답 직후 하드 리로드해 refresh 로 다시 받는다(WD-281).
        List.of());
  }

  /**
   * 비밀번호 변경 직후 호출자의 세션을 새 refresh 패밀리로 다시 세운다(WD-2 리뷰 지적 2).
   *
   * <p>{@code UserService.changePassword} 가 이 사용자의 refresh 토큰을 전부 폐기하므로, 호출자(웹)가 쥔 옛 쿠키로는 refresh 가
   * 401 이다. 컨트롤러가 여기서 받은 refresh 토큰을 쿠키로 내려 주면 웹의 {@code completePasswordChange} → {@code
   * /auth/refresh} 가 그대로 동작한다.
   *
   * <p>테넌트는 호출 시점 access token 의 클레임이다. refresh 와 같은 규칙으로 멤버십을 재검증해 정지됐으면 미선택(null)으로 강등한다. pwc 는
   * 방금 커밋된 DB 값(꺼짐)을 읽는다.
   */
  @Transactional
  public TokenResponse startSessionAfterPasswordChange(Long userId, Long claimedTenantId) {
    UserResponse user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new InvalidTokenException("사용자를 찾을 수 없습니다. 다시 로그인해 주세요."));
    Long tenantId =
        (claimedTenantId != null
                && membershipRepository.hasActiveMembership(userId, claimedTenantId))
            ? claimedTenantId
            : null;
    return issueTokenPair(
        userId,
        user.username(),
        tenantId,
        UUID.randomUUID(),
        membershipRepository.findActiveByUser(userId),
        user.mustChangePassword(),
        membershipRepository.findUnavailableByUser(userId));
  }

  /**
   * 액세스/리프레시 토큰을 발급하고 리프레시 토큰을 저장한 뒤 응답을 만든다.
   *
   * <p>login/refresh/selectTenant 는 tenantId 산출 방식, familyId 를 새로 시작할지 재사용할지, memberships 를 실제 목록으로
   * 채울지 비워둘지만 다르고 그 이후 처리(토큰 발급·저장·응답 조립)는 동일하므로 이 부분만 공유한다. unavailableWorkspaces(WD-281)도
   * memberships 와 같은 규칙으로 채우거나 비운다.
   */
  private TokenResponse issueTokenPair(
      Long userId,
      String username,
      Long tenantId,
      UUID familyId,
      List<MembershipResponse> memberships,
      boolean mustChangePassword,
      List<UnavailableWorkspace> unavailableWorkspaces) {
    String accessToken =
        jwtTokenProvider.generateAccessToken(userId, username, tenantId, mustChangePassword);
    String refreshToken = jwtTokenProvider.generateRefreshToken(userId, tenantId);

    storeRefreshToken(userId, refreshToken, familyId);

    return new TokenResponse(
        accessToken,
        refreshToken,
        "Bearer",
        jwtProperties.accessExpiration() / 1000,
        tenantId,
        memberships,
        mustChangePassword,
        unavailableWorkspaces);
  }

  private void storeRefreshToken(Long userId, String refreshToken, UUID familyId) {
    String tokenHash = hashToken(refreshToken);
    LocalDateTime expiresAt =
        LocalDateTime.now().plusSeconds(jwtProperties.refreshExpiration() / 1000);
    refreshTokenRepository.save(userId, tokenHash, expiresAt, familyId);
  }

  /** 로그인 실패 사유 — 감사 행의 설명과 {@code metadata.reason} 에 쓴다(WD-290). */
  private enum LoginFailure {
    UNKNOWN_USER("없는 아이디"),
    BAD_PASSWORD("비밀번호 불일치"),
    LOCKED("잠긴 계정"),
    IP_LIMITED("IP 로그인 제한"),
    DEACTIVATED("비활성 계정");

    private final String label;

    LoginFailure(String label) {
      this.label = label;
    }
  }

  /**
   * 로그인 실패를 계정 카운터와 IP 기준 제한(WD-578) 양쪽에 기록한다. 없는 아이디·비밀번호 불일치가 같은 쌍을 부르므로 한 곳에 둔다({@code
   * PlatformAuthService#failed} 와 같은 역할).
   *
   * @return 이번 실패로 계정이 막 잠겼으면 true — 계정 잠금 감사에 쓴다
   */
  private boolean recordLoginFailure(String username, String clientIp) {
    boolean justLocked = loginAttemptService.loginFailed(username);
    loginIpThrottleService.loginFailed(clientIp, username);
    return justLocked;
  }

  /**
   * 잠금·IP 제한으로 거부한 로그인 기록을 1분 창에서 합치는 캐시. 거부 상태에서는 실패 카운터가 더 오르지 않아, 합치지 않으면 요청마다 감사 행이 끝없이
   * 쌓인다({@code SecurityAuditRecorder} 의 거부 감사와 같은 1분 창). 키는 사유 이름을 앞에 붙여 잠금(아이디)·IP 제한(IP 키)이 서로
   * 겹치지 않게 한다. 키 길이가 묶여(아이디 50자·IP 키) 메모리도 묶인다. 인스턴스 메모리 기준이라 다중 인스턴스면 인스턴스 수만큼 남을 수 있다.
   */
  private final Cache<String, Boolean> recentRejections =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100_000).build();

  /**
   * 거부한 로그인을 같은 사유·합침 키당 1분에 1건으로 남긴다. 잠금은 아이디, IP 제한(WD-578)은 IP 키로 합친다 — IP 제한이면 그 1분 동안 첫 거부의
   * 아이디만 남는다. 없는 아이디도 거부될 수 있으므로(카운터가 쌓이면 잠기고, 스프레이 대상은 대개 없는 계정) 사용자가 없을 수 있다.
   *
   * @param dedupKey 합침 키(잠금: 잘린 아이디, IP 제한: IP 키)
   * @param username 감사 행에 남길 정규화된 아이디
   * @param reason 거부 사유
   */
  private void auditRejectionOncePerMinute(String dedupKey, String username, LoginFailure reason) {
    String key = reason.name() + ":" + dedupKey;
    if (recentRejections.asMap().putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    UserResponse user = userRepository.findByUsername(username).orElse(null);
    if (!auditLoginFailure(username, user, reason)) {
      recentRejections.invalidate(key); // 못 남겼으면 다음 거부가 다시 시도하게 한다
    }
  }

  /**
   * 로그인 실패 감사 행(LOGIN + FAILURE). 없는 아이디면 입력한 아이디를 그대로 남긴다(길이는 감사 서비스가 자른다) — 무차별 대입 대상 추적용. 테넌트도
   * 여기서 심는다 — 바로 뒤에 같은 트랜잭션에서 쓰는 잠금 행도 이 테넌트를 따른다.
   */
  private boolean auditLoginFailure(String username, UserResponse user, LoginFailure reason) {
    applySoleTenant(user);
    return writeAccountAudit(
        user,
        user != null ? user.username() : username,
        "LOGIN",
        "로그인 실패 — " + reason.label,
        "FAILURE",
        Map.of("reason", reason.name()));
  }

  /**
   * 로그인 연속 실패로 계정이 잠기는 순간을 남긴다. 계정이 있을 때만 — 없는 아이디의 잠금은 그 아이디의 실패 행들로 충분하다. 바로 앞의 {@link
   * #auditLoginFailure} 가 심은 테넌트를 따른다.
   */
  private void auditAccountLocked(UserResponse user) {
    writeAccountAudit(
        user,
        user.username(),
        "ACCOUNT_LOCKED",
        "로그인 " + LoginAttemptService.MAX_ATTEMPTS + "회 연속 실패로 계정 잠금",
        "SUCCESS",
        null);
  }

  /**
   * 로그인 트랜잭션에 계정 사건 감사 행의 테넌트를 심는다. 로그인 성공 행과 같은 규칙이다 — 활성 멤버십이 정확히 하나면 그 테넌트, 0개·2개 이상이거나 없는 아이디면
   * 심지 않아 NULL 테넌트(운영자 콘솔 감사 로그에서 보인다).
   */
  private void applySoleTenant(UserResponse user) {
    if (user != null) {
      MembershipResponse.soleActiveTenant(membershipRepository.findActiveByUser(user.id()))
          .ifPresent(currentTransactionTenant::apply);
    }
  }

  /**
   * 계정 사건 감사 행을 로그인 트랜잭션 안의 저장점(NESTED)에서 쓴다. 감사는 판정이 아니라 기록이라 실패해도 로그인 응답(401/403)을 바꾸지 않는다 — PG 는
   * 실패한 문장이 트랜잭션 전체를 깨뜨리므로 try/catch 만으로는 부족하고, 저장점까지 되돌려야 이어서 커밋할 수 있다.
   *
   * @return 남겼으면 true
   */
  private boolean writeAccountAudit(
      UserResponse user,
      String username,
      String actionType,
      String description,
      String result,
      Object metadata) {
    String[] requestInfo = extractRequestInfo();
    TransactionTemplate savepoint = new TransactionTemplate(transactionManager);
    savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    try {
      savepoint.executeWithoutResult(
          s ->
              auditLogService.log(
                  user != null ? user.id() : null,
                  username,
                  actionType,
                  "auth",
                  null,
                  description,
                  requestInfo[0],
                  requestInfo[1],
                  result,
                  null,
                  metadata));
      return true;
    } catch (RuntimeException e) {
      log.warn("{} 감사 기록 실패 — 로그인 응답은 그대로 둔다: {}", actionType, username, e);
      return false;
    }
  }

  /** HTTP 요청 컨텍스트에서 IP와 User-Agent를 추출한다. 감사 로그에 사용 */
  private String[] extractRequestInfo() {
    // IP 는 ClientIpExtractor 가 현재 요청에서 고른다(WD-578, 로그인 IP 제한과 같은 경로). User-Agent 만 여기서 읽는다.
    var attrs = RequestContextHolder.getRequestAttributes();
    String userAgent =
        attrs instanceof ServletRequestAttributes sra
            ? sra.getRequest().getHeader("User-Agent")
            : null;
    return new String[] {clientIpExtractor.currentRequestIp(), userAgent};
  }

  /**
   * 저장용 토큰 해시.
   *
   * <p>구현은 {@link RefreshTokenHasher} 로 옮겼다 — 운영자 평면(P7-a)이 같은 {@code refresh_token} 테이블을 쓰므로 두 평면이
   * 반드시 같은 해시를 만들어야 한다.
   */
  private String hashToken(String token) {
    return RefreshTokenHasher.hash(token);
  }
}
