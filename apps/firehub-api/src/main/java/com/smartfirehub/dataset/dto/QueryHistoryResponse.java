package com.smartfirehub.dataset.dto;

import java.time.LocalDateTime;

public record QueryHistoryResponse(
    Long id,
    String sql,
    String queryType,
    int affectedRows,
    long executionTimeMs,
    boolean success,
    String error,
    LocalDateTime executedAt
) {}
