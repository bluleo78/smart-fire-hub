package com.smartfirehub.synonymreview.dto;

/** LLM "같다" 판정 근접쌍 등록 요청(ai-agent → firehub-api). */
public record PendingSynonymRequest(
    String entityType, String nameA, String nameB, Double similarity, String rationale) {}
