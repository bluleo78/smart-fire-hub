package com.smartfirehub.dashboard.dto;

import java.time.LocalDateTime;

public record RecentExecutionResponse(
    Long id, String pipelineName, String status, LocalDateTime createdAt) {}
