package com.smartfirehub.ontology.dto;

import java.util.List;

// POST /api/v1/ontologies 요청 — 신규 도메인 온톨로지 생성. schemaVersion은 생성 시 항상 1로 시작하므로
// 요청에 없다(UpdateOntologyRequest와 달리 낙관적 잠금 대상 아니다). 중첩 레코드는 OntologyResponse 재사용.
public record CreateOntologyRequest(
    String domain,
    List<OntologyResponse.EntityType> entities,
    List<OntologyResponse.Triple> relations) {}
