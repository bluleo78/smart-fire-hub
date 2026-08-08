package com.smartfirehub.ontology.element;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

// 요소 단위 온톨로지 쓰기 — 전체 스키마를 왕복시키던 OntologyRepository.updateOntology(S2 Task 7에서
// 삭제됨)와 달리 행 하나만 건드린다. 읽기는 OntologyRepository.findById를 그대로 쓴다(중복 조립 회피).
@Repository
@RequiredArgsConstructor
public class OntologyElementRepository {

  private final DSLContext dsl;

  private static final Table<?> ONTOLOGY = table(name("ontology"));
  private static final Field<Long> O_ID = field(name("ontology", "id"), Long.class);
  private static final Field<String> O_DOMAIN = field(name("ontology", "domain"), String.class);
  private static final Field<Integer> O_SCHEMA_VERSION = field(name("ontology", "schema_version"), Integer.class);
  private static final Field<OffsetDateTime> O_UPDATED_AT = field(name("ontology", "updated_at"), OffsetDateTime.class);

  private static final Table<?> ENTITY_TYPE = table(name("ontology_entity_type"));
  private static final Field<Long> ET_ID = field(name("ontology_entity_type", "id"), Long.class);
  private static final Field<Long> ET_ONTOLOGY_ID = field(name("ontology_entity_type", "ontology_id"), Long.class);
  private static final Field<String> ET_TYPE = field(name("ontology_entity_type", "type"), String.class);
  private static final Field<String> ET_DESC = field(name("ontology_entity_type", "description"), String.class);
  private static final Field<String> ET_NAMING = field(name("ontology_entity_type", "naming"), String.class);
  private static final Field<String> ET_RES = field(name("ontology_entity_type", "resolution"), String.class);
  private static final Field<Integer> ET_ORDER = field(name("ontology_entity_type", "sort_order"), Integer.class);

  private static final Table<?> ENTITY_PROP = table(name("ontology_entity_property"));
  private static final Field<Long> EP_ID = field(name("ontology_entity_property", "id"), Long.class);
  private static final Field<Long> EP_TYPE_ID = field(name("ontology_entity_property", "entity_type_id"), Long.class);
  private static final Field<String> EP_NAME = field(name("ontology_entity_property", "name"), String.class);
  private static final Field<String> EP_DESC = field(name("ontology_entity_property", "description"), String.class);
  private static final Field<String> EP_DTYPE = field(name("ontology_entity_property", "data_type"), String.class);
  private static final Field<String> EP_UNIT = field(name("ontology_entity_property", "unit"), String.class);
  private static final Field<Integer> EP_ORDER = field(name("ontology_entity_property", "sort_order"), Integer.class);

  private static final Table<?> RELATION = table(name("ontology_relation"));
  private static final Field<Long> R_ID = field(name("ontology_relation", "id"), Long.class);
  private static final Field<Long> R_SUBJECT_ID = field(name("ontology_relation", "subject_type_id"), Long.class);
  private static final Field<Long> R_OBJECT_ID = field(name("ontology_relation", "object_type_id"), Long.class);
  private static final Field<Long> R_ONTOLOGY_ID = field(name("ontology_relation", "ontology_id"), Long.class);
  private static final Field<String> R_RELATION = field(name("ontology_relation", "relation"), String.class);
  private static final Field<String> R_DESC = field(name("ontology_relation", "description"), String.class);
  private static final Field<Integer> R_ORDER = field(name("ontology_relation", "sort_order"), Integer.class);

  // schema_version을 1 올리고 새 값을 돌려준다. 모든 요소 뮤테이션이 이걸 거친다 —
  // 적재된 Neo4j 노드의 schemaVersion 스탬프와 부등호로 비교되므로 단조 증가만 지키면 된다.
  public int bumpVersion(long ontologyId) {
    return dsl.update(ONTOLOGY)
        .set(O_SCHEMA_VERSION, O_SCHEMA_VERSION.plus(1))
        .set(O_UPDATED_AT, currentOffsetDateTime())
        .where(O_ID.eq(ontologyId))
        .returning(O_SCHEMA_VERSION)
        .fetchOne()
        .get(O_SCHEMA_VERSION);
  }

  public void updateDomain(long ontologyId, String domain) {
    dsl.update(ONTOLOGY).set(O_DOMAIN, domain).where(O_ID.eq(ontologyId)).execute();
  }

