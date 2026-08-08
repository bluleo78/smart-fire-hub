package com.smartfirehub.ontology.dto;

import java.util.List;

// PUT /api/v1/ontology 요청 DTO — 지식 모델 전체를 한 번에 교체하는 full-document 편집(B-2b 슬라이스 5-1).
// OntologyResponse와 대칭 형태(entities에 properties 포함)이며, schemaVersion은 "로드 시점 기대 버전"으로
// 낙관적 동시성 검사에 쓰인다(현재 DB 버전과 일치할 때만 적용, 불일치 시 409).
// 중첩 레코드는 OntologyResponse의 것을 재사용해 읽기/쓰기 계약의 형태를 일치시킨다.
// renames(5-5, 5-6에서 목적 변경): 엔티티 타입 리네임 힌트 — entities에는 새 이름만 담기므로,
// OntologyRepository가 "이름이 바뀐 기존 행"을 매칭해 entity_type_id를 보존(UPDATE)할지, 새 항목으로
// 볼지(INSERT) 판단하는 데 쓰인다(리포지토리의 매칭 기반 UPDATE/INSERT/DELETE 참조). Neo4j는 이제
// entity_type_id 기반 key를 쓰므로 이 힌트와 무관하게 리네임의 영향을 받지 않는다.
// 상태 전이는 이 계약에 포함하지 않는다 — PATCH /ontology/{id}/status 전용
// (UpdateOntologyStatusRequest 주석에 분리 사유 정리).
// id 필드 계약(Task 2에서 OntologyResponse.Property/Triple에 id/subjectTypeId/objectTypeId를 추가한 뒤):
// 이 요청은 OntologyResponse의 레코드를 그대로 재사용하므로 본문에 그 id들을 실어 보낼 수 있지만,
// 쓰기 경로(OntologyRepository.updateOntology)는 여전히 "이름" 기준으로 매칭한다 — 엔티티 타입은
// entities[i].type 문자열로, 관계는 subject/object 이름(resolveTypeId)으로 대상을 찾는다.
// 즉 본문에 담긴 id/subjectTypeId/objectTypeId는 조용히 무시되며 저장 결과에 아무 영향도 주지 않는다
// (id 기반 매칭으로 바꾸는 것은 이 태스크의 범위가 아니라 요소 단위 PATCH API의 몫이다).
// 또한 이 PUT은 ontology_entity_property/ontology_relation을 delete-then-reinsert하므로, 응답으로
// 받았던 property.id / relation.id는 이 호출이 끝나는 순간 더 이상 유효하지 않다 — 클라이언트가 이어서
// 요소 단위로 무언가를 지목하려면 저장 후 반드시 GET으로 재조회해 새 id를 얻어야 한다.
public record UpdateOntologyRequest(
    String domain,
    int schemaVersion,
    List<OntologyResponse.EntityType> entities,
    List<OntologyResponse.Triple> relations,
    List<UpdateOntologyRequest.TypeRename> renames) {

  // 5-5 이전 호출부(4-인자) 하위호환 — renames 생략 시 빈 목록.
  public UpdateOntologyRequest(
      String domain,
      int schemaVersion,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations) {
    this(domain, schemaVersion, entities, relations, List.of());
  }

  // renames가 null(구버전 클라이언트, 또는 JSON에 필드 자체가 없는 경우)이면 빈 목록으로 정규화.
  public UpdateOntologyRequest {
    if (renames == null) {
      renames = List.of();
    }
  }

  public record TypeRename(String from, String to) {}
}
