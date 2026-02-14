package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(@NotBlank @Size(max = 50) String name, String description) {}
