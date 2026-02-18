package com.smartfirehub.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "메시지는 필수입니다")
        String message,
        String sessionId
) {
}
