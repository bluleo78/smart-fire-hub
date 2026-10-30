package com.smartfirehub.auth.dto;

import com.smartfirehub.user.dto.PasswordRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Email String username,
    @Email String email,
    // 규칙·한국어 문구는 PasswordRules 공용 (WD-270)
    @NotBlank
        @Size(
            min = PasswordRules.MIN_LENGTH,
            max = PasswordRules.MAX_LENGTH,
            message = PasswordRules.SIZE_MESSAGE)
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.PATTERN_MESSAGE)
        String password,
    // 이름 상한은 DB 컬럼(user.name VARCHAR(50))과 같다 — 없으면 51자부터 DB 오류로 거부된다 (WD-125)
    @NotBlank @Size(max = 50, message = "이름은 50자 이하여야 합니다") String name) {}
