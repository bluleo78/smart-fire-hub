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
// status: null이면 "상태를 바꾸지 않음"(스키마만 편집). 값이 있으면 상태 전이를 함께 요청한다.
// 전이 허용 여부는 OntologyService가 현재 상태와 대조해 판정한다.
public record UpdateOntologyRequest(
    String domain,
    int schemaVersion,
    List<OntologyResponse.EntityType> entities,
    List<OntologyResponse.Triple> relations,
    List<UpdateOntologyRequest.TypeRename> renames,
    String status) {

  // 5-5 이전 호출부(4-인자) 하위호환 — renames 생략 시 빈 목록, 상태 변경 없음.
  public UpdateOntologyRequest(
      String domain,
      int schemaVersion,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations) {
    this(domain, schemaVersion, entities, relations, List.of(), null);
  }

  // status 도입 이전 호출부(5-인자) 하위호환 — 상태 변경 없음.
  public UpdateOntologyRequest(
      String domain,
      int schemaVersion,
      List<OntologyResponse.EntityType> entities,
      List<OntologyResponse.Triple> relations,
      List<UpdateOntologyRequest.TypeRename> renames) {
    this(domain, schemaVersion, entities, relations, renames, null);
  }

  // renames가 null(구버전 클라이언트, 또는 JSON에 필드 자체가 없는 경우)이면 빈 목록으로 정규화.
  public UpdateOntologyRequest {
    if (renames == null) {
      renames = List.of();
    }
  }

  public record TypeRename(String from, String to) {}
}
