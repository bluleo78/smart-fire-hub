package com.smartfirehub.role.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.RoleLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
 *   <li>역할 권한 변경 — 새로 <b>추가</b>되는 권한은 호출자가 가진 것이어야 한다. 그리고 권한이 실제로 바뀌면(추가든 제거든) 대상 역할 자체가 삭제와 같은 범위
 *       안이어야 한다(WD-285). 권한 제거는 그 역할 보유자 모두에게서 그 권한을 회수하는 것이라, 범위를 보지 않으면 {@code role:write} 만 위임받은
 *       사용자가 삭제·등급 낮추기로는 막힌 상위 역할을 권한 전부 빼기로 무력화할 수 있었다.
 *   <li>역할 삭제(WD-141) — 삭제는 "그 역할을 가진 모든 사람에게서 한꺼번에 회수" 이므로 회수와 같은 범위 검사를 받는다. {@code role:delete}
 *       만 위임받은 사용자가 자기보다 높은 권한·등급(또는 자기가 못 보는 데이터셋의 허용 목록)이 붙은 역할을 지워 모든 보유자에게서 빼앗을 수 있었다.
 *   <li>멤버 정지·재활성·제거(WD-277) — 정지·제거는 "그 멤버가 이 워크스페이스에서 가진 역할 전부의 회수", 재활성은 그 역할들을 되살리는 것과 같으므로 대상의
 *       역할(기본 USER 제외)에 같은 범위 검사를 한다. {@code user:write} 만 위임받은 사용자가 역할 변경으로는 막힌 ADMIN 무력화를 정지·제거로
 *       할 수 있었다.
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
  static final String DELETE_SCOPE_MESSAGE = "자신에게 없는 권한·등급을 가진 역할은 삭제할 수 없습니다";
  static final String DELETE_ALLOWLIST_MESSAGE = "자신이 열람할 수 없는 데이터셋의 허용 목록이 붙은 역할은 삭제할 수 없습니다";
  static final String PERMISSIONS_EDIT_SCOPE_MESSAGE = "자신에게 없는 권한·등급을 가진 역할의 권한은 바꿀 수 없습니다";
  static final String PERMISSIONS_EDIT_ALLOWLIST_MESSAGE =
      "자신이 열람할 수 없는 데이터셋의 허용 목록이 붙은 역할의 권한은 바꿀 수 없습니다";
  static final String EDIT_ADMIN_MESSAGE = "시스템 ADMIN 역할은 ADMIN 만 바꿀 수 있습니다";
  static final String EDIT_SCOPE_MESSAGE = "자신에게 없는 권한·등급을 가진 역할은 이름·설명을 바꿀 수 없습니다";
  static final String EDIT_ALLOWLIST_MESSAGE = "자신이 열람할 수 없는 데이터셋의 허용 목록이 붙은 역할은 이름·설명을 바꿀 수 없습니다";
  static final String CLEARANCE_SCOPE_MESSAGE = "자신에게 없는 권한·등급을 가진 역할은 열람 등급을 바꿀 수 없습니다";
  static final String CLEARANCE_ALLOWLIST_MESSAGE =
      "자신이 열람할 수 없는 데이터셋의 허용 목록이 붙은 역할은 열람 등급을 바꿀 수 없습니다";
  static final String MEMBER_ADMIN_MESSAGE = "시스템 ADMIN 역할을 가진 멤버는 ADMIN 만 정지·재활성하거나 제거할 수 있습니다";
  static final String MEMBER_SCOPE_MESSAGE = "자신에게 없는 권한·등급의 역할을 가진 멤버는 정지·재활성하거나 제거할 수 없습니다";
  static final String MEMBER_ALLOWLIST_MESSAGE =
      "자신이 열람할 수 없는 데이터셋의 허용 목록이 붙은 역할을 가진 멤버는 정지·재활성하거나 제거할 수 없습니다";

  /**
   * 범위 위반 시 내보낼 오류 코드·메시지 묶음. 판정 규칙은 부여·회수·삭제·멤버 정지/제거가 같고 "무엇을 하려다 막혔는지" 만 다르므로, 규칙은 한 곳({@link
   * #assertWithinScope})에 두고 문구만 갈아 끼운다.
   */
  private record ScopeErrors(
      String adminCode,
      String adminMessage,
      String scopeCode,
      String scopeMessage,
      String allowlistCode,
      String allowlistMessage) {}

  private static final ScopeErrors GRANT_ERRORS =
      new ScopeErrors(
          "SYSTEM_ADMIN_ROLE_ADMIN_ONLY",
          ADMIN_ROLE_MESSAGE,
          "ROLE_GRANT_BEYOND_SCOPE",
          ROLE_SCOPE_MESSAGE,
          "ROLE_GRANT_ALLOWLIST_BEYOND_SCOPE",
          ALLOWLIST_SCOPE_MESSAGE);
  private static final ScopeErrors DELETE_ERRORS =
      new ScopeErrors(
          "SYSTEM_ADMIN_ROLE_ADMIN_ONLY",
          ADMIN_ROLE_MESSAGE,
          "ROLE_DELETE_BEYOND_SCOPE",
          DELETE_SCOPE_MESSAGE,
          "ROLE_DELETE_ALLOWLIST_BEYOND_SCOPE",
          DELETE_ALLOWLIST_MESSAGE);
  // 시스템 역할(ADMIN 포함)은 RoleService 가 권한 변경 전에 거부하므로 ADMIN 문구에는 닿지 않는다 — 삭제처럼 방어선으로만 부여·회수 문구를 쓴다.
  private static final ScopeErrors PERMISSIONS_EDIT_ERRORS =
      new ScopeErrors(
          "SYSTEM_ADMIN_ROLE_ADMIN_ONLY",
          ADMIN_ROLE_MESSAGE,
          "ROLE_PERMISSIONS_BEYOND_SCOPE",
          PERMISSIONS_EDIT_SCOPE_MESSAGE,
          "ROLE_PERMISSIONS_ALLOWLIST_BEYOND_SCOPE",
          PERMISSIONS_EDIT_ALLOWLIST_MESSAGE);
  private static final ScopeErrors EDIT_ERRORS =
      new ScopeErrors(
          "SYSTEM_ADMIN_ROLE_ADMIN_ONLY",
          EDIT_ADMIN_MESSAGE,
          "ROLE_EDIT_BEYOND_SCOPE",
          EDIT_SCOPE_MESSAGE,
          "ROLE_EDIT_ALLOWLIST_BEYOND_SCOPE",
          EDIT_ALLOWLIST_MESSAGE);
  // 시스템 ADMIN 역할의 등급은 RoleClearanceService 가 먼저 고정(400)으로 거부하므로 ADMIN 문구에는 닿지 않는다 — 방어선으로만 둔다.
  private static final ScopeErrors CLEARANCE_EDIT_ERRORS =
      new ScopeErrors(
          "SYSTEM_ADMIN_ROLE_ADMIN_ONLY",
          EDIT_ADMIN_MESSAGE,
          "ROLE_CLEARANCE_BEYOND_SCOPE",
          CLEARANCE_SCOPE_MESSAGE,
          "ROLE_CLEARANCE_ALLOWLIST_BEYOND_SCOPE",
          CLEARANCE_ALLOWLIST_MESSAGE);
  private static final ScopeErrors MEMBER_REMOVE_ERRORS =
      new ScopeErrors(
          "MEMBER_ADMIN_ADMIN_ONLY",
          MEMBER_ADMIN_MESSAGE,
          "MEMBER_REMOVE_BEYOND_SCOPE",
          MEMBER_SCOPE_MESSAGE,
          "MEMBER_REMOVE_ALLOWLIST_BEYOND_SCOPE",
          MEMBER_ALLOWLIST_MESSAGE);

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
    assertWithinScope(callerId, changedRoleIds, GRANT_ERRORS);
  }

  /**
   * 역할 삭제 범위 검사(WD-141). 삭제는 CASCADE 로 모든 보유자에게서 그 역할을 회수하므로 {@link #assertCanChangeRoles} 와 같은
   * 규칙(시스템 ADMIN → 열람 등급 → 권한 부분집합 → 허용 목록)을 그대로 적용하고, 거부 문구만 "삭제" 로 바꾼다. 시스템 역할 삭제 금지·역할 없음(404)은
   * 호출하는 {@link RoleService#deleteRole} 가 먼저 판정하므로 여기에 오는 역할은 존재하는 사용자 정의 역할이다.
   *
   * @throws CodedApiException 403 — 역할이 호출자 범위를 벗어날 때
   */
  public void assertCanDeleteRole(long callerId, long roleId) {
    assertWithinScope(callerId, Set.of(roleId), DELETE_ERRORS);
  }

  /**
   * 역할 권한 변경 범위 검사(WD-285). 권한 제거는 그 역할 보유자 모두에게서 권한을 회수하고, 추가는 그들에게 나눠 주는 것이므로, 권한이 실제로 바뀌는 역할은
   * 삭제와 같은 규칙(열람 등급 → 권한 부분집합 → 허용 목록)으로 호출자 범위 안이어야 한다. 반드시 권한을 바꾸기 <b>전</b>에 불러 변경 전 권한으로 판정한다 —
   * 바꾼 뒤에 보면 다 빼 버린 역할은 빈 권한이라 통과한다. 시스템 역할은 호출하는 {@link RoleService#setRolePermissions} 가 먼저 거부한다.
   *
   * @throws CodedApiException 403 — 역할이 호출자 범위를 벗어날 때
   */
  public void assertCanEditRolePermissions(long callerId, long roleId) {
    assertWithinScope(callerId, Set.of(roleId), PERMISSIONS_EDIT_ERRORS);
  }

  /**
   * 역할 이름·설명 수정 범위 검사(WD-284). 이름·설명은 관리자가 "이 역할을 누구에게 줘도 되는지" 판단하는 근거라, 범위 밖 역할의 이름을 「손님용 읽기 전용」처럼
   * 바꾸면 관리자를 속여 상위 권한을 퍼뜨리게 할 수 있다. 그래서 삭제와 같은 규칙(시스템 ADMIN → 열람 등급 → 권한 부분집합 → 허용 목록)을 적용하고 거부 문구만
   * "이름·설명" 으로 바꾼다. 시스템 역할·역할 없음은 호출하는 {@link RoleService#updateRole} 가 먼저 판정한다.
   *
   * @throws CodedApiException 403 — 역할이 호출자 범위를 벗어날 때
   */
  public void assertCanEditRole(long callerId, long roleId) {
    assertWithinScope(callerId, Set.of(roleId), EDIT_ERRORS);
  }

  /**
   * 역할 열람 등급 변경 범위 검사(WD-444). 등급을 낮추면 그 역할 보유자 모두가 데이터셋을 못 보게 되고 올리면 더 보게 되므로, 권한 변경(WD-285)과 같은
   * 효과를 등급 쪽으로 낼 수 있다. 그래서 삭제·권한 변경과 같은 규칙(시스템 ADMIN → 열람 등급 → 권한 부분집합 → 허용 목록)을 적용하고 거부 문구만 "열람
   * 등급" 으로 바꾼다. 예전에는 등급 숫자만 비교해, 자기에게 없는 권한이나 볼 수 없는 데이터셋의 허용 목록이 붙은 역할의 등급도 바꿀 수 있었다. 대상 등급이 호출자보다
   * 높은지는 호출하는 {@code RoleClearanceService} 가 따로 본다. 호출자 자격은 그 서비스가 받은 것을 그대로 써 등급 비교와 같은 자격으로 판정한다.
   *
   * @throws CodedApiException 403 — 역할이 호출자 범위를 벗어날 때
   */
  public void assertCanEditRoleClearance(Clearance caller, long roleId) {
    assertWithinScope(caller, Set.of(roleId), CLEARANCE_EDIT_ERRORS);
  }

  /**
   * 멤버 정지·재활성·제거 범위 검사(WD-277). 정지·제거는 대상이 이 워크스페이스에서 가진 역할 전부를 한꺼번에 무력화하고 재활성은 되살리므로 {@code
   * targetRoleIds}(대상의 현재 역할 — 기본 USER 는 호출자가 뺀다)에 부여·회수와 같은 규칙을 적용하고, 거부 문구만 바꾼다. 자기 자신·OWNER·마지막
   * 활성 ADMIN 잠금은 호출하는 {@code UserService} 가 먼저 판정한다.
   *
   * @throws CodedApiException 403 — 대상 역할 중 하나라도 호출자 범위를 벗어날 때
   */
  public void assertCanRemoveMember(long callerId, Collection<Long> targetRoleIds) {
    if (targetRoleIds.isEmpty()) {
      return; // 역할이 없는 멤버는 빼앗을 권한도 없다
    }
    assertWithinScope(callerId, targetRoleIds, MEMBER_REMOVE_ERRORS);
  }

  /**
   * 부여·회수·삭제·권한 변경·멤버 정지/제거 공통 범위 판정 본체 — 검사 순서와 fail-closed 규칙은 {@link #assertCanChangeRoles} 문서
   * 참고.
   */
  private void assertWithinScope(
      long callerId, Collection<Long> changedRoleIds, ScopeErrors errors) {
    assertWithinScope(clearanceResolver.resolve(callerId), changedRoleIds, errors);
  }

  /**
   * {@link #assertWithinScope(long, Collection, ScopeErrors)} 의 이미 해석한 자격 버전 — 위반이 있으면 종류 순서대로 하나를
   * 던진다.
   */
  private void assertWithinScope(
      Clearance caller, Collection<Long> changedRoleIds, ScopeErrors errors) {
    if (caller.tenantAdmin()) {
      return; // 시스템 ADMIN 은 제한하지 않는다(잠금 방지 규칙은 UserService 가 따로 본다)
    }
    Map<Long, Violation> violations =
        classify(caller, changedRoleIds, levelRepository.findRoleLevels());
    // ADMIN 은 별도 메시지로 먼저 거른다 — 다른 역할과 섞여 있어도 "왜 안 되는지" 가 정확히 보이게. 삭제 경로에서는 시스템 역할이 먼저 거부되므로
    // 여기에 닿지 않는다(삭제는 부여·회수 문구를 그대로 쓴다 — 방어선으로만 남는다).
    if (violations.containsValue(Violation.ADMIN)) {
      throw new CodedApiException(HttpStatus.FORBIDDEN, errors.adminCode(), errors.adminMessage());
    }
    if (violations.containsValue(Violation.SCOPE)) {
      throw new CodedApiException(HttpStatus.FORBIDDEN, errors.scopeCode(), errors.scopeMessage());
    }
    if (violations.containsValue(Violation.ALLOWLIST)) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, errors.allowlistCode(), errors.allowlistMessage());
    }
  }

  /** 역할 하나가 호출자 범위를 벗어난 까닭 — 거부 문구의 우선순위(ADMIN → 등급·권한 → 허용 목록) 순서로 둔다. */
  private enum Violation {
    ADMIN,
    SCOPE,
    ALLOWLIST
  }

  /**
   * 호출자(ADMIN 아님)가 부여할 수 없는 역할 목록(WD-291). 멤버 추가 화면이 범위 밖 역할을 미리 잠그고 까닭을 보이게 하려고, 실제 부여 때의 {@link
   * #assertCanChangeRoles} 와 같은 판정({@link #classify})을 현재 테넌트의 모든 역할에 돌린다 — 화면이 기준을 따로 흉내 내면 서버와
   * 어긋난다. 시스템 ADMIN 호출자는 막히는 역할이 없으므로 빈 목록이다.
   */
  public List<GrantBlock> findGrantBlockedRoles(long callerId) {
    Clearance caller = clearanceResolver.resolve(callerId);
    if (caller.tenantAdmin()) {
      return List.of();
    }
    List<RoleLevel> levels = levelRepository.findRoleLevels();
    List<Long> roleIds = levels.stream().map(RoleLevel::roleId).toList();
    List<GrantBlock> blocked = new ArrayList<>();
    classify(caller, roleIds, levels)
        .forEach(
            (roleId, violation) ->
                blocked.add(
                    switch (violation) {
                      case ADMIN ->
                          new GrantBlock(
                              roleId, GRANT_ERRORS.adminCode(), GRANT_ERRORS.adminMessage());
                      case SCOPE ->
                          new GrantBlock(
                              roleId, GRANT_ERRORS.scopeCode(), GRANT_ERRORS.scopeMessage());
                      case ALLOWLIST ->
                          new GrantBlock(
                              roleId,
                              GRANT_ERRORS.allowlistCode(),
                              GRANT_ERRORS.allowlistMessage());
                    }));
    blocked.sort(Comparator.comparingLong(GrantBlock::roleId));
    return blocked;
  }

  /** 부여할 수 없는 역할 하나와 그 까닭(실제 부여 시 받을 403 의 코드·문구와 같다). */
  public record GrantBlock(long roleId, String code, String message) {}

  /**
   * 판정 본체(호출자는 ADMIN 아님): 범위를 벗어난 역할마다 첫 번째 위반 종류를 돌려준다. 범위 안 역할은 맵에 없다. {@code roleLevels} 는
   * 현재 테넌트 역할 등급 — 목록 조회가 이미 읽은 것을 다시 읽지 않게 받는다. 검사 순서·fail-closed 규칙은
   * {@link #assertCanChangeRoles} 문서 참고.
   */
  private Map<Long, Violation> classify(
      Clearance caller, Collection<Long> roleIds, List<RoleLevel> roleLevels) {
    Map<Long, RoleLevel> roles =
        roleLevels.stream()
            .collect(Collectors.toMap(RoleLevel::roleId, Function.identity()));
    Map<Long, Integer> rankById = levelRepository.rankById();
    // 역할들의 권한을 한 번에 읽는다(역할마다 조회하면 N+1).
    Map<Long, Set<String>> codesByRole = permissionRepository.findCodesByRoleIds(roleIds);
    // WD-140: 역할에 붙은 허용 항목도 "나눠 주는 열람 자격" 이다. 허용 목록 필요 등급 데이터셋의 항목 중 호출자 자신이 열람 판정(VIEW — 등급 +
    // 허용 목록)에서 볼 수 없는 것이 하나라도 있으면 거부한다. 받는 사람은 다른 역할로 등급을 가질 수 있으므로 "허용 목록에 있음" 만으로는 부족하다 —
    // 호출자가 못 보는 데이터를 남이 보게 만들 수 있다. 역할 전체를 한 번에 조회한다(데이터셋·역할마다 묻지 않는다). 호출자는 여기서 ADMIN 이
    // 아니므로 admin_bypass 우회는 성립하지 않는다(조건식은 열람 판정과 같은 모양을 그대로 쓴다).
    Set<Long> allowlistBlocked =
        accessRepository.findRoleIdsWithAllowlistNotViewable(roleIds, caller);

    Map<Long, Violation> violations = new HashMap<>();
    for (Long roleId : roleIds) {
      RoleLevel role = roles.get(roleId);
      if (role != null && role.systemAdmin()) {
        violations.put(roleId, Violation.ADMIN);
        continue;
      }
      // 등급을 알 수 없으면(역할 없음·등급 누락) 최상위보다 높은 것으로 본다 — fail-closed. 없는·다른 테넌트 역할은 권한 맵에도 키가 없어
      // 빈 집합(= 권한 검사 통과)이 되므로, 거부는 반드시 이 등급 판정이 맡는다.
      int roleRank =
          role == null || role.levelId() == null
              ? Integer.MAX_VALUE
              : rankById.getOrDefault(role.levelId(), Integer.MAX_VALUE);
      if (roleRank > caller.rank()
          || !caller.permissions().containsAll(codesByRole.getOrDefault(roleId, Set.of()))) {
        violations.put(roleId, Violation.SCOPE);
      } else if (allowlistBlocked.contains(roleId)) {
        violations.put(roleId, Violation.ALLOWLIST);
      }
    }
    return violations;
  }

  /**
   * 역할 권한 추가 범위 검사. {@code addedCodes} 는 이번 교체로 새로 붙는 권한 코드만 넘긴다. 역할 자체가 범위 안인지는 {@link
   * #assertCanEditRolePermissions} 가 따로 본다(WD-285) — 범위 안 역할의 권한은 모두 호출자 것이므로 제거는 여기서 볼 것이 없다.
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
}
