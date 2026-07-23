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
  public record Property(String name, String description, String dataType, String unit) {}

  // 온톨로지 관계(트리플) 정의: subject-relation-object.
  public record Triple(String subject, String relation, String object, String description) {}
}
