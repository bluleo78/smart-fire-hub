package com.smartfirehub.ontology.dto;

import java.time.OffsetDateTime;

// GET /api/v1/ontologies 목록 응답 요약.
// status/entityCount/datasetCount/updatedAt는 온톨로지 관리 다이얼로그가 쓰는 필드다 —
// 도메인명만으로는 "지워도 되는지", "얼마나 채워졌는지"를 판단할 수 없어 함께 내려준다.
// isDefault: 문서 적재가 단수 /ontology로 의존하는 기본 온톨로지인지 — 삭제·은퇴 버튼 표시 여부를
// 프론트가 판정하지 않고 서버가 내려준다(OntologyService.DEFAULT_ONTOLOGY_ID 기준).
// 판정만 서버가 하고, 삭제 불가 사유 문구("기본 온톨로지") 표현은 프론트에 남아 있다.
public record OntologySummary(
    Long id,
    String domain,
    int schemaVersion,
    String status,
    int entityCount,
    int datasetCount,
    OffsetDateTime updatedAt,
    boolean isDefault) {}
