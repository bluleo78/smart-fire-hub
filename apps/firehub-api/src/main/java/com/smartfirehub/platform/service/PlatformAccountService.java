package com.smartfirehub.platform.service;

import com.smartfirehub.auth.exception.EmailAlreadyExistsException;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.platform.dto.CreatePlatformAccountRequest;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import com.smartfirehub.platform.repository.PlatformUserRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import com.smartfirehub.user.service.TemporaryAccountCreator;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 평면 전역 계정 관리(#784) — {@code user.is_active} 를 바꾼다.
 *
 * <p>WD-2 이후 테넌트 화면의 활성 스위치는 "그 워크스페이스 멤버십 정지" 라 전역 계정을 잠글 수단이 없었다. 이 서비스가 그 수단이다. 효과:
 *
 * <ul>
 *   <li>로그인·refresh·워크스페이스 선택: 이미 {@code isActive} 를 검사한다(AuthService·PlatformAuthService).
 *   <li>refresh 세션: 비활성화 시 전부 폐기한다 — 재활성화해도 옛 세션은 살아나지 않고 다시 로그인해야 한다.
 *   <li>권한: 테넌트 권한 조회가 user 활성 조인을 하므로(PermissionRepository) 권한 필요 API 는 즉시 403. 권한 표시가 없는 API 는
 *       access token 만료(최대 30분)까지 열린다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlatformAccountService {

  private final PlatformUserRepository platformUserRepository;
  private final PlatformRoleRepository platformRoleRepository;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PlatformAuditRecorder auditRecorder;
  private final TemporaryAccountCreator temporaryAccountCreator;

  /** 페이지 크기 상한 — 감사 로그 목록(PlatformAuditLogService)과 같은 값·같은 400 규칙. */
  static final int MAX_PAGE_SIZE = 100;

  /**
   * 계정 목록(WD-47) — 검색어가 없으면 전체, 있으면 필터. 페이지네이션.
   *
   * <p>검색 하한을 Owner 검색(2자)과 갈라 1자로 둔 이유: 여기는 상한 절단이 없는 페이지 목록이라 짧은 검색어가 "앞 20건만 보이는" 문제를 만들지 않는다.
   * 상한(100자)은 같다 — 방어적 상한.
   *
   * @throws IllegalArgumentException page &lt; 0, size 범위 밖, 검색어 100자 초과(400)
   */
  @Transactional(readOnly = true)
  public PageResponse<PlatformAccountResponse> list(String q, int page, int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page 는 0 이상이어야 합니다");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("size 는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다");
    }
    String trimmed = q == null ? "" : q.trim();
    if (trimmed.length() > PlatformUserService.MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException(
          "검색어는 " + PlatformUserService.MAX_QUERY_LENGTH + "자 이하여야 합니다");
    }
    return platformUserRepository.findAccounts(trimmed.isEmpty() ? null : trimmed, page, size);
  }

  /**
   * 전역 계정 비활성화.
   *
   * <p>잠금 방지: 자기 자신(400) → 운영자 계정(409). 운영자 계정을 통째로 막으면 자기 잠금과 "운영자 둘이 서로를 동시에 비활성화해 0 명" 경합이 함께
   * 사라진다(잠금이 필요 없다). 운영자 해임은 이 기능 범위 밖이다. 이미 비활성이면 무동작(감사 로그 없음) — 같은 요청 재전송이 감사 로그를 부풀리지 않게 한다.
   */
  @Transactional
  public void deactivate(long targetUserId, long operatorId) {
    if (targetUserId == operatorId) {
      throw new IllegalArgumentException("자기 자신의 계정은 비활성화할 수 없습니다");
    }
    UserResponse target = requireUser(targetUserId);
    if (platformRoleRepository.hasAnyPlatformRole(targetUserId)) {
      throw new IllegalStateException("운영자 계정은 비활성화할 수 없습니다");
    }
    if (!target.isActive()) {
      return;
    }
    userRepository.setActive(targetUserId, false);
    // 같은 트랜잭션: "비활성인데 refresh 세션은 살아 있는" 중간 상태가 커밋되지 않게 한다.
    refreshTokenRepository.revokeAllByUserId(targetUserId);
    audit(operatorId, "ACCOUNT_DEACTIVATE", target, "전역 계정 비활성화(모든 워크스페이스 로그인 차단, refresh 세션 폐기)");
  }

  /**
   * 소속 없는 새 계정 생성(WD-46) — 공개 가입이 닫힌 뒤(WD-2) 계정이 없는 사람을 테넌트 Owner 로 세울 수단.
   *
   * <p>규칙은 멤버 추가와 같다({@link TemporaryAccountCreator}): username = 소문자 이메일, 임시 비밀번호 해시, 첫 로그인 변경 강제.
   * 멤버십은 만들지 않는다. 이미 있으면(아이디 또는 이메일, 대소문자 무시) 409 {@code ACCOUNT_ALREADY_EXISTS} — 기존 계정은 건드리지 않는다
   * (비밀번호를 덮어쓰면 남의 계정을 가로채는 경로가 된다). 감사는 같은 트랜잭션에 남긴다.
   */
  @Transactional
  public PlatformAccountResponse create(CreatePlatformAccountRequest request, long operatorId) {
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    // 아이디 검사를 따로 하는 이유: 공용 생성기는 이메일만 본다(멤버 추가는 아이디 일치를 "기존 계정 추가" 로 쓰므로 거기서 미리 걸렀다).
    if (userRepository.findByUsernameIgnoreCase(email).isPresent()) {
      throw accountAlreadyExists();
    }
    UserResponse created;
    try {
      created = temporaryAccountCreator.create(email, request.name(), request.temporaryPassword());
    } catch (EmailAlreadyExistsException | DuplicateKeyException e) {
      // 이메일 중복(사전 검사) 과 동시 요청의 유니크 제약 위반을 같은 409 로 번역한다 — 영어 무결성 오류를 노출하지 않는다.
      // 런타임 예외라 @Transactional 이 롤백한다(부분 생성 없음).
      throw accountAlreadyExists();
    }
    audit(operatorId, "ACCOUNT_CREATE", created, "소속 없는 계정 생성(임시 비밀번호, 첫 로그인 시 변경 강제)");
    // 방금 만든 계정은 활성·비운영자·소속 0 이다(플랫폼 롤도 멤버십도 이 경로에서 주지 않는다).
    return new PlatformAccountResponse(
        created.id(),
        created.username(),
        created.email(),
        created.name(),
        true,
        false,
        0,
        created.createdAt());
  }

  private static CodedApiException accountAlreadyExists() {
    return new CodedApiException(
        HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", "이미 같은 아이디 또는 이메일의 계정이 있습니다");
  }

  /** 전역 계정 재활성화. 폐기된 refresh 세션은 되살리지 않는다 — 사용자는 다시 로그인한다. */
  @Transactional
  public void reactivate(long targetUserId, long operatorId) {
    UserResponse target = requireUser(targetUserId);
    if (target.isActive()) {
      return;
    }
    userRepository.setActive(targetUserId, true);
    audit(operatorId, "ACCOUNT_REACTIVATE", target, "전역 계정 재활성화");
  }

  private UserResponse requireUser(long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
  }

  /** 감사 로그 — 운영자 평면 공통 기록기에 위임한다(tenant NULL, WD-12 에서 테넌트 생명주기와 형태 통일). */
  private void audit(long operatorId, String action, UserResponse target, String description) {
    auditRecorder.record(
        operatorId,
        action,
        "user",
        String.valueOf(target.id()),
        description,
        Map.of("targetUsername", target.username()));
  }
}
