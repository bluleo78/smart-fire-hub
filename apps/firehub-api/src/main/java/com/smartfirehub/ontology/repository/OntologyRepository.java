package com.smartfirehub.ontology.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

// 온톨로지 DB 읽기/쓰기 — id로 지정한 온톨로지를 OntologyResponse 계약으로 조립한다(다중 온톨로지 지원).
// 무인자 오버로드(findOntology/currentSchemaVersion)는 기존 단일 온톨로지(id=1) 호출부와의
// 하위호환을 위해 findById(1L) 등으로 위임한다. sort_order 정렬로 ai-agent 프롬프트 조립 순서(바이트 동일성)를
// 보존한다. plain-SQL DSL(생성 클래스 비의존).
// (S2 Task 7) 전체 스키마 교체(entity_type/relation의 매칭 기반 UPDATE/INSERT/DELETE) updateOntology는
// 요소 단위 편집(OntologyElementRepository)으로 대체되어 삭제됐다.
@Repository
@RequiredArgsConstructor
public class OntologyRepository {

  private final DSLContext dsl;

  private static final Table<?> ONTOLOGY = table(name("ontology"));
  private static final Field<String> O_DOMAIN = field(name("ontology", "domain"), String.class);
  private static final Field<Long> O_ID = field(name("ontology", "id"), Long.class);
  private static final Field<Integer> O_SCHEMA_VERSION = field(name("ontology", "schema_version"), Integer.class);
  private static final Field<OffsetDateTime> O_UPDATED_AT = field(name("ontology", "updated_at"), OffsetDateTime.class);
  private static final Field<String> O_STATUS = field(name("ontology", "status"), String.class);

  // 목록 요약의 카운트 조인 대상. dataset_ontology는 바인딩된 데이터셋 수를 센다.
  private static final Table<?> DATASET_ONTOLOGY = table(name("dataset_ontology"));
  private static final Field<Long> DO_ONTOLOGY_ID =
      field(name("dataset_ontology", "ontology_id"), Long.class);

  // 삭제 가능 여부 판정에 쓰는 또 다른 참조원 — 매핑(dataset_mapping)도 이 온톨로지에 묶여 있으면 삭제 불가.
  private static final Table<?> DATASET_MAPPING = table(name("dataset_mapping"));
  private static final Field<Long> DM_ONTOLOGY_ID =
      field(name("dataset_mapping", "ontology_id"), Long.class);
  private static final Field<Long> DO_DATASET_ID =
      field(name("dataset_ontology", "dataset_id"), Long.class);
  private static final Field<Long> DM_DATASET_ID =
      field(name("dataset_mapping", "dataset_id"), Long.class);

  private static final Table<?> ENTITY_TYPE = table(name("ontology_entity_type"));
  private static final Field<Long> ET_ID = field(name("ontology_entity_type", "id"), Long.class);
  private static final Field<Long> ET_ONTOLOGY_ID = field(name("ontology_entity_type", "ontology_id"), Long.class);
  private static final Field<String> ET_TYPE = field(name("ontology_entity_type", "type"), String.class);
  private static final Field<String> ET_DESC = field(name("ontology_entity_type", "description"), String.class);
  private static final Field<String> ET_NAMING = field(name("ontology_entity_type", "naming"), String.class);
  private static final Field<String> ET_RES = field(name("ontology_entity_type", "resolution"), String.class);
  private static final Field<Integer> ET_ORDER = field(name("ontology_entity_type", "sort_order"), Integer.class);

  private static final Table<?> ENTITY_PROP = table(name("ontology_entity_property"));
  private static final Field<Long> EP_TYPE_ID = field(name("ontology_entity_property", "entity_type_id"), Long.class);
  private static final Field<String> EP_NAME = field(name("ontology_entity_property", "name"), String.class);
  private static final Field<String> EP_DESC = field(name("ontology_entity_property", "description"), String.class);
  private static final Field<String> EP_DTYPE = field(name("ontology_entity_property", "data_type"), String.class);
  private static final Field<String> EP_UNIT = field(name("ontology_entity_property", "unit"), String.class);
  private static final Field<Integer> EP_ORDER = field(name("ontology_entity_property", "sort_order"), Integer.class);
  // 요소 단위 편집 API(PATCH/DELETE .../properties/{id})가 속성을 지목하는 안정 id.
  private static final Field<Long> EP_ID = field(name("ontology_entity_property", "id"), Long.class);

