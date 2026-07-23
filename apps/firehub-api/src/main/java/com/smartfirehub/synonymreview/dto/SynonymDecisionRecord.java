package com.smartfirehub.synonymreview.dto;

import java.time.LocalDateTime;

/** synonym_decision 1행 — repository/service 내부 전달용(컨트롤러 응답 DTO는 SynonymDecisionResponse). */
public record SynonymDecisionRecord(
    Long id,
    String entityType,
    String nameA,
    String nameB,
    String status,
    Double similarity,
    String rationale,
    Long decidedBy,
    LocalDateTime decidedAt,
    LocalDateTime createdAt) {}
