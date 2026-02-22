package com.smartfirehub.pipeline.dto;

import java.util.List;
import java.util.Map;

public record ApiCallPreviewResponse(
    boolean success,
    String rawJson,
    List<Map<String, Object>> rows,
    List<String> columns,
    int totalExtractedRows,
    String errorMessage
) {}
