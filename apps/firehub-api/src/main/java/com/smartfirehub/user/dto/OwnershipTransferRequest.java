package com.smartfirehub.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 소유권 이전 요청(WD-1132).
 *
 * @param confirmName 확인용으로 다시 입력한 워크스페이스 이름 — 앞뒤 공백만 무시하고 정확히 같아야 한다(서버가 최종 판정)
 */
public record OwnershipTransferRequest(
    @NotBlank(message = "워크스페이스 이름을 입력하세요") String confirmName) {}
