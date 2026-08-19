package com.smartfirehub.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 테넌트 생성 요청.
 *
 * @param slug URL·스키마 파생에 쓰이는 식별자. 소문자·숫자·하이픈만 허용한다 — 대문자나 공백이
 *     들어오면 이후 서브도메인 UX(설계서 §8)에서 대소문자 충돌이 난다
 * @param ownerUserId 초기 Owner. 존재하는 사용자여야 한다 — Owner 없는 테넌트는 아무도 들어갈 수
 *     없어 운영자 SQL 로만 고칠 수 있다
 */
public record CreateTenantRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$") String slug,
    @NotBlank @Size(max = 255) String name,
    @NotNull Long ownerUserId) {}
