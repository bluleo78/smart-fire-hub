package com.smartfirehub.graphreview.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 저신뢰 엔티티 검수 등록 요청(ai-agent → api). properties는 정규화된 속성 객체(그대로 저장·재전달). */
public record PendingEntityRequest(
    Long datasetId, String entityType, String name, JsonNode properties,
    List<Long> sourceChunkIds, Double confidence, String reason, List<EntityRelationRef> relations) {}
