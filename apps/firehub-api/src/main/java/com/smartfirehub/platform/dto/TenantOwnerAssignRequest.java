package com.smartfirehub.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 운영자 콘솔의 테넌트 소유자 지정 요청(WD-1132).
 *
 * @param userId 새 소유자가 될 이 테넌트의 활성 멤버 계정 id
 * @param confirmName 실수 방지용 확인 입력 — 테넌트 이름과 같아야 한다(앞뒤 공백만 무시, 대소문자 구분)
 */
public record TenantOwnerAssignRequest(
    @NotNull(message = "새 소유자를 선택하세요") Long userId,
    @NotBlank(message = "확인하려면 테넌트 이름을 입력하세요") String confirmName) {}
