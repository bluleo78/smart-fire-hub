package com.smartfirehub.graphreview.dto;

import java.util.List;

/** 저신뢰 관계 검수 등록 요청(ai-agent → api). 키(subjectKey/objectKey)는 ai-agent가 계산한 canonical opaque 값. */
public record PendingRelationRequest(
    Long datasetId, String subjectKey, String relType, String objectKey,
    String subjectName, String objectName, List<Long> sourceChunkIds, Double confidence, String reason) {}
