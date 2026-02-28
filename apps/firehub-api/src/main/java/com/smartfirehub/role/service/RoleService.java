package com.smartfirehub.role.service;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.role.dto.RoleDetailResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.role.repository.RoleRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleService {

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;

  public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository) {
    this.roleRepository = roleRepository;
    this.permissionRepository = permissionRepository;
  }

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
      throw new IllegalArgumentException("Role name already exists: " + name);
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
