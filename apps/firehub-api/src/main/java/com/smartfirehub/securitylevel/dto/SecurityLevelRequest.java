package com.smartfirehub.securitylevel.dto;

import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 등급 추가·수정 요청.
 *
 * @param seedAllowlistFromViewers allowlist_required 를 켤 때 "현재 열람 가능한 역할로 허용 목록 채우기"(목업 기본 체크).
 *     null 은 false 로 본다.
 */
public record SecurityLevelRequest(
    @NotBlank @Size(max = 50) String name,
    boolean allowlistRequired,
    boolean adminBypass,
    @NotNull ExportPolicy exportPolicy,
    @NotNull AiPolicy aiPolicy,
    @NotNull SharePolicy sharePolicy,
    boolean auditAccess,
    Boolean seedAllowlistFromViewers) {}
