package com.smartfirehub.graphreview.dto;

/** 정규화 실패 속성 검수 등록 요청(ai-agent → api). entityKey는 canonical 재매핑 후 최종 Neo4j key. */
public record PendingPropertyRequest(
    Long datasetId, Long chunkId, String entityKey, String entityType,
    String propertyName, String dataType, String rawText) {}
