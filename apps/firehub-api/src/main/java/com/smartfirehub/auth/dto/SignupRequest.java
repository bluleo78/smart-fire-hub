package com.smartfirehub.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Email String username,
    @Email String email,
    @NotBlank
        @Size(min = 8, max = 128)
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "비밀번호는 대문자, 소문자, 숫자를 각각 1자 이상 포함해야 합니다")
        String password,
    // 이름 상한은 DB 컬럼(user.name VARCHAR(50))과 같다 — 없으면 51자부터 DB 오류로 거부된다 (WD-125)
    @NotBlank @Size(max = 50, message = "이름은 50자 이하여야 합니다") String name) {}
