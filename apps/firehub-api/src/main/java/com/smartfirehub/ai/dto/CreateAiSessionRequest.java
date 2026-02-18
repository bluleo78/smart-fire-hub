package com.smartfirehub.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAiSessionRequest(
        @NotBlank(message = "세션 ID는 필수입니다")
        String sessionId,
        String contextType,
        Long contextResourceId,
        String title
) {
}
