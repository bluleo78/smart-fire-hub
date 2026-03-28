package com.smartfirehub.proactive.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ProactiveJobExecutionResponse(
    Long id,
    Long jobId,
    String status,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String errorMessage,
    Map<String, Object> result,
    List<String> deliveredChannels,
    LocalDateTime createdAt) {}
