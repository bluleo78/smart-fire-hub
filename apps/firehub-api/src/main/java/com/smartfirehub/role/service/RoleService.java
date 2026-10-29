package com.smartfirehub.role.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.role.dto.RoleDetailResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 역할 CRUD·역할 권한 설정 서비스.
 *
 * <p>변경 작업(생성·수정·삭제·권한 설정)은 감사 로그를 남긴다(WD-117) — 누가 어떤 역할의 권한을 바꿨는지 관리자가 추적할 수 있어야 하기 때문이다. 멤버 관리
 * 감사(UserService.audit)와 같은 테넌트 감사 경로(AuditLogService, audit_log.tenant_id = 현재 GUC)를 쓰고, 변경과 같은
 * 트랜잭션에 기록해 롤백되면 감사도 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final DatasetAccessGrantRepository grantRepository;
  private final UserRepository userRepository;
  private final AuditLogService auditLogService;
  private final RoleGrantGuard roleGrantGuard;

  @Transactional(readOnly = true)
  public List<RoleResponse> getAllRoles() {
    return roleRepository.findAll();
  }

  @Transactional(readOnly = true)
  public RoleDetailResponse getRoleById(Long id) {
    RoleResponse role =
        roleRepository
            .findById(id)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + id));
    List<PermissionResponse> permissions = permissionRepository.findByRoleId(id);
    return new RoleDetailResponse(
        role.id(), role.name(), role.description(), role.isSystem(), permissions);
  }

  /** 역할 생성. 성공하면 ROLE_CREATE 감사(행위자 = callerId)를 남긴다. */
  @Transactional
  public RoleResponse createRole(String name, String description, Long callerId) {
    assertNameAvailable(name);
    RoleResponse created = roleRepository.save(name, description);
    audit(
        callerId,
        "ROLE_CREATE",
        created.id(),
        "역할 생성: " + created.name(),
        roleSnapshot(created.name(), created.description()));
    return created;
  }

  /** 같은 이름의 역할이 이미 있으면 생성·수정 모두 같은 문구의 400 으로 거부한다(WD-279). */
  private void assertNameAvailable(String name) {
    if (roleRepository.existsByName(name)) {
      throw new IllegalArgumentException("이미 존재하는 역할 이름입니다: " + name);
    }
  }

  /**
   * 역할 이름·설명 수정. 성공하면 ROLE_UPDATE 감사에 변경 전/후 값을 남긴다.
   *
   * <p>이름·설명이 둘 다 그대로면 감사를 남기지 않는다 — 멤버십 정지/재활성의 "이미 원하는 상태면 기록 없음" 규칙과 같다. 화면은 저장 버튼을 누를 때마다 같은
   * 값으로 PUT 할 수 있어, 기록하면 실제 변경과 구분되지 않는 잡음 행이 쌓인다.
   */
  @Transactional
  public void updateRole(Long id, String name, String description, Long callerId) {
    RoleResponse role =
        roleRepository
            .findById(id)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + id));

    if (role.isSystem() && !role.name().equals(name)) {
      throw new SystemRoleModificationException(
          "Cannot change name of system role: " + role.name());
    }
    // 이름을 바꿀 때만 생성과 같은 중복 검사를 한다(WD-279) — 그대로 두고 설명만 바꾸는 저장을 자기 이름과의 중복으로 막지 않기 위해서다.
    // DB 유니크 제약도 있지만 그 409 는 「이미 같은 값이 있어…」라 어느 칸이 문제인지 알 수 없어, 생성과 같은 문구로 먼저 안내한다.
    if (!role.name().equals(name)) {
      assertNameAvailable(name);
    }

    roleRepository.update(id, name, description);
    if (Objects.equals(role.name(), name) && Objects.equals(role.description(), description)) {
      return; // 실제 변경 없음 — 감사 생략
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("before", roleSnapshot(role.name(), role.description()));
    metadata.put("after", roleSnapshot(name, description));
    audit(callerId, "ROLE_UPDATE", id, "역할 수정: " + name, metadata);
  }

  /**
   * 역할 삭제. 성공하면 ROLE_DELETE 감사를 남긴다 — 행이 사라지므로 이름·설명과 함께 지워질 권한 코드·이 역할을 가졌던 사용자 id 를 metadata 에
   * 보존한다. 거부 경로는 기록하지 않는다.
   *
   * <p>권한 범위(WD-141): 삭제는 그 역할을 가진 모든 사람에게서 한꺼번에 회수하는 것과 같으므로, ADMIN 이 아닌 호출자는 자기 범위 안의 역할(권한 ⊆ 자기
   * 권한, 등급 ≤ 자기 등급, 허용 목록 데이터셋은 자기가 열람 가능)만 지울 수 있다({@link RoleGrantGuard#assertCanDeleteRole}). 판정
   * 순서는 역할 없음(404) → 시스템 역할(400) → 범위(403) → 유일 허용 항목(409) 이다 — 앞의 두 계약은 그대로 두고, 범위 판정은 어떤 쓰기·잠금보다
   * 먼저 한다.
   */
  @Transactional
  public void deleteRole(Long id, Long callerId) {
    RoleResponse role =
        roleRepository
            .findById(id)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + id));

    if (role.isSystem()) {
      throw new SystemRoleModificationException("Cannot delete system role: " + role.name());
    }

    // WD-141: role:delete 만 위임받은 사용자가 자기보다 높은 역할을 지워 보유자 전원에게서 빼앗지 못하게 — 회수와 같은 범위 검사를 쓰기 전에 한다.
    roleGrantGuard.assertCanDeleteRole(callerId, id);

    // 판단 사항 14: 이 역할이 어떤 허용 목록 필요 데이터셋의 유일한 항목이면, 삭제(ON DELETE CASCADE)가 그 데이터셋을 아무도 못 보게 만든다.
    // 후속 F4: 검사 전에 이 역할이 걸린 데이터셋 행을 잠가 같은 데이터셋의 허용 항목 제거(removeGrant — 같은 행 FOR UPDATE)와 직렬화한다.
    // 잠금 없이 검사하면 동시 제거의 미커밋 삭제를 못 보고 통과해 데이터셋이 고아가 된다. 검사는 잠금 뒤 별도 문장이어야 상대의 커밋이 보인다.
    grantRepository.lockDatasetsGrantedToRole(id);
    var orphaned = grantRepository.datasetsWhereRoleIsSoleGrantOnAllowlistLevel(id);
    if (!orphaned.isEmpty()) {
      throw new CodedApiException(
          HttpStatus.CONFLICT,
          "ROLE_SOLE_ALLOWLIST_ENTRY",
          "이 역할이 유일한 허용 항목인 데이터셋이 " + orphaned.size() + "개 있어 삭제할 수 없습니다. 먼저 허용 목록에 다른 항목을 추가하세요.");
    }

    // 삭제는 role_permission·user_role 을 CASCADE 로 함께 지운다 — 누가 어떤 권한을 잃었는지 재구성할 수 있게 지우기 전에 권한과 사용자를
    // 담는다.
    Map<String, Object> metadata = roleSnapshot(role.name(), role.description());
    metadata.put("permissions", List.copyOf(permissionCodes(id)));
    metadata.put("affectedUserIds", roleRepository.findUserIdsByRoleId(id));
    roleRepository.deleteById(id);
    audit(callerId, "ROLE_DELETE", id, "역할 삭제: " + role.name(), metadata);
  }

  /**
   * 역할 권한 전체 교체. 실제로 권한이 바뀌었으면 ROLE_PERMISSIONS_UPDATE 감사에 추가·제거된 권한 코드를 남긴다.
   *
   * <p>차이는 요청 id 가 아니라 교체 전/후 실제 저장 상태(권한 코드)로 계산한다 — 요청에 중복 id 가 섞여도, 같은 목록을 다시 보내도 정확하다. 추가·제거가
   * 모두 없으면 감사를 남기지 않는다(멤버십 정지/재활성과 같은 무변경 규칙).
   *
   * <p>권한 범위(WD-116): 새로 <b>추가</b>되는 권한은 호출자가 가진 것이어야 한다({@link RoleGrantGuard}) — {@code
   * role:write} 만 가진 사용자가 자기 역할에 아무 권한이나 붙여 권한을 올리던 결함의 수정이다. 제거는 제한하지 않는다. 판정은 쓰기 <b>전에</b> 한다 —
   * 쓰고 나서 보면 호출자가 자기 역할에 방금 붙인 권한이 "이미 가진 권한" 으로 보여 검사가 무력해진다.
   */
  @Transactional
  public void setRolePermissions(Long roleId, List<Long> permissionIds, Long callerId) {
    // 동시 교체가 같은 "변경 전"을 읽어 감사 차이가 틀어지지 않게 역할 단위로 직렬화한다.
    roleRepository.lockById(roleId);
    RoleResponse role =
        roleRepository
            .findById(roleId)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + roleId));

    if (role.isSystem()) {
      throw new SystemRoleModificationException(
          "Cannot modify permissions of system role: " + role.name());
    }

    TreeSet<String> before = permissionCodes(roleId);
    Set<String> adding = new HashSet<>(permissionRepository.findCodesByIds(permissionIds));
    adding.removeAll(before);
    roleGrantGuard.assertCanAddPermissions(callerId, adding);
    roleRepository.setPermissions(roleId, permissionIds);
    TreeSet<String> after = permissionCodes(roleId);

    TreeSet<String> added = new TreeSet<>(after);
    added.removeAll(before);
    TreeSet<String> removed = new TreeSet<>(before);
    removed.removeAll(after);
    if (added.isEmpty() && removed.isEmpty()) {
      return; // 실제 변경 없음 — 감사 생략
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("role", role.name());
    metadata.put("added", List.copyOf(added));
    metadata.put("removed", List.copyOf(removed));
    audit(callerId, "ROLE_PERMISSIONS_UPDATE", roleId, "역할 권한 변경: " + role.name(), metadata);
  }

  /** 역할에 현재 붙은 권한 코드(정렬) — 감사 metadata 를 결정적으로 만들기 위해 TreeSet 으로 모은다. */
  private TreeSet<String> permissionCodes(Long roleId) {
    TreeSet<String> codes = new TreeSet<>();
    permissionRepository.findByRoleId(roleId).forEach(p -> codes.add(p.code()));
    return codes;
  }

  /** 역할 이름·설명 스냅샷. 설명은 null 일 수 있어 Map.of(null 금지) 대신 LinkedHashMap 을 쓴다. */
  private static Map<String, Object> roleSnapshot(String name, String description) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("name", name);
    snapshot.put("description", description);
    return snapshot;
  }

  /**
   * 역할 관리 감사 로그(resource = role, resourceId = 역할 id). 행위자 username 은 감사 테이블 NOT NULL 이라 조회해서 넣는다 —
   * UserService.audit 와 같은 방식이다. audit_log.tenant_id 는 GUC 기본값이라 호출 트랜잭션의 현재 테넌트로 기록된다.
   */
  private void audit(
      Long callerId, String action, Long roleId, String description, Object metadata) {
    String callerName =
        userRepository.findById(callerId).map(UserResponse::username).orElse("unknown");
    auditLogService.log(
        callerId,
        callerName,
        action,
        "role",
        String.valueOf(roleId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}
