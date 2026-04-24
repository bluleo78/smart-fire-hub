package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(
    @NotBlank
    @Size(max = 50, message = "역할 이름은 50자 이내여야 합니다")
    @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "역할 이름은 대문자, 숫자, 밑줄만 허용됩니다")
    String name,
    String description) {}
