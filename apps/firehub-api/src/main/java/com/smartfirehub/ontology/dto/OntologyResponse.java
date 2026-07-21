package com.smartfirehub.ontology.dto;

import java.util.List;

// GET /api/v1/ontology 응답 DTO — ai-agent GET /agent/ontology 와 1:1 매핑(순수 프록시 계약).
public record OntologyResponse(String domain, List<EntityType> entities, List<Triple> relations) {
  // 온톨로지 엔티티 타입 정의 (타입명·설명·명명규칙·해상도 전략).
  public record EntityType(String type, String description, String naming, String resolution) {}

  // 온톨로지 관계(트리플) 정의: subject-relation-object.
  public record Triple(String subject, String relation, String object, String description) {}
}
