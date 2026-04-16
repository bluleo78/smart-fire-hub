package com.smartfirehub.pipeline.dto;

import java.util.List;
import java.util.Map;

public record ApiCallPreviewResponse(
    boolean success,
    String rawJson,
    List<Map<String, Object>> rows,
    List<String> columns,
    int totalExtractedRows,
    String errorMessage,
    /** 서버가 실제로 호출한 최종 URL (쿼리 파라미터 포함) */
    String resolvedUrl) {}
