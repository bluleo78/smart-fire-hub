package com.smartfirehub.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프로필 수정 요청 DTO.
 * name에 @Size(max=100)을 추가하여 프론트엔드 우회 시에도 서버에서 400 반환 (#26).
 */
public record UpdateProfileRequest(
    @NotBlank @Size(max = 100, message = "이름은 100자 이하여야 합니다") String name,
    @Email String email) {}
