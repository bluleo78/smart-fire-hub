package com.smartfirehub.ontology.dto;

import java.util.List;

// PUT /api/v1/ontology 요청 DTO — 지식 모델 전체를 한 번에 교체하는 full-document 편집(B-2b 슬라이스 5-1).
// OntologyResponse와 대칭 형태(entities에 properties 포함)이며, schemaVersion은 "로드 시점 기대 버전"으로
// 낙관적 동시성 검사에 쓰인다(현재 DB 버전과 일치할 때만 적용, 불일치 시 409).
// 중첩 레코드는 OntologyResponse의 것을 재사용해 읽기/쓰기 계약의 형태를 일치시킨다.
// renames(5-5): 엔티티 타입 리네임 의도 목록 — DB는 entities의 최종 타입명만으로 리네임을 그대로
// 처리하지만(entity_type_id가 Long FK라 이름 변경에 영향받지 않음), Neo4j 노드 key/type을
// 동기 마이그레이션하려면 "무엇을 무엇으로 바꿨는지"를 별도로 알아야 한다(OntologyService 참조).
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
