package com.smartfirehub.mapping.dto;

import java.util.List;

// 컬럼→온톨로지 요소 수동 매핑 문서. dataset_mapping.spec(JSONB)에 직렬화되어 저장된다.
public record MappingSpec(List<EntityMapping> entities, List<RelationMapping> relations) {
  // 컬럼 → 엔티티타입. nameColumn 값이 노드 이름(키)이 된다. properties는 그 엔티티의 속성 매핑.
  public record EntityMapping(String entityType, String nameColumn, List<PropertyMapping> properties) {}
  // 컬럼 → 엔티티 속성.
  public record PropertyMapping(String column, String propertyName) {}
  // entities 인덱스(subjectRef/objectRef) 간 관계. relation은 ontology_relation.relation.
  public record RelationMapping(int subjectRef, String relation, int objectRef) {}
}