  private static final Table<?> RELATION = table(name("ontology_relation"));
  private static final Field<Long> R_ID = field(name("ontology_relation", "id"), Long.class);
  private static final Field<Long> R_ONTOLOGY_ID = field(name("ontology_relation", "ontology_id"), Long.class);
  // V80: subject/object는 타입 "이름"(TEXT)이 아니라 ontology_entity_type.id FK다.
  private static final Field<Long> R_SUBJECT_ID = field(name("ontology_relation", "subject_type_id"), Long.class);
  private static final Field<String> R_RELATION = field(name("ontology_relation", "relation"), String.class);
  private static final Field<Long> R_OBJECT_ID = field(name("ontology_relation", "object_type_id"), Long.class);
  private static final Field<String> R_DESC = field(name("ontology_relation", "description"), String.class);
  private static final Field<Integer> R_ORDER = field(name("ontology_relation", "sort_order"), Integer.class);

  // 지정한 온톨로지를 조회해 OntologyResponse로 조립한다(해당 ontology_id의 타입/관계만).
  public OntologyResponse findById(long ontologyId) {
    var head =
        dsl.select(O_DOMAIN, O_SCHEMA_VERSION).from(ONTOLOGY).where(O_ID.eq(ontologyId)).fetchOne();
    // 존재하지 않는 id는 NPE→500이 아니라 명확한 400(전역 핸들러 규약)으로 차단한다.
    // 신규 라우트 GET /api/v1/ontology/{id}가 임의 id를 받으므로 반드시 필요.
    if (head == null) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    String domain = head.get(O_DOMAIN);
    int schemaVersion = head.get(O_SCHEMA_VERSION);

    List<OntologyResponse.EntityType> entities =
        dsl.select(ET_ID, ET_TYPE, ET_DESC, ET_NAMING, ET_RES)
            .from(ENTITY_TYPE)
            .where(ET_ONTOLOGY_ID.eq(ontologyId)) // ← 다중 온톨로지: 자기 타입만
            .orderBy(ET_ORDER)
            .fetch(r -> {
              // 각 엔티티 타입의 데이터 프로퍼티를 sort_order 순으로 조회한다.
              List<OntologyResponse.Property> props =
                  dsl.select(EP_NAME, EP_DESC, EP_DTYPE, EP_UNIT, EP_ID)
                      .from(ENTITY_PROP)
                      .where(EP_TYPE_ID.eq(r.get(ET_ID)))
                      .orderBy(EP_ORDER)
                      .fetch(p -> new OntologyResponse.Property(
                          p.get(EP_NAME), p.get(EP_DESC), p.get(EP_DTYPE), p.get(EP_UNIT), p.get(EP_ID)));
              return new OntologyResponse.EntityType(
                  r.get(ET_TYPE), r.get(ET_DESC), r.get(ET_NAMING), r.get(ET_RES), props, r.get(ET_ID));
            });

    // V80 이후 관계는 타입 id를 들고 있다. 읽기 계약(OntologyResponse.Triple)은 이름을 유지해야 하므로
    // 위에서 이미 조회한 entities로 id→이름 맵을 만들어 되붙인다. 셀프 조인 2회보다 싸고 읽기 쉽다.
    Map<Long, String> typeNameById = new HashMap<>();
    for (var e : entities) {
      typeNameById.put(e.id(), e.type());
    }

    List<OntologyResponse.Triple> relations =
        dsl.select(R_ID, R_SUBJECT_ID, R_RELATION, R_OBJECT_ID, R_DESC)
            .from(RELATION)
            .where(R_ONTOLOGY_ID.eq(ontologyId)) // ← 다중 온톨로지: 자기 관계만
            .orderBy(R_ORDER)
            .fetch(r -> new OntologyResponse.Triple(
                typeNameById.get(r.get(R_SUBJECT_ID)),
                r.get(R_RELATION),
                typeNameById.get(r.get(R_OBJECT_ID)),
                r.get(R_DESC),
                r.get(R_ID),
                r.get(R_SUBJECT_ID),
                r.get(R_OBJECT_ID)));

    return new OntologyResponse(domain, schemaVersion, entities, relations);
  }

  // 하위호환 — 기존 단일 온톨로지 호출부(문서 파이프라인 프록시 라우트)는 기본 화재조사(id=1)를 본다.
  public OntologyResponse findOntology() {
    return findById(1L);
  }

