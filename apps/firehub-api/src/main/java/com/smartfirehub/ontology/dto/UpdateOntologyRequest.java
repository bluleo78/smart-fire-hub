package com.smartfirehub.ontology.dto;

import java.util.List;

// PUT /api/v1/ontology 요청 DTO — 지식 모델 전체를 한 번에 교체하는 full-document 편집(B-2b 슬라이스 5-1).
// OntologyResponse와 대칭 형태(entities에 properties 포함)이며, schemaVersion은 "로드 시점 기대 버전"으로
// 낙관적 동시성 검사에 쓰인다(현재 DB 버전과 일치할 때만 적용, 불일치 시 409).
// 중첩 레코드는 OntologyResponse의 것을 재사용해 읽기/쓰기 계약의 형태를 일치시킨다.
public record UpdateOntologyRequest(
    String domain,
    int schemaVersion,
    List<OntologyResponse.EntityType> entities,
    List<OntologyResponse.Triple> relations) {}
