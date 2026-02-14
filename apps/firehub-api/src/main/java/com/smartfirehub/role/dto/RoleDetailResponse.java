package com.smartfirehub.role.dto;

import com.smartfirehub.permission.dto.PermissionResponse;

import java.util.List;

public record RoleDetailResponse(Long id, String name, String description, boolean isSystem, List<PermissionResponse> permissions) {}
