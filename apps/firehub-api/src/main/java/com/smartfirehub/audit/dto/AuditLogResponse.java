package com.smartfirehub.audit.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.smartfirehub.audit.time.StorageZoneDateTimeSerializer;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

public record AuditLogResponse(
    Long id,
    Long userId,
    String username,
    String actionType,
    String resource,
    String resourceId,
    String description,
    /** 저장 벽시계 — JSON 에는 저장 TZ 오프셋이 붙는다(WD-11, StorageZoneDateTimeSerializer). */
    @JsonSerialize(using = StorageZoneDateTimeSerializer.class) LocalDateTime actionTime,
    String ipAddress,
    String userAgent,
    String result,
    String errorMessage,
    @JsonRawValue String metadata) {}