  // 온톨로지 목록(요약). id 순. statusFilter가 null이면 전체, 아니면 해당 상태만.
  // 엔티티 수와 바인딩된 데이터셋 수는 상관 서브쿼리로 센다 — 온톨로지 행 수가 한 자릿수 규모라
  // 조인 폭발 걱정이 없고, GROUP BY보다 읽기 쉽다.
  public List<OntologySummary> findAllSummaries(String statusFilter) {
    var entityCount =
        field(
            selectCount().from(ENTITY_TYPE).where(ET_ONTOLOGY_ID.eq(O_ID)));
    var datasetCount =
        field(
            selectCount().from(DATASET_ONTOLOGY).where(DO_ONTOLOGY_ID.eq(O_ID)));

    var condition = statusFilter == null ? noCondition() : O_STATUS.eq(statusFilter);

    return dsl
        .select(O_ID, O_DOMAIN, O_SCHEMA_VERSION, O_STATUS, entityCount, datasetCount, O_UPDATED_AT)
        .from(ONTOLOGY)
        .where(condition)
        .orderBy(O_ID)
        .fetch(
            r ->
                new OntologySummary(
                    r.get(O_ID),
                    r.get(O_DOMAIN),
                    r.get(O_SCHEMA_VERSION),
                    r.get(O_STATUS),
                    r.get(entityCount),
                    r.get(datasetCount),
                    r.get(O_UPDATED_AT),
                    false)); // isDefault는 리포지토리가 판정하지 않는다 — OntologyService가 채워 넣는다.
  }

  // 하위호환 — 인자 없는 호출은 전체 목록.
  public List<OntologySummary> findAllSummaries() {
    return findAllSummaries(null);
  }

  // 살아있는(archived 아님) 온톨로지 중 같은 도메인이 이미 있는지 — 생성 시 사전 중복 검사용.
  // V79 부분 유니크 인덱스(status <> 'archived')와 정합해야 한다 — archived는 이름을 선점하지 않으므로
  // 여기서도 제외해야, 은퇴한 온톨로지와 같은 이름의 후속 온톨로지를 만들 수 있다는 전제가 깨지지 않는다.
  public boolean existsLiveDomain(String domain) {
    return dsl.fetchExists(
        dsl.selectOne().from(ONTOLOGY).where(O_DOMAIN.eq(domain)).and(O_STATUS.ne("archived")));
  }

  // 상태만 경량 조회 — 바인딩/활성화 가드가 본문 없이 상태만 확인할 때 쓴다.
  public String findStatusById(long ontologyId) {
    var record = dsl.select(O_STATUS).from(ONTOLOGY).where(O_ID.eq(ontologyId)).fetchOne();
    if (record == null) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    return record.get(O_STATUS);
  }

  // 상태만 전이시킨다. 스키마 내용을 건드리지 않으므로 schema_version은 올리지 않는다
  // (버전은 "스키마가 몇 번 바뀌었나"를 뜻하고, 적재 노드의 schemaVersion 스탬프와 짝을 이룬다).
  public void updateStatus(long ontologyId, String status) {
    dsl.update(ONTOLOGY)
        .set(O_STATUS, status)
        .set(O_UPDATED_AT, currentOffsetDateTime())
        .where(O_ID.eq(ontologyId))
        .execute();
  }

  // 이 온톨로지를 참조하는 데이터셋 수(distinct) — 바인딩(dataset_ontology) ∪ 매핑(dataset_mapping).
  // 삭제 가능 여부의 유일한 판정 근거다(상태는 판정에 쓰지 않는다).
  // 단순 합산이 아니라 dataset_id 기준 UNION(중복 제거)이어야 한다 — MappingService.save가 ontologyId를
  // 바인딩에서 파생시키므로, 매핑이 있는 데이터셋은 반드시 같은 dataset_ontology 행도 함께 있다. 합산하면
  // 매핑까지 만든 데이터셋 1개가 2로 집계돼 관리 다이얼로그의 datasetCount(바인딩만)와 어긋난다.
  public int countReferences(long ontologyId) {
    return dsl.fetchCount(
        dsl.select(DO_DATASET_ID).from(DATASET_ONTOLOGY).where(DO_ONTOLOGY_ID.eq(ontologyId))
            .union(dsl.select(DM_DATASET_ID).from(DATASET_MAPPING).where(DM_ONTOLOGY_ID.eq(ontologyId))));
  }

  // 온톨로지 삭제. entity_type/relation/property는 ON DELETE CASCADE로 함께 지워진다.
  public void deleteOntology(long ontologyId) {
    dsl.deleteFrom(ONTOLOGY).where(O_ID.eq(ontologyId)).execute();
  }

