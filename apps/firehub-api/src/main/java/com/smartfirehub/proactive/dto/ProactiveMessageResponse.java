package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.Map;

/** 프로액티브 메시지 응답 DTO — jobId는 execution JOIN으로 조회한 상위 잡 ID. */
public record ProactiveMessageResponse(
    Long id,
    Long userId,
    Long jobId,
    Long executionId,
    String title,
    Map<String, Object> content,
    String messageType,
    Boolean read,
    LocalDateTime readAt,
    String jobName,
    LocalDateTime createdAt) {}
