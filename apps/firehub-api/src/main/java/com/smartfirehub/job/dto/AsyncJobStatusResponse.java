package com.smartfirehub.job.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record AsyncJobStatusResponse(
    String jobId,
    String jobType,
    String stage,
    int progress,
    String message,
    Map<String, Object> metadata,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Long userId) {}
