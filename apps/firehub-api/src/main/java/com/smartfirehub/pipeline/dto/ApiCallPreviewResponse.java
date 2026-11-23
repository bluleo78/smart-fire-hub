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
    String resolvedUrl,
    /** 열 전체 수 — 원본 모드는 쓸 수 있는 키 전체 수(columns 는 그중 앞 100개), 매핑 모드는 columns 수, 실패는 0 (WD-947 리뷰) */
    int totalColumns,
    /**
     * 원본 모드에서 비밀 값(BASIC 비밀번호 부분·가린 ****)이 들었거나 이름이 너무 길어(500자 초과) 뺀 서로 다른 키 수, 그 밖은 0 (WD-947 리뷰)
     */
    int hiddenColumns) {}
