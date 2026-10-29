package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(
    @NotBlank
        @Size(max = RoleNameRules.MAX_LENGTH, message = RoleNameRules.SIZE_MESSAGE)
        @Pattern(regexp = RoleNameRules.PATTERN, message = RoleNameRules.PATTERN_MESSAGE)
        String name,
    String description) {}
