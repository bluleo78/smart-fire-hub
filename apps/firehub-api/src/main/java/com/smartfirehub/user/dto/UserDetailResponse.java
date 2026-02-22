package com.smartfirehub.user.dto;

import com.smartfirehub.role.dto.RoleResponse;
import java.time.LocalDateTime;
import java.util.List;

public record UserDetailResponse(
    Long id,
    String username,
    String email,
    String name,
    boolean isActive,
    LocalDateTime createdAt,
    List<RoleResponse> roles) {}