  // 현재 타입 이름 목록 — 중복 검사와 "마지막 타입" 판정에 쓴다.
  public List<String> findTypeNames(long ontologyId) {
    return dsl.select(ET_TYPE).from(ENTITY_TYPE).where(ET_ONTOLOGY_ID.eq(ontologyId))
        .fetch(r -> r.get(ET_TYPE));
  }

  public int countEntityTypes(long ontologyId) {
    return dsl.fetchCount(dsl.selectOne().from(ENTITY_TYPE).where(ET_ONTOLOGY_ID.eq(ontologyId)));
  }

  // 특정 타입이 이 온톨로지에 속하는지 + 현재 이름. 없으면 null.
  // 온톨로지 소속을 함께 확인해야 남의 온톨로지 타입을 id만 알면 고칠 수 있는 구멍이 막힌다.
  public String findTypeName(long ontologyId, long entityTypeId) {
    var rec = dsl.select(ET_TYPE).from(ENTITY_TYPE)
        .where(ET_ID.eq(entityTypeId).and(ET_ONTOLOGY_ID.eq(ontologyId))).fetchOne();
    return rec == null ? null : rec.get(ET_TYPE);
  }

  // 새 타입은 항상 맨 뒤(max+1)에 붙인다. 삭제로 생긴 빈 번호는 메우지 않는다 —
  // 재번호를 매기면 무관한 타입들의 프롬프트 조립 순서까지 함께 흔들린다(V71 주석 참조).
  public long insertEntityType(long ontologyId, CreateEntityTypeRequest req) {
    Integer maxOrder = dsl.select(max(ET_ORDER)).from(ENTITY_TYPE)
        .where(ET_ONTOLOGY_ID.eq(ontologyId)).fetchOne().value1();
    return dsl.insertInto(ENTITY_TYPE)
        .set(ET_ONTOLOGY_ID, ontologyId)
        .set(ET_TYPE, req.type())
        .set(ET_DESC, req.description())
        .set(ET_NAMING, req.naming())
        .set(ET_RES, req.resolution())
        .set(ET_ORDER, maxOrder == null ? 0 : maxOrder + 1)
        .returning(ET_ID)
        .fetchOne()
        .get(ET_ID);
  }

  // null 필드는 건드리지 않는다(PATCH 의미론). 전부 null이면 쓰기 없이 반환한다.
  public void updateEntityType(long entityTypeId, UpdateEntityTypeRequest req) {
    Map<Field<?>, Object> changes = new HashMap<>();
    if (req.type() != null) changes.put(ET_TYPE, req.type());
    if (req.description() != null) changes.put(ET_DESC, req.description());
    if (req.naming() != null) changes.put(ET_NAMING, req.naming());
    if (req.resolution() != null) changes.put(ET_RES, req.resolution());
    if (changes.isEmpty()) return;
    dsl.update(ENTITY_TYPE).set(changes).where(ET_ID.eq(entityTypeId)).execute();
  }

  // 삭제 전에 조회해야 한다 — FK ON DELETE CASCADE는 조용해서, 지운 뒤에는 무엇이 함께
  // 사라졌는지 알 수 없다. 클라이언트 낙관적 갱신과 감사 로그가 이 목록을 필요로 한다.
  public List<Long> findRelationIdsTouching(long entityTypeId) {
    return dsl.select(R_ID).from(RELATION)
        .where(R_SUBJECT_ID.eq(entityTypeId).or(R_OBJECT_ID.eq(entityTypeId)))
        .fetch(r -> r.get(R_ID));
  }

  public void deleteEntityType(long entityTypeId) {
    dsl.deleteFrom(ENTITY_TYPE).where(ET_ID.eq(entityTypeId)).execute();
  }

  public List<String> findPropertyNames(long entityTypeId) {
    return dsl.select(EP_NAME).from(ENTITY_PROP).where(EP_TYPE_ID.eq(entityTypeId))
        .fetch(r -> r.get(EP_NAME));
  }

  // 속성이 지정한 타입에 실제로 속하는지 + 현재 이름. 소속이 다르면 null.
  public String findPropertyName(long entityTypeId, long propertyId) {
    var rec = dsl.select(EP_NAME).from(ENTITY_PROP)
        .where(EP_ID.eq(propertyId).and(EP_TYPE_ID.eq(entityTypeId))).fetchOne();
    return rec == null ? null : rec.get(EP_NAME);
  }

