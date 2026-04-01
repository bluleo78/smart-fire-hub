package com.smartfirehub.global.security;

import com.smartfirehub.permission.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PermissionChecker {

  private final PermissionRepository permissionRepository;

  public boolean hasPermission(Long userId, String permissionCode) {
    return permissionRepository.findPermissionCodesByUserId(userId).contains(permissionCode);
  }
}
