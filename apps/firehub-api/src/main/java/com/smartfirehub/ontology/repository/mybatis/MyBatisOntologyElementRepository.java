package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import com.smartfirehub.ontology.repository.OntologyElementRepository;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.EntityTypeInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.PropertyInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.RelationInsert;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * OntologyElementRepository 의 MyBatis 구현(WD-711) — {@code JooqOntologyElementRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 호출자(OntologyElementService·MCP 툴
 * 핸들러)가 경계를 만들지 않으면 여기서 열어야 테넌트 컨텍스트가 심긴다.
 *
 * <p>새 타입·속성·관계는 항상 맨 뒤(max+1)에 붙이고 삭제로 생긴 빈 번호를 메우지 않는다(프롬프트 조립 순서 안정). PATCH 는 null 필드를 건드리지 않으며
 * 전부 null 이면 문장을 보내지 않는다(빈 SET 문법 오류 회피 겸 jOOQ 와 같은 "쓰기 없음"). 단위는 빈 문자열을 NULL 로 정규화한다(jOOQ
 * normalizeUnit 과 같음 — 두 DB 모두 "단위 없음"이 NULL).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisOntologyElementRepository implements OntologyElementRepository {

  private final OntologyElementMapper mapper;

  /** 요소 INSERT·schema_version 조회는 OntologyMapper 의 문장을 쓴다(중복 문장을 두지 않는다). */
  private final OntologyMapper ontologyMapper;

  public MyBatisOntologyElementRepository(
      OntologyElementMapper mapper, OntologyMapper ontologyMapper) {
    this.mapper = mapper;
    this.ontologyMapper = ontologyMapper;
  }

  /**
   * schema_version 을 1 올리고 새 값을 돌려준다. RETURNING 대신 갱신 뒤 같은 트랜잭션에서 다시 읽는다 — 갱신이 행을 잠가 두 문장 사이에 다른
   * 갱신이 끼어들지 못한다. 없는 온톨로지는 jOOQ(fetchOne() 이 null)처럼 NPE 로 끝난다(서비스가 먼저 lockAndFindStatus 로 거른다).
   */
  @Override
  public int bumpVersion(long ontologyId) {
    mapper.bumpVersion(ontologyId);
    return ontologyMapper.selectSchemaVersion(ontologyId);
  }

  @Override
  public void updateDomain(long ontologyId, String domain) {
    mapper.updateDomain(ontologyId, domain);
  }

  @Override
  public List<String> findTypeNames(long ontologyId) {
    return mapper.selectTypeNames(ontologyId);
  }

  @Override
  public int countEntityTypes(long ontologyId) {
    return mapper.countEntityTypes(ontologyId);
  }

  /** 이 온톨로지에 속한 타입이면 이름, 아니면 null — 소속 확인으로 남의 온톨로지 타입을 id 만으로 고치는 구멍을 막는다. */
  @Override
  public String findTypeName(long ontologyId, long entityTypeId) {
    return mapper.selectTypeName(ontologyId, entityTypeId);
  }

  @Override
  public long insertEntityType(long ontologyId, CreateEntityTypeRequest req) {
    EntityTypeInsert row =
        new EntityTypeInsert(
            ontologyId,
            req.type(),
            req.description(),
            req.naming(),
            req.resolution(),
            nextOrder(mapper.selectMaxTypeOrder(ontologyId)));
    ontologyMapper.insertEntityType(row);
    return row.getId();
  }

  @Override
  public void updateEntityType(long entityTypeId, UpdateEntityTypeRequest req) {
    if (req.type() == null
        && req.description() == null
        && req.naming() == null
        && req.resolution() == null) {
      return;
    }
    mapper.updateEntityType(
        entityTypeId, req.type(), req.description(), req.naming(), req.resolution());
  }

  /** 삭제 전에 부른다 — FK CASCADE 는 조용해서 지운 뒤에는 무엇이 함께 사라졌는지 알 수 없다. */
  @Override
  public List<Long> findRelationIdsTouching(long entityTypeId) {
    return mapper.selectRelationIdsTouching(entityTypeId);
  }

  @Override
  public void deleteEntityType(long entityTypeId) {
    mapper.deleteEntityType(entityTypeId);
  }

  @Override
  public List<String> findPropertyNames(long entityTypeId) {
    return mapper.selectPropertyNames(entityTypeId);
  }

  @Override
  public String findPropertyName(long entityTypeId, long propertyId) {
    return mapper.selectPropertyName(entityTypeId, propertyId);
  }

  @Override
  public long insertProperty(long entityTypeId, CreatePropertyRequest req) {
    PropertyInsert row =
        new PropertyInsert(
            entityTypeId,
            req.name(),
            req.description(),
            req.dataType(),
            normalizeUnit(req.unit()),
            nextOrder(mapper.selectMaxPropertyOrder(entityTypeId)));
    ontologyMapper.insertProperty(row);
    return row.getId();
  }

  /** unit 은 null 이면 변경 없음, 빈 문자열이면 NULL 로 지우기 — 그래서 "바꿀지" 플래그를 따로 넘긴다. */
  @Override
  public void updateProperty(long propertyId, UpdatePropertyRequest req) {
    boolean unitSet = req.unit() != null;
    if (req.name() == null && req.description() == null && req.dataType() == null && !unitSet) {
      return;
    }
    mapper.updateProperty(
        propertyId,
        req.name(),
        req.description(),
        req.dataType(),
        unitSet,
        unitSet ? normalizeUnit(req.unit()) : null);
  }

  @Override
  public void deleteProperty(long propertyId) {
    mapper.deleteProperty(propertyId);
  }

  /** 같은 트리플이 이미 있는지 — DB 유일 제약 위반 500 대신 사용자 어휘의 400 을 주려고 먼저 확인한다. */
  @Override
  public boolean existsTriple(
      long ontologyId, long subjectTypeId, String relation, long objectTypeId) {
    return mapper.countTriple(ontologyId, subjectTypeId, relation, objectTypeId) > 0;
  }

  @Override
  public long insertRelation(long ontologyId, CreateRelationRequest req) {
    RelationInsert row =
        new RelationInsert(
            ontologyId,
            req.subjectTypeId(),
            req.relation(),
            req.objectTypeId(),
            req.description(),
            nextOrder(mapper.selectMaxRelationOrder(ontologyId)));
    ontologyMapper.insertRelation(row);
    return row.getId();
  }

  @Override
  public void updateRelation(long relationId, UpdateRelationRequest req) {
    if (req.relation() == null && req.description() == null) {
      return;
    }
    mapper.updateRelation(relationId, req.relation(), req.description());
  }

  @Override
  public void deleteRelation(long relationId) {
    mapper.deleteRelation(relationId);
  }

  /** 맨 뒤 번호 — 비어 있으면 0, 아니면 max+1. */
  private static int nextOrder(Integer maxOrder) {
    return maxOrder == null ? 0 : maxOrder + 1;
  }

  /** 빈 단위를 NULL 로 통일한다(insert/update 두 경로가 같은 "단위 없음" 표현을 갖게). */
  private static String normalizeUnit(String unit) {
    return (unit == null || unit.isEmpty()) ? null : unit;
  }
}
