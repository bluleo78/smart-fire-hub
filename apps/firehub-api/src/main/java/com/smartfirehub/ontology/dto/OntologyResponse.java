package com.smartfirehub.ontology.dto;

import java.util.List;

// GET /api/v1/ontology 응답 DTO — api DB(OntologyRepository)에서 조립하는 온톨로지 스키마 계약.
// (B-2a 이전엔 ai-agent 프록시였으나, 이제 api DB가 단일 소유. ai-agent가 추출 시 이 형태로 역직렬화함.)
public record OntologyResponse(String domain, int schemaVersion, List<EntityType> entities, List<Triple> relations) {
  // 온톨로지 엔티티 타입 정의 (타입명·설명·명명규칙·해상도·데이터 프로퍼티 목록).
  // id(5-6): ontology_entity_type.id(서로게이트 PK) — 리네임 시에도 리포지토리가 UPDATE로 보존해
  // ai-agent가 Neo4j 노드 key를 이 id 기반으로 구성할 수 있게 한다(타입명이 바뀌어도 key 불변).
  public record EntityType(String type, String description, String naming, String resolution,
                           List<Property> properties, Long id) {

    // 5-6 이전 호출부(5-인자) 하위호환 — id 생략 시 null(신규 삽입 대상으로 취급).
    public EntityType(String type, String description, String naming, String resolution, List<Property> properties) {
      this(type, description, naming, resolution, properties, null);
    }
  }

  // 엔티티 데이터 프로퍼티: 속성명·설명·데이터타입(text|number|date)·단위(nullable).
  // id: ontology_entity_property.id — 요소 단위 편집 API(PATCH/DELETE .../properties/{id})가
  // 대상을 지목하는 주소다. 이름으로 지목하면 리네임과 동시 수정이 서로를 덮어쓴다.
  public record Property(String name, String description, String dataType, String unit, Long id) {

    // id 도입 이전 호출부(4-인자) 하위호환 — 생략 시 null(신규 삽입 대상으로 취급).
    public Property(String name, String description, String dataType, String unit) {
      this(name, description, dataType, unit, null);
    }
  }

  // 온톨로지 관계(트리플) 정의.
  // subject/object는 사람이 읽는 타입 "이름"으로 계속 내보낸다 — ai-agent가 이 계약을 이름 기반으로
  // 소비(추출 프롬프트·표 투영)하므로 빼면 그쪽 전체가 딸려온다.
  // id/subjectTypeId/objectTypeId(V80): 저장은 FK 기반이며, 요소 단위 편집 API는 이 id들로 대화한다.
  public record Triple(String subject, String relation, String object, String description,
                       Long id, Long subjectTypeId, Long objectTypeId) {

    // id 도입 이전 호출부(4-인자) 하위호환.
    public Triple(String subject, String relation, String object, String description) {
      this(subject, relation, object, description, null, null, null);
    }
  }
}
