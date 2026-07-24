package com.smartfirehub.graphreview.dto;

import java.util.List;

/** LLM "같다" 판정 근접쌍 등록 요청(ai-agent → api). datasetId/sourceChunkIds는 원문 근거 표시용(선택). */
public record PendingSynonymRequest(
    String entityType, String nameA, String nameB, Double similarity, String rationale,
    Long datasetId, List<Long> sourceChunkIds) {}
