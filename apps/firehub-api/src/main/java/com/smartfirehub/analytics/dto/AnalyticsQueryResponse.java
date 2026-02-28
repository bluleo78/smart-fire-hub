package com.smartfirehub.analytics.dto;

import java.util.List;
import java.util.Map;

public record AnalyticsQueryResponse(
    String queryType,
    List<String> columns,
    List<Map<String, Object>> rows,
    int affectedRows,
    long executionTimeMs,
    int totalRows,
    boolean truncated,
    String error) {}
