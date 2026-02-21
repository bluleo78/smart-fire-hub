package com.smartfirehub.dataset.dto;

import java.util.List;
import java.util.Map;

public record SqlQueryResponse(
    String queryType,
    List<String> columns,
    List<Map<String, Object>> rows,
    int affectedRows,
    long executionTimeMs,
    String error
) {}
