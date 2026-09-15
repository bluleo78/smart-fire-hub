package com.smartfirehub.ontology.dto;

import java.time.OffsetDateTime;

// GET /api/v1/ontologies 목록 응답 요약.
// status/entityCount/datasetCount/updatedAt는 온톨로지 관리 다이얼로그가 쓰는 필드다 —
// 도메인명만으로는 "지워도 되는지", "얼마나 채워졌는지"를 판단할 수 없어 함께 내려준다.
public record OntologySummary(
    Long id,
    String domain,
    int schemaVersion,
    String status,
    int entityCount,
    int datasetCount,
    OffsetDateTime updatedAt) {}
