package com.smartfirehub.ontology.dto;

import java.util.List;

// GET /api/v1/ontology 응답 DTO — api DB(OntologyRepository)에서 조립하는 온톨로지 스키마 계약.
// (B-2a 이전엔 ai-agent 프록시였으나, 이제 api DB가 단일 소유. ai-agent가 추출 시 이 형태로 역직렬화함.)
public record OntologyResponse(String domain, List<EntityType> entities, List<Triple> relations) {
  // 온톨로지 엔티티 타입 정의 (타입명·설명·명명규칙·해상도 전략).
  public record EntityType(String type, String description, String naming, String resolution) {}

  // 온톨로지 관계(트리플) 정의: subject-relation-object.
  public record Triple(String subject, String relation, String object, String description) {}
}
