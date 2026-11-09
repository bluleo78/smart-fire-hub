package com.smartfirehub.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 사용자 활성/비활성 변경 요청.
 *
 * <p>WD-596: 원시 boolean 이면 본문 {} 나 필드 오타({"actvie":true})가 false 로 묶여 사용자가 조용히 비활성화됐다. 래퍼 Boolean
 * + @NotNull 로 바꿔 값이 없으면 400 으로 거절한다.
 */
public record SetActiveRequest(@NotNull(message = "활성 여부(active)는 필수입니다") Boolean active) {}
