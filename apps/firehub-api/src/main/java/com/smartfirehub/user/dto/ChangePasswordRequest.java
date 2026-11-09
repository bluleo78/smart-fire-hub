package com.smartfirehub.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청(프로필·강제 변경 공용).
 *
 * <p>newPassword 규칙·한국어 문구는 PasswordRules 공용(WD-270).
 *
 * <p>currentPassword 에는 바이트 상한을 걸지 않는다(WD-605) — 상한 도입 전에 72바이트보다 길게 만든 비밀번호로도 변경할 수 있어야 한다. 비교는
 * BCrypt 가 예전처럼 앞 72바이트로 한다.
 */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank
        // 길이: 하한은 글자 수, 상한은 BCrypt 가 실제로 쓰는 UTF-8 72바이트 (WD-605)
        @Size(
            min = PasswordRules.MIN_LENGTH,
            message = PasswordRules.NEW_PASSWORD_MIN_LENGTH_MESSAGE)
        @PasswordMaxBytes(message = PasswordRules.NEW_PASSWORD_MAX_BYTES_MESSAGE)
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.PATTERN_MESSAGE)
        String newPassword) {}
