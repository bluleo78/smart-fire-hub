package com.smartfirehub.pipeline.service.executor;

import java.util.List;
import java.util.Map;

public record ApiCallConfig(
        String url,
        String method,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String body,
        String responseFormat,
        String dataPath,
        List<FieldMapping> fieldMappings,
        String sourceTimezone,
        PaginationConfig pagination,
        RetryConfig retry,
        Integer timeoutMs,
        Integer maxDurationMs,
        Integer maxResponseSizeMb,
        Map<String, String> inlineAuth
) {
    public record FieldMapping(
            String sourceField,
            String targetColumn,
            String dataType,
            String dateFormat,
            String numberFormat,
            String sourceTimezone
    ) {}

    public record PaginationConfig(
            String type,
            Integer pageSize,
            String offsetParam,
            String limitParam,
            String totalPath
    ) {}

    public record RetryConfig(
            Integer maxRetries,
            Integer initialBackoffMs,
            Integer maxBackoffMs
    ) {}
}
