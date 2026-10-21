package com.smartfirehub.role.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.RoleLevel;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 역할 부여·회수와 역할 권한 추가를 "호출자 자신의 범위 안" 으로 제한하는 가드(WD-116).
 *
 * <p><b>왜 필요한가:</b> {@code role:assign} 만 위임받은 사용자가 자기에게 시스템 ADMIN 을 붙이거나 다른 관리자의 ADMIN 을 떼고,
 * {@code role:write} 만 가진 사용자가 자기 역할에 아무 권한이나 붙여 권한을 올릴 수 있었다. 위임은 "자기가 가진 것까지만 나눠 줄 수 있다" 여야 상승
 * 경로가 닫힌다(사람 결정 A안).
 *
 * <p><b>규칙</b>(시스템 ADMIN 호출자는 전부 통과):
 *
 * <ul>
 *   <li>역할 부여·회수 — 대상 역할의 모든 권한을 호출자가 갖고 있고, 역할 열람 등급 ≤ 호출자 열람 등급이어야 한다. 시스템 ADMIN 역할은 ADMIN 만
 *       부여·회수할 수 있다.
 *   <li>역할 부여·회수(WD-140) — 대상 역할이 허용 목록 필요 등급 데이터셋의 허용 목록에 올라 있으면, 호출자가 그 데이터셋을 열람 시점에 이미 볼 수 있어야
 *       한다(등급 rank 충족 + 본인 항목 또는 자기 역할의 항목으로 허용 목록 충족 — 열람 판정과 같은 식). 역할에 붙은 허용 항목은 권한·등급과 별개의 "열람
 *       자격" 이라, 권한·등급만 보면 위임자가 자기도 못 보는 데이터셋의 허용 목록 역할을 자기·남에게 붙여 열람을 넓힐 수 있었다.
 *   <li>역할 권한 변경 — 새로 <b>추가</b>되는 권한은 호출자가 가진 것이어야 한다. 제거는 제한하지 않는다.
 * </ul>
 *
 * <p>서비스 계층(UserService·RoleService)에서 부르는 이유: REST 컨트롤러뿐 아니라 AI 에이전트(내부 토큰 + X-On-Behalf-Of)도 같은
 * 서비스를 타므로 한 곳에서 막아야 모든 경로가 덮인다. 호출자 자격은 요청 캐시({@link ClearanceResolver#current()}) 대신 명시 인자 {@code
 * callerId} 로 다시 계산한다 — 서비스는 행위자를 인자로 받고, 같은 요청 안에서 방금 바뀐 역할이 캐시에 가려지면 안 되기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class RoleGrantGuard {

  static final String ROLE_SCOPE_MESSAGE = "자신에게 없는 권한·등급을 가진 역할은 부여하거나 회수할 수 없습니다";
  static final String ADMIN_ROLE_MESSAGE = "시스템 ADMIN 역할은 ADMIN 만 부여하거나 회수할 수 있습니다";
  static final String PERMISSION_SCOPE_MESSAGE = "자신에게 없는 권한은 역할에 추가할 수 없습니다";
  static final String ALLOWLIST_SCOPE_MESSAGE = "자신이 열람할 수 없는 데이터셋의 허용 목록이 붙은 역할은 부여하거나 회수할 수 없습니다";

  private final ClearanceResolver clearanceResolver;
  private final SecurityLevelRepository levelRepository;
  private final PermissionRepository permissionRepository;
  private final DatasetAccessRepository accessRepository;

  /**
   * 역할 부여·회수 범위 검사. {@code changedRoleIds} 는 실제로 바뀌는 역할(추가 + 제거)만 넘긴다 — 대상이 이미 갖고 있고 그대로 유지되는 역할까지
   * 검사하면, 위임자가 무관한 상위 역할을 가진 사람의 다른 역할을 전혀 편집할 수 없게 된다.
   *
   * <p>검사 순서: 시스템 ADMIN(전용 메시지가 이겨야 한다) → 역할 존재 → 열람 등급 → 권한 부분집합 → 허용 목록(WD-140). 현재 테넌트에서 보이지 않는
   * 역할·등급이 없는 역할은 판정할 근거가 없으므로 fail-closed 로 거부한다.
   *
   * @throws CodedApiException 403 — 범위를 벗어난 역할이 하나라도 있을 때
   */
  public void assertCanChangeRoles(long callerId, Collection<Long> changedRoleIds) {
    if (changedRoleIds.isEmpty()) {
      return; // 바뀌는 역할이 없으면 판정할 것도 없다(무변경 저장)
    }
    Clearance caller = clearanceResolver.resolve(callerId);
    if (caller.tenantAdmin()) {
      return; // 시스템 ADMIN 은 제한하지 않는다(잠금 방지 규칙은 UserService 가 따로 본다)
    }
    Map<Long, RoleLevel> roles =
        levelRepository.findRoleLevels().stream()
            .collect(Collectors.toMap(RoleLevel::roleId, Function.identity()));

    // ADMIN 은 별도 메시지로 먼저 거른다 — 다른 역할과 섞여 있어도 "왜 안 되는지" 가 정확히 보이게.
    for (Long roleId : changedRoleIds) {
      RoleLevel role = roles.get(roleId);
      if (role != null && role.systemAdmin()) {
        throw new CodedApiException(
            HttpStatus.FORBIDDEN, "SYSTEM_ADMIN_ROLE_ADMIN_ONLY", ADMIN_ROLE_MESSAGE);
      }
    }

    Map<Long, Integer> rankById = levelRepository.rankById();
    // 바뀌는 역할들의 권한을 한 번에 읽는다(역할마다 조회하면 N+1).
    Map<Long, Set<String>> codesByRole = permissionRepository.findCodesByRoleIds(changedRoleIds);
    for (Long roleId : changedRoleIds) {
      RoleLevel role = roles.get(roleId);
      // 등급을 알 수 없으면(역할 없음·등급 누락) 최상위보다 높은 것으로 본다 — fail-closed. 없는·다른 테넌트 역할은 권한 맵에도 키가 없어
      // 빈 집합(= 권한 검사 통과)이 되므로, 거부는 반드시 이 등급 판정이 맡는다.
      int roleRank =
          role == null || role.levelId() == null
              ? Integer.MAX_VALUE
              : rankById.getOrDefault(role.levelId(), Integer.MAX_VALUE);
      if (roleRank > caller.rank()
          || !caller.permissions().containsAll(codesByRole.getOrDefault(roleId, Set.of()))) {
        throw roleScopeViolation();
      }
    }

    // WD-140: 역할에 붙은 허용 항목도 "나눠 주는 열람 자격" 이다. 허용 목록 필요 등급 데이터셋의 항목 중 호출자 자신이 열람 판정(VIEW — 등급 +
    // 허용 목록)에서 볼 수 없는 것이 하나라도 있으면 거부한다. 받는 사람은 다른 역할로 등급을 가질 수 있으므로 "허용 목록에 있음" 만으로는 부족하다 —
    // 호출자가 못 보는 데이터를 남이 보게 만들 수 있다. 바뀌는 역할 전체를 한 번에 조회한다(데이터셋·역할마다 묻지 않는다). 호출자는 여기서 ADMIN 이
    // 아니므로 admin_bypass 우회는 성립하지 않는다(조건식은 열람 판정과 같은 모양을 그대로 쓴다).
    if (!accessRepository.findRoleIdsWithAllowlistNotViewable(changedRoleIds, caller).isEmpty()) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, "ROLE_GRANT_ALLOWLIST_BEYOND_SCOPE", ALLOWLIST_SCOPE_MESSAGE);
    }
  }

  /**
   * 역할 권한 추가 범위 검사. {@code addedCodes} 는 이번 교체로 새로 붙는 권한 코드만 넘긴다(제거는 제한하지 않는다 — 권한을 줄이는 것은 상승이
   * 아니다).
   *
   * @throws CodedApiException 403 — 호출자에게 없는 권한이 하나라도 추가될 때
   */
  public void assertCanAddPermissions(long callerId, Collection<String> addedCodes) {
    if (addedCodes.isEmpty()) {
      return;
    }
    Clearance caller = clearanceResolver.resolve(callerId);
    if (caller.tenantAdmin()) {
      return;
    }
    if (!caller.permissions().containsAll(addedCodes)) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, "PERMISSION_GRANT_BEYOND_SCOPE", PERMISSION_SCOPE_MESSAGE);
    }
  }

  private static CodedApiException roleScopeViolation() {
    return new CodedApiException(
        HttpStatus.FORBIDDEN, "ROLE_GRANT_BEYOND_SCOPE", ROLE_SCOPE_MESSAGE);
  }
}
