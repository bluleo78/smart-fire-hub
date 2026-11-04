package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import java.util.List;

/**
 * 요소 단위 온톨로지 쓰기 — 전체 스키마를 왕복시키던 OntologyRepository.updateOntology(S2 Task 7에서 삭제됨)와 달리 행 하나만 건드린다.
 * 읽기는 OntologyRepository.findById를 그대로 쓴다(중복 조립 회피).
 *
 * <p>구현은 {@code JooqOntologyElementRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface OntologyElementRepository {

  /**
   * schema_version을 1 올리고 새 값을 돌려준다. 모든 요소 뮤테이션이 이걸 거친다 — 적재된 Neo4j 노드의 schemaVersion 스탬프와 부등호로
   * 비교되므로 단조 증가만 지키면 된다.
   */
  int bumpVersion(long ontologyId);

  void updateDomain(long ontologyId, String domain);

  /** 현재 타입 이름 목록 — 중복 검사와 "마지막 타입" 판정에 쓴다. */
  List<String> findTypeNames(long ontologyId);

  int countEntityTypes(long ontologyId);

  /** 특정 타입이 이 온톨로지에 속하는지 + 현재 이름. 없으면 null. 온톨로지 소속을 함께 확인해야 남의 온톨로지 타입을 id만 알면 고칠 수 있는 구멍이 막힌다. */
  String findTypeName(long ontologyId, long entityTypeId);

  /**
   * 새 타입은 항상 맨 뒤(max+1)에 붙인다. 삭제로 생긴 빈 번호는 메우지 않는다 — 재번호를 매기면 무관한 타입들의 프롬프트 조립 순서까지 함께 흔들린다(V71 주석
   * 참조).
   */
  long insertEntityType(long ontologyId, CreateEntityTypeRequest req);

  /** null 필드는 건드리지 않는다(PATCH 의미론). 전부 null이면 쓰기 없이 반환한다. */
  void updateEntityType(long entityTypeId, UpdateEntityTypeRequest req);

  /**
   * 삭제 전에 조회해야 한다 — FK ON DELETE CASCADE는 조용해서, 지운 뒤에는 무엇이 함께 사라졌는지 알 수 없다. 클라이언트 낙관적 갱신과 감사 로그가 이
   * 목록을 필요로 한다.
   */
  List<Long> findRelationIdsTouching(long entityTypeId);

  void deleteEntityType(long entityTypeId);

  List<String> findPropertyNames(long entityTypeId);

  /** 속성이 지정한 타입에 실제로 속하는지 + 현재 이름. 소속이 다르면 null. */
  String findPropertyName(long entityTypeId, long propertyId);

  /** 엔티티 타입과 같은 규칙 — 맨 뒤(max+1)에 붙이고 삭제로 생긴 빈 번호는 메우지 않는다. */
  long insertProperty(long entityTypeId, CreatePropertyRequest req);

  void updateProperty(long propertyId, UpdatePropertyRequest req);

  void deleteProperty(long propertyId);

  /** 같은 트리플이 이미 있는지 — DB UNIQUE(V80)가 최종 방어선이지만, 제약 위반 500 대신 사용자 어휘로 된 400을 주기 위해 먼저 확인한다. */
  boolean existsTriple(long ontologyId, long subjectTypeId, String relation, long objectTypeId);

  long insertRelation(long ontologyId, CreateRelationRequest req);

  void updateRelation(long relationId, UpdateRelationRequest req);

  void deleteRelation(long relationId);
}
