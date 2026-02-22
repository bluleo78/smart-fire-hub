package com.smartfirehub.audit.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
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
    @JsonRawValue String metadata) {}
