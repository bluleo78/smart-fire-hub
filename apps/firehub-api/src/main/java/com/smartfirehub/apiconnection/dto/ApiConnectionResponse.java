package com.smartfirehub.apiconnection.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ApiConnectionResponse(
        Long id,
        String name,
        String description,
        String authType,
        Map<String, String> maskedAuthConfig,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
