package com.smartfirehub.ontology.dto;

import java.util.List;

// POST /api/v1/ontologies 요청 — 신규 도메인 온톨로지 생성. schemaVersion은 생성 시 항상 1로 시작하므로
// 요청에 없다(UpdateOntologyRequest와 달리 낙관적 잠금 대상 아니다). 중첩 레코드는 OntologyResponse 재사용.
// status: AI 챗은 'draft'로 제안하고 사람이 UI에서 활성화한다. 'archived' 상태로는 생성할 수 없다
// (은퇴는 운영을 마친 것에 대한 조치이지 생성 시점의 선택이 아니다) — 검증은 OntologyService가 한다.
public record CreateOntologyRequest(
    String domain,
    List<OntologyResponse.EntityType> entities,
    List<OntologyResponse.Triple> relations,
    String status) {

  // status 도입 이전 호출부(3-인자) 하위호환 — 생략 시 active로 생성한다.
  public CreateOntologyRequest(
      String domain,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations) {
    this(domain, entities, relations, "active");
  }

  public CreateOntologyRequest {
    if (status == null || status.isBlank()) {
      status = "active";
    }
  }
}
