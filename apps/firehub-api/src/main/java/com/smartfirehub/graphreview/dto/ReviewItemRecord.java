package com.smartfirehub.graphreview.dto;

import java.time.LocalDateTime;

/** graph_review_item 1행 — repository/service 내부 전달용(payload는 JSON 문자열, 서비스가 파싱). */
public record ReviewItemRecord(
    Long id,
    String itemType,
    String status,
    Long datasetId,
    String signalType,
    Double signalScore,
    String reason,
    String payloadJson,
    Long decidedBy,
    LocalDateTime decidedAt,
    LocalDateTime createdAt) {}
