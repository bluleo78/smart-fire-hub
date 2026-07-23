package com.smartfirehub.synonymreview.dto;

/** synonym_decision API 응답 — firehub-web 목록/상세용. */
public record SynonymDecisionResponse(
    Long id,
    String entityType,
    String nameA,
    String nameB,
    String status,
    Double similarity,
    String rationale,
    Long decidedBy,
    String decidedAt,
    String createdAt) {}
