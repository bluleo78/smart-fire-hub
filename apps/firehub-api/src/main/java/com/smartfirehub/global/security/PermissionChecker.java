package com.smartfirehub.global.security;

import com.smartfirehub.permission.repository.PermissionRepository;
import org.springframework.stereotype.Service;

@Service
public class PermissionChecker {

  private final PermissionRepository permissionRepository;

  public PermissionChecker(PermissionRepository permissionRepository) {
    this.permissionRepository = permissionRepository;
  }

  public boolean hasPermission(Long userId, String permissionCode) {
    return permissionRepository.findPermissionCodesByUserId(userId).contains(permissionCode);
  }
}
