package com.smartfirehub.platform.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.auth.repository.RefreshTokenRepository;
import com.smartfirehub.platform.dto.PlatformAccountResponse;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
import com.smartfirehub.platform.repository.PlatformUserRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 평면 전역 계정 관리(#784) — {@code user.is_active} 를 바꾼다.
 *
 * <p>WD-2 이후 테넌트 화면의 활성 스위치는 "그 워크스페이스 멤버십 정지" 라 전역 계정을 잠글 수단이 없었다. 이
 * 서비스가 그 수단이다. 효과:
 *
 * <ul>
 *   <li>로그인·refresh·워크스페이스 선택: 이미 {@code isActive} 를 검사한다(AuthService·PlatformAuthService).
 *   <li>refresh 세션: 비활성화 시 전부 폐기한다 — 재활성화해도 옛 세션은 살아나지 않고 다시 로그인해야 한다.
 *   <li>권한: 테넌트 권한 조회가 user 활성 조인을 하므로(PermissionRepository) 권한 필요 API 는 즉시 403. 권한
 *       표시가 없는 API 는 access token 만료(최대 30분)까지 열린다.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlatformAccountService {

  private final PlatformUserRepository platformUserRepository;
  private final PlatformRoleRepository platformRoleRepository;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final AuditLogService auditLogService;

  /** 계정 검색 — 길이 하한·상한·결과 상한은 Owner 검색과 같은 정책을 쓴다(PlatformUserService 상수). */
  @Transactional(readOnly = true)
  public List<PlatformAccountResponse> search(String q) {
    return platformUserRepository.searchAccounts(
        PlatformUserService.normalizeQuery(q), PlatformUserService.MAX_RESULTS);
  }

  /**
   * 전역 계정 비활성화.
   *
   * <p>잠금 방지: 자기 자신(400) → 운영자 계정(409). 운영자 계정을 통째로 막으면 자기 잠금과 "운영자 둘이 서로를
   * 동시에 비활성화해 0 명" 경합이 함께 사라진다(잠금이 필요 없다). 운영자 해임은 이 기능 범위 밖이다.
   * 이미 비활성이면 무동작(감사 로그 없음) — 같은 요청 재전송이 감사 로그를 부풀리지 않게 한다.
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

  /**
   * 감사 로그. 운영자 요청에는 테넌트 GUC 가 없으므로 audit_log.tenant_id 는 DEFAULT 로 NULL 이 된다(로그인 이벤트와
   * 같은 형태, V97/V99). 어느 테넌트 감사 화면에도 보이지 않는 플랫폼 이벤트로 남는다.
   */
  private void audit(long operatorId, String action, UserResponse target, String description) {
    String operatorName =
        userRepository.findById(operatorId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        operatorId,
        operatorName,
        action,
        "user",
        String.valueOf(target.id()),
        description,
        null,
        null,
        "SUCCESS",
        null,
        Map.of("plane", "platform", "targetUsername", target.username()));
  }
}
