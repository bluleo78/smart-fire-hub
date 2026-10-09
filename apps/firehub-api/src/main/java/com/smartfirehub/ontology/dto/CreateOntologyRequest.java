package com.smartfirehub.ontology.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// POST /api/v1/ontologies 요청 — 신규 도메인 온톨로지 생성. schemaVersion은 생성 시 항상 1로 시작하므로
// 요청에 없다(낙관적 잠금 대상 아니다). 중첩 레코드는 OntologyResponse 재사용.
// status: AI 챗은 'draft'로 제안하고 사람이 UI에서 활성화한다. 'archived' 상태로는 생성할 수 없다
// (은퇴는 운영을 마친 것에 대한 조치이지 생성 시점의 선택이 아니다) — 검증은 OntologyService가 한다.
public record CreateOntologyRequest(
    String domain,
    List<OntologyResponse.EntityType> entities,
    List<OntologyResponse.Triple> relations,
    String status,
    // WD-31⑤: 추론 표본을 뽑은 데이터셋(선택). 서버가 VIEW·AI·SHARE 판정 후 graph_ontology_source 에 기록한다 —
    // 그래야 이 온톨로지의 그래프 읽기 게이트(WD-28)가 표본 출처의 등급을 따른다.
    List<Long> sourceDatasetIds) {

  // status 도입 이전 호출부(3-인자) 하위호환 — 생략 시 active로 생성한다.
  public CreateOntologyRequest(
      String domain,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations) {
    this(domain, entities, relations, "active", null);
  }

  // 출처 도입 이전 호출부(4-인자) 하위호환 — 출처 없음.
  public CreateOntologyRequest(
      String domain,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations,
      String status) {
    this(domain, entities, relations, status, null);
  }

  public CreateOntologyRequest {
    if (status == null || status.isBlank()) {
      status = "active";
    }
    // 생략은 "출처 없음". null 원소는 List.copyOf 가 NPE(→500)로 터뜨리므로 그대로 담아 두고 OntologyService 가 400 으로 거부한다.
    sourceDatasetIds =
        sourceDatasetIds == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(sourceDatasetIds));
  }
}