  // 엔티티 타입과 같은 규칙 — 맨 뒤(max+1)에 붙이고 삭제로 생긴 빈 번호는 메우지 않는다.
  public long insertProperty(long entityTypeId, CreatePropertyRequest req) {
    Integer maxOrder = dsl.select(max(EP_ORDER)).from(ENTITY_PROP)
        .where(EP_TYPE_ID.eq(entityTypeId)).fetchOne().value1();
    return dsl.insertInto(ENTITY_PROP)
        .set(EP_TYPE_ID, entityTypeId)
        .set(EP_NAME, req.name())
        .set(EP_DESC, req.description())
        .set(EP_DTYPE, req.dataType())
        .set(EP_UNIT, normalizeUnit(req.unit()))
        .set(EP_ORDER, maxOrder == null ? 0 : maxOrder + 1)
        .returning(EP_ID)
        .fetchOne()
        .get(EP_ID);
  }

  public void updateProperty(long propertyId, UpdatePropertyRequest req) {
    java.util.Map<Field<?>, Object> changes = new java.util.HashMap<>();
    if (req.name() != null) changes.put(EP_NAME, req.name());
    if (req.description() != null) changes.put(EP_DESC, req.description());
    if (req.dataType() != null) changes.put(EP_DTYPE, req.dataType());
    // 빈 문자열은 "단위 없음"으로 정규화한다 — JSON에 null을 보내는 것으로는 PATCH 의미론상
    // "변경 없음"과 구분되지 않기 때문이다.
    if (req.unit() != null) changes.put(EP_UNIT, normalizeUnit(req.unit()));
    if (changes.isEmpty()) return;
    dsl.update(ENTITY_PROP).set(changes).where(EP_ID.eq(propertyId)).execute();
  }

  // 빈 문자열 단위를 NULL로 통일한다. insert/update 두 경로에서 각자 정규화하면 같은 필드가
  // 생성 시점과 수정 시점에 서로 다른 "단위 없음" 표현(""/NULL)을 갖게 되므로 여기 한 곳에 둔다.
  private static String normalizeUnit(String unit) {
    return (unit == null || unit.isEmpty()) ? null : unit;
  }

  public void deleteProperty(long propertyId) {
    dsl.deleteFrom(ENTITY_PROP).where(EP_ID.eq(propertyId)).execute();
  }

  // 같은 트리플이 이미 있는지 — DB UNIQUE(V80)가 최종 방어선이지만, 제약 위반 500 대신
  // 사용자 어휘로 된 400을 주기 위해 먼저 확인한다.
  public boolean existsTriple(long ontologyId, long subjectTypeId, String relation, long objectTypeId) {
    return dsl.fetchExists(dsl.selectOne().from(RELATION)
        .where(R_ONTOLOGY_ID.eq(ontologyId))
        .and(R_SUBJECT_ID.eq(subjectTypeId))
        .and(R_RELATION.eq(relation))
        .and(R_OBJECT_ID.eq(objectTypeId)));
  }

  public long insertRelation(long ontologyId, CreateRelationRequest req) {
    Integer maxOrder = dsl.select(max(R_ORDER)).from(RELATION)
        .where(R_ONTOLOGY_ID.eq(ontologyId)).fetchOne().value1();
    return dsl.insertInto(RELATION)
        .set(R_ONTOLOGY_ID, ontologyId)
        .set(R_SUBJECT_ID, req.subjectTypeId())
        .set(R_RELATION, req.relation())
        .set(R_OBJECT_ID, req.objectTypeId())
        .set(R_DESC, req.description())
        .set(R_ORDER, maxOrder == null ? 0 : maxOrder + 1)
        .returning(R_ID)
        .fetchOne()
        .get(R_ID);
  }

  public void updateRelation(long relationId, UpdateRelationRequest req) {
    Map<Field<?>, Object> changes = new HashMap<>();
    if (req.relation() != null) changes.put(R_RELATION, req.relation());
    if (req.description() != null) changes.put(R_DESC, req.description());
    if (changes.isEmpty()) return;
    dsl.update(RELATION).set(changes).where(R_ID.eq(relationId)).execute();
  }

  public void deleteRelation(long relationId) {
    dsl.deleteFrom(RELATION).where(R_ID.eq(relationId)).execute();
  }
}
