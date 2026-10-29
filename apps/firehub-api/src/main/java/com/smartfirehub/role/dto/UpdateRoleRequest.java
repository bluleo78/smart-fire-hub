package com.smartfirehub.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 역할 이름·설명 수정 요청.
 *
 * <p>이름 규칙은 {@link CreateRoleRequest} 와 같은 {@link RoleNameRules} 다(WD-279) — 수정에서만 규칙이 빠지면 「ADMIN
 * 」(끝 공백)처럼 시스템 역할과 똑같이 보이는 이름이나 한글·공백 이름이 저장돼, 목록·역할 할당에서 진짜 ADMIN 과 구분할 수 없게 된다.
 */
public record UpdateRoleRequest(
    @NotBlank
        @Size(max = RoleNameRules.MAX_LENGTH, message = RoleNameRules.SIZE_MESSAGE)
        @Pattern(regexp = RoleNameRules.PATTERN, message = RoleNameRules.PATTERN_MESSAGE)
        String name,
    String description) {}
