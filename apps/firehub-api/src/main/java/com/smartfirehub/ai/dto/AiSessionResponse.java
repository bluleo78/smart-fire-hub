package com.smartfirehub.ai.dto;

import java.time.LocalDateTime;

public record AiSessionResponse(
    Long id,
    Long userId,
    String sessionId,
    String contextType,
    Long contextResourceId,
    String title,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
