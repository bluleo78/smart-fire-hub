package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.EntityTypeInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.EntityTypeRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.HeadRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.OntologyInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.PropertyInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.PropertyRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.RelationInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.RelationRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * OntologyRepository 의 MyBatis 구현(WD-711) — {@code JooqOntologyRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 호출자(OntologyService·MCP 툴 핸들러)는
 * 트랜잭션 경계를 만들지 않으므로, 여기서 열지 않으면 테넌트 컨텍스트가 심기지 않아 조회는 0행, 쓰기는 tenant_id NOT NULL 위반이 된다.
 * createOntology 는 jOOQ 의 중첩 transactionResult 대신 이 트랜잭션 하나로 원자성을 얻는다.
 *
 * <p>빈 문자열(Review Focus 5): findById 결과는 ai-agent 추출 프롬프트에 바이트 그대로 들어간다. PG 에서 NOT NULL 인 설명·명명 규칙
 * (엔티티 타입 description·naming, 속성 description, 관계 description)은 Tibero 가 {@code ""} 를 NULL 로 저장하므로
 * 자바에서 null → {@code ""} 로 되돌린다(SQL 의 NVL(col, '') 은 Tibero 에서 다시 NULL). 속성 unit 은 PG 도 "단위 없음"을
 * NULL 로 두는 열이라 바꾸지 않는다 — 바꾸면 PG 의 NULL 단위가 {@code ""} 로 달라진다(프롬프트는 빈 단위·null 단위를 같게 그린다).
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisOntologyRepository implements OntologyRepository {

  private final OntologyMapper mapper;

  public MyBatisOntologyRepository(OntologyMapper mapper) {
    this.mapper = mapper;
  }

  /** PG 에서 NOT NULL 인 프롬프트 텍스트 열의 null(Tibero 빈 문자열)을 {@code ""} 로 되돌린다. */
  static String promptText(String value) {
    return value == null ? "" : value;
  }

  /** 지정한 온톨로지를 OntologyResponse 로 조립한다 — 타입·속성·관계를 각각 한 번씩 읽어 메모리에서 묶는다(N+1 없음). */
  @Override
  public OntologyResponse findById(long ontologyId) {
    HeadRow head = mapper.selectHead(ontologyId);
    // 존재하지 않는(또는 다른 테넌트의) id 는 NPE→500 이 아니라 400(전역 핸들러 규약)으로 막는다.
    if (head == null) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }

    // 속성은 (타입, sort_order) 순으로 읽으므로 순차 적재만으로 타입별 순서가 보존된다.
    Map<Long, List<OntologyResponse.Property>> propsByTypeId = new HashMap<>();
    for (PropertyRow p : mapper.selectProperties(ontologyId)) {
      propsByTypeId
          .computeIfAbsent(p.entityTypeId(), k -> new ArrayList<>())
          .add(
              new OntologyResponse.Property(
                  p.name(), promptText(p.description()), p.dataType(), p.unit(), p.id()));
    }

    List<OntologyResponse.EntityType> entities = new ArrayList<>();
    Map<Long, String> typeNameById = new HashMap<>();
    for (EntityTypeRow e : mapper.selectEntityTypes(ontologyId)) {
      entities.add(
          new OntologyResponse.EntityType(
              e.type(),
              promptText(e.description()),
              promptText(e.naming()),
              e.resolution(),
              // 속성이 없는 타입도 null 이 아니라 빈 목록(기존 계약).
              propsByTypeId.getOrDefault(e.id(), List.of()),
              e.id()));
      typeNameById.put(e.id(), e.type());
    }

    // 관계는 타입 id 를 들고 있으므로 위 타입 목록으로 이름을 되붙인다(읽기 계약은 이름 유지).
    List<OntologyResponse.Triple> relations = new ArrayList<>();
    for (RelationRow r : mapper.selectRelations(ontologyId)) {
      relations.add(
          new OntologyResponse.Triple(
              typeNameById.get(r.subjectTypeId()),
              r.relation(),
              typeNameById.get(r.objectTypeId()),
              promptText(r.description()),
              r.id(),
              r.subjectTypeId(),
              r.objectTypeId()));
    }

    return new OntologyResponse(head.domain(), head.schemaVersion(), entities, relations);
  }

  /** 목록 요약(id 순). statusFilter 가 null 이면 전체. 수정 시각은 UTC OffsetDateTime 으로 돌려준다. */
  @Override
  public List<OntologySummary> findAllSummaries(String statusFilter) {
    return mapper.selectSummaries(statusFilter).stream()
        .map(
            r ->
                new OntologySummary(
                    r.id(),
                    r.domain(),
                    r.schemaVersion(),
                    r.status(),
                    r.entityCount(),
                    r.datasetCount(),
                    r.updatedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(r.updatedAt(), ZoneOffset.UTC)))
        .toList();
  }

  /** 하위호환 — 인자 없는 호출은 전체 목록. */
  @Override
  public List<OntologySummary> findAllSummaries() {
    return findAllSummaries(null);
  }

  /** archived 가 아닌 같은 도메인이 있는지 — 부분 유일 색인(archived 제외)과 같은 기준. */
  @Override
  public boolean existsLiveDomain(String domain) {
    return mapper.countLiveDomain(domain) > 0;
  }

  @Override
  public String findStatusById(long ontologyId) {
    return requireStatus(mapper.selectStatus(ontologyId), ontologyId);
  }

  /** 요소 편집 트랜잭션의 첫 문장 — 행을 잠그고 상태를 읽는다(PG FOR NO KEY UPDATE, Tibero FOR UPDATE). */
  @Override
  public String lockAndFindStatus(long ontologyId) {
    return requireStatus(mapper.lockAndSelectStatus(ontologyId), ontologyId);
  }

  /** 매핑 저장·활성화가 검증 전에 잡는 공유 잠금(PG FOR SHARE, Tibero 는 공유 잠금이 없어 FOR UPDATE). 없는 id 는 조용히 지나간다. */
  @Override
  public void lockForShare(long ontologyId) {
    mapper.lockForShare(ontologyId);
  }

  /** 상태와 수정 시각만 바꾼다 — 스키마 내용이 그대로이므로 schema_version 은 올리지 않는다. */
  @Override
  public void updateStatus(long ontologyId, String status) {
    mapper.updateStatus(ontologyId, status);
  }

  /** 이 온톨로지를 참조하는 서로 다른 데이터셋 수(바인딩 ∪ 매핑) — 삭제 가능 여부 판정 근거. */
  @Override
  public int countReferences(long ontologyId) {
    return mapper.countReferences(ontologyId);
  }

  @Override
  public void deleteOntology(long ontologyId) {
    mapper.deleteOntology(ontologyId);
  }

  /** 존재 여부 — 정책이 다른 테넌트 행을 거르므로 "없음"과 "내 것이 아님"이 같은 답이다. */
  @Override
  public boolean existsById(long ontologyId) {
    return mapper.countById(ontologyId) > 0;
  }

  @Override
  public int currentSchemaVersion(long ontologyId) {
    Integer version = mapper.selectSchemaVersion(ontologyId);
    if (version == null) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    return version;
  }

  /**
   * 새 도메인 온톨로지 — ontology 행(schema_version 1) + 타입 + 속성 + 관계를 이 트랜잭션 안에서 넣는다. sort_order 는 요청 배열
   * 순서. 관계는 타입이 모두 들어간 뒤에만 넣을 수 있다(FK).
   */
  @Override
  public long createOntology(CreateOntologyRequest req) {
    OntologyInsert head = new OntologyInsert(req.domain(), req.status());
    mapper.insertOntology(head);
    long ontologyId = head.getId();

    // 관계 삽입에 쓸 이름→id 맵. 타입을 넣으면서 모은다.
    Map<String, Long> idByType = new HashMap<>();
    int etOrder = 0;
    for (var e : req.entities()) {
      EntityTypeInsert et =
          new EntityTypeInsert(
              ontologyId, e.type(), e.description(), e.naming(), e.resolution(), etOrder++);
      mapper.insertEntityType(et);
      idByType.put(e.type(), et.getId());
      int epOrder = 0;
      List<OntologyResponse.Property> props = e.properties() == null ? List.of() : e.properties();
      for (var p : props) {
        mapper.insertProperty(
            new PropertyInsert(
                et.getId(), p.name(), p.description(), p.dataType(), p.unit(), epOrder++));
      }
    }

    int rOrder = 0;
    for (var t : req.relations()) {
      mapper.insertRelation(
          new RelationInsert(
              ontologyId,
              resolveTypeId(idByType, t.subject(), "subject"),
              t.relation(),
              resolveTypeId(idByType, t.object(), "object"),
              t.description(),
              rOrder++));
    }
    return ontologyId;
  }

  /** 관계 끝점 이름→id. 서비스 검증을 우회한 호출이면 FK 위반 500 대신 400 으로 올린다(jOOQ 와 같은 메시지). */
  private static long resolveTypeId(Map<String, Long> idByType, String typeName, String where) {
    Long id = idByType.get(typeName);
    if (id == null) {
      throw new IllegalArgumentException("관계가 존재하지 않는 엔티티 타입을 참조합니다(" + where + "): " + typeName);
    }
    return id;
  }

  /** 상태가 없으면(행 없음) 400 으로 막는다. */
  private static String requireStatus(String status, long ontologyId) {
    if (status == null) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    return status;
  }
}
