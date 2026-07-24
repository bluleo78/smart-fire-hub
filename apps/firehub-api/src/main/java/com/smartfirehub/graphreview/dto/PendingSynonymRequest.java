package com.smartfirehub.graphreview.dto;

/** LLM "같다" 판정 근접쌍 등록 요청(ai-agent → api). */
public record PendingSynonymRequest(
    String entityType, String nameA, String nameB, Double similarity, String rationale) {}