  // 온톨로지 존재 여부(바인딩 검증용).
  public boolean existsById(long ontologyId) {
    return dsl.fetchExists(dsl.selectOne().from(ONTOLOGY).where(O_ID.eq(ontologyId)));
  }

  // stale 질의용 경량 조회 — 온톨로지 본문 없이 schema_version 만 확인한다(Task 3: dataset_graph_ingest 재사용 여부 판단).
  public int currentSchemaVersion(long ontologyId) {
    var record = dsl.select(O_SCHEMA_VERSION).from(ONTOLOGY).where(O_ID.eq(ontologyId)).fetchOne();
    // 존재하지 않는 id는 NPE→500이 아니라 findById와 동일하게 명확한 400(전역 핸들러 규약)으로 차단한다.
    if (record == null) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    return record.get(O_SCHEMA_VERSION);
  }

  // 하위호환 — id=1 위임.
  public int currentSchemaVersion() {
    return currentSchemaVersion(1L);
  }

  // 관계 삽입용 이름→id 해석(createOntology 전용). 관계는 V80 이후 FK를 요구하는데 쓰기 계약
  // (CreateOntologyRequest)은 여전히 이름을 담고 있어, 저장 직전에 한 번 변환해야 한다.
  // 매칭 실패는 IllegalArgumentException으로 올린다 — OntologyService.validateCore가 이미
  // 참조 무결성을 검사하므로 여기 도달했다면 검증을 우회한 호출이고, FK 위반 500보다 400이 낫다.
  private static long resolveTypeId(Map<String, Long> idByType, String typeName, String where) {
    Long id = idByType.get(typeName);
    if (id == null) {
      throw new IllegalArgumentException(
          "관계가 존재하지 않는 엔티티 타입을 참조합니다(" + where + "): " + typeName);
    }
    return id;
  }

  // 신규 도메인 온톨로지 생성 — ontology 행(schema_version=1) + entity_type + relation을 원자 삽입.
  // id는 IDENTITY(V77)로 자동 발급되어 반환된다. sort_order는 요청 배열 순서로 매긴다.
  public long createOntology(CreateOntologyRequest req) {
    return dsl.transactionResult(cfg -> {
      DSLContext tx = using(cfg);
      long ontologyId =
          tx.insertInto(ONTOLOGY)
              .set(O_DOMAIN, req.domain())
              .set(O_SCHEMA_VERSION, 1)
              .set(O_STATUS, req.status())
              .set(O_UPDATED_AT, currentOffsetDateTime())
              .returning(O_ID)
              .fetchOne()
              .get(O_ID);

      // 관계 삽입에 쓸 이름→id 맵. 엔티티 타입을 넣으면서 함께 모은다(별도 재조회 불필요).
      Map<String, Long> idByType = new HashMap<>();
      int etOrder = 0;
      for (var e : req.entities()) {
        long entityTypeId =
            tx.insertInto(ENTITY_TYPE)
                .set(ET_ONTOLOGY_ID, ontologyId)
                .set(ET_TYPE, e.type())
                .set(ET_DESC, e.description())
                .set(ET_NAMING, e.naming())
                .set(ET_RES, e.resolution())
                .set(ET_ORDER, etOrder++)
                .returning(ET_ID)
                .fetchOne()
                .get(ET_ID);
        idByType.put(e.type(), entityTypeId);
        int epOrder = 0;
        List<OntologyResponse.Property> props = e.properties() == null ? List.of() : e.properties();
        for (var p : props) {
          tx.insertInto(ENTITY_PROP)
              .set(EP_TYPE_ID, entityTypeId)
              .set(EP_NAME, p.name())
              .set(EP_DESC, p.description())
              .set(EP_DTYPE, p.dataType())
              .set(EP_UNIT, p.unit())
              .set(EP_ORDER, epOrder++)
              .execute();
        }
      }

      // 관계는 엔티티 타입이 모두 삽입된 뒤에만 넣을 수 있다(FK).
      int rOrder = 0;
      for (var t : req.relations()) {
        tx.insertInto(RELATION)
            .set(R_ONTOLOGY_ID, ontologyId)
            .set(R_SUBJECT_ID, resolveTypeId(idByType, t.subject(), "subject"))
            .set(R_RELATION, t.relation())
            .set(R_OBJECT_ID, resolveTypeId(idByType, t.object(), "object"))
            .set(R_DESC, t.description())
            .set(R_ORDER, rOrder++)
            .execute();
      }
      return ontologyId;
    });
  }

}
