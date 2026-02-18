package com.smartfirehub.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAiSessionRequest(
        @NotBlank(message = "제목은 필수입니다")
        String title
) {
}
