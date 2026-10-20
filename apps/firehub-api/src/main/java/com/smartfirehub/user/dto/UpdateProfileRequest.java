package com.smartfirehub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프로필 수정 요청 DTO. name 의 상한은 DB 컬럼(user.name VARCHAR(50))과 같은 50자 — 프론트엔드 우회 시에도 DB 오류가 아니라 검증 400
 * 으로 안내한다 (#26, WD-125: 예전 100자 상한은 51~100자를 통과시킨 뒤 DB 에서 거부됐다).
 */
public record UpdateProfileRequest(
    @NotBlank @Size(max = 50, message = "이름은 50자 이하여야 합니다") String name, @Email String email) {}
