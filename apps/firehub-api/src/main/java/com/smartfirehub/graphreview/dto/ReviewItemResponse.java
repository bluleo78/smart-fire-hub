package com.smartfirehub.graphreview.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** graph_review_item API 응답 — payload는 타입별 상세를 담은 JSON 객체(web이 item_type으로 분기 렌더). */
public record ReviewItemResponse(
    Long id,
    String itemType,
    String status,
    Long datasetId,
    String signalType,
    Double signalScore,
    String reason,
    JsonNode payload,
    Long decidedBy,
    String decidedAt,
    String createdAt) {}
