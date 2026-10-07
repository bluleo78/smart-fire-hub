package com.smartfirehub.role.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.role.dto.RoleDetailResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoleService {

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final DatasetAccessGrantRepository grantRepository;

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

  @Transactional
  public RoleResponse createRole(String name, String description) {
    if (roleRepository.existsByName(name)) {
      throw new IllegalArgumentException("이미 존재하는 역할 이름입니다: " + name);
    }
    return roleRepository.save(name, description);
  }

  @Transactional
  public void updateRole(Long id, String name, String description) {
    RoleResponse role =
        roleRepository
            .findById(id)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + id));

    if (role.isSystem() && !role.name().equals(name)) {
      throw new SystemRoleModificationException(
          "Cannot change name of system role: " + role.name());
    }

    roleRepository.update(id, name, description);
  }

  @Transactional
  public void deleteRole(Long id) {
    RoleResponse role =
        roleRepository
            .findById(id)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + id));

    if (role.isSystem()) {
      throw new SystemRoleModificationException("Cannot delete system role: " + role.name());
    }

    // 판단 사항 14: 이 역할이 어떤 허용 목록 필요 데이터셋의 유일한 항목이면, 삭제(ON DELETE CASCADE)가 그 데이터셋을 아무도 못 보게 만든다.
    var orphaned = grantRepository.datasetsWhereRoleIsSoleGrantOnAllowlistLevel(id);
    if (!orphaned.isEmpty()) {
      throw new CodedApiException(
          HttpStatus.CONFLICT,
          "ROLE_SOLE_ALLOWLIST_ENTRY",
          "이 역할이 유일한 허용 항목인 데이터셋이 " + orphaned.size() + "개 있어 삭제할 수 없습니다. 먼저 허용 목록에 다른 항목을 추가하세요.");
    }

    roleRepository.deleteById(id);
  }

  @Transactional
  public void setRolePermissions(Long roleId, List<Long> permissionIds) {
    RoleResponse role =
        roleRepository
            .findById(roleId)
            .orElseThrow(() -> new RoleNotFoundException("Role not found: " + roleId));

    if (role.isSystem()) {
      throw new SystemRoleModificationException(
          "Cannot modify permissions of system role: " + role.name());
    }

    roleRepository.setPermissions(roleId, permissionIds);
  }
}
