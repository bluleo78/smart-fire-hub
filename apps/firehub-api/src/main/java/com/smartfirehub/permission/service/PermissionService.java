package com.smartfirehub.permission.service;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PermissionService {

  private final PermissionRepository permissionRepository;

  public PermissionService(PermissionRepository permissionRepository) {
    this.permissionRepository = permissionRepository;
  }

  @Transactional(readOnly = true)
  public List<PermissionResponse> getAllPermissions() {
    return permissionRepository.findAll();
  }

  @Transactional(readOnly = true)
  public List<PermissionResponse> getPermissionsByCategory(String category) {
    return permissionRepository.findByCategory(category);
  }

  @Transactional(readOnly = true)
  public Set<String> getUserPermissions(Long userId) {
    return permissionRepository.findPermissionCodesByUserId(userId);
  }
}
