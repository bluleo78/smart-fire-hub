package com.smartfirehub.audit.dto;

import java.time.LocalDateTime;

public record AuditLogResponse(
    Long id,
    Long userId,
    String username,
    String actionType,
    String resource,
    String resourceId,
    String description,
    LocalDateTime actionTime,
    String ipAddress,
    String userAgent,
    String result,
    String errorMessage,
    Object metadata
) {}
