package com.smartfirehub.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청(프로필·강제 변경 공용).
 *
 * <p>newPassword 규칙·한국어 문구는 PasswordRules 공용(WD-270).
 */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank
        @Size(
            min = PasswordRules.MIN_LENGTH,
            max = PasswordRules.MAX_LENGTH,
            message = PasswordRules.NEW_PASSWORD_SIZE_MESSAGE)
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.PATTERN_MESSAGE)
        String newPassword) {}
