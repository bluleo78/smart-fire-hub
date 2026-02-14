package com.smartfirehub.permission.service;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    public List<PermissionResponse> getAllPermissions() {
        return permissionRepository.findAll();
    }

    public List<PermissionResponse> getPermissionsByCategory(String category) {
        return permissionRepository.findByCategory(category);
    }

    public Set<String> getUserPermissions(Long userId) {
        return permissionRepository.findPermissionCodesByUserId(userId);
    }
}
