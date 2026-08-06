package com.smartfirehub.ontology.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

// 온톨로지 DB 읽기/쓰기 — id로 지정한 온톨로지를 OntologyResponse 계약으로 조립한다(다중 온톨로지 지원).
// 무인자 오버로드(findOntology/currentSchemaVersion/updateOntology)는 기존 단일 온톨로지(id=1) 호출부와의
// 하위호환을 위해 findById(1L) 등으로 위임한다. sort_order 정렬로 ai-agent 프롬프트 조립 순서(바이트 동일성)를
// 보존한다. plain-SQL DSL(생성 클래스 비의존).
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

  private static final Table<?> RELATION = table(name("ontology_relation"));
  private static final Field<Long> R_ONTOLOGY_ID = field(name("ontology_relation", "ontology_id"), Long.class);
  private static final Field<String> R_SUBJECT = field(name("ontology_relation", "subject"), String.class);
  private static final Field<String> R_RELATION = field(name("ontology_relation", "relation"), String.class);
  private static final Field<String> R_OBJECT = field(name("ontology_relation", "object"), String.class);
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
                  dsl.select(EP_NAME, EP_DESC, EP_DTYPE, EP_UNIT)
                      .from(ENTITY_PROP)
                      .where(EP_TYPE_ID.eq(r.get(ET_ID)))
                      .orderBy(EP_ORDER)
                      .fetch(p -> new OntologyResponse.Property(
                          p.get(EP_NAME), p.get(EP_DESC), p.get(EP_DTYPE), p.get(EP_UNIT)));
              return new OntologyResponse.EntityType(
                  r.get(ET_TYPE), r.get(ET_DESC), r.get(ET_NAMING), r.get(ET_RES), props, r.get(ET_ID));
            });

    List<OntologyResponse.Triple> relations =
        dsl.select(R_SUBJECT, R_RELATION, R_OBJECT, R_DESC)
            .from(RELATION)
            .where(R_ONTOLOGY_ID.eq(ontologyId)) // ← 다중 온톨로지: 자기 관계만
            .orderBy(R_ORDER)
            .fetch(r -> new OntologyResponse.Triple(
                r.get(R_SUBJECT), r.get(R_RELATION), r.get(R_OBJECT), r.get(R_DESC)));

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

      int rOrder = 0;
      for (var t : req.relations()) {
        tx.insertInto(RELATION)
            .set(R_ONTOLOGY_ID, ontologyId)
            .set(R_SUBJECT, t.subject())
            .set(R_RELATION, t.relation())
            .set(R_OBJECT, t.object())
            .set(R_DESC, t.description())
            .set(R_ORDER, rOrder++)
            .execute();
      }
      return ontologyId;
    });
  }

  // 단일 온톨로지(id=1) 전체를 교체하는 full-document 편집(B-2b). 단일 트랜잭션 원자성:
  // ① 낙관적 잠금 + 버전 증가(기대 버전과 일치할 때만) ② relation은 전량 재작성(id 안정성 불필요 —
  // subject/object는 문자열 참조라 FK 없음) ③ entity_type은 매칭 기반 UPDATE/INSERT/DELETE로 id 보존(5-6).
  // 반환값 = 증가된 새 schema_version. 기대 버전 불일치 시 IllegalStateException → 409(전역 핸들러 규약).
  public int updateOntology(long ontologyId, UpdateOntologyRequest req) {
    return dsl.transactionResult(cfg -> {
      DSLContext tx = using(cfg);

      // ① 낙관적 잠금 + 버전 증가 + domain 갱신. 기대 버전과 일치할 때만 1행 갱신된다.
      int updated =
          tx.update(ONTOLOGY)
              .set(O_DOMAIN, req.domain())
              .set(O_SCHEMA_VERSION, O_SCHEMA_VERSION.plus(1))
              .set(O_UPDATED_AT, currentOffsetDateTime())
              .where(O_ID.eq(ontologyId).and(O_SCHEMA_VERSION.eq(req.schemaVersion())))
              .execute();
      if (updated == 0) {
        // 기대 버전 불일치 = 다른 사용자가 먼저 수정(또는 잘못된 버전). 트랜잭션 롤백 → 자식 변경 없음.
        throw new IllegalStateException(
            "지식 모델이 다른 사용자에 의해 이미 수정되었습니다. 새로고침 후 다시 시도하세요.");
      }

      // ② relation은 전량 재작성(기존과 동일 — subject/object는 문자열 참조라 entity_type_id와 무관).
      tx.deleteFrom(RELATION).where(R_ONTOLOGY_ID.eq(ontologyId)).execute();
      int rOrder = 0;
      for (var t : req.relations()) {
        tx.insertInto(RELATION)
            .set(R_ONTOLOGY_ID, ontologyId)
            .set(R_SUBJECT, t.subject())
            .set(R_RELATION, t.relation())
            .set(R_OBJECT, t.object())
            .set(R_DESC, t.description())
            .set(R_ORDER, rOrder++)
            .execute();
      }

      // ③ entity_type — 매칭 기반 UPDATE/INSERT/DELETE(5-6: entity_type_id를 시간축에서 안정적으로
      // 보존해 ai-agent가 Neo4j 노드 key를 이 id 기반으로 구성할 수 있게 한다. 타입명이 바뀌어도(리네임)
      // 같은 행을 UPDATE하므로 id가 그대로 유지되고, Neo4j는 더 이상 마이그레이션할 필요가 없다).
      // 매칭 규칙: (a) 기존 타입명과 그대로 같으면 그 행 (b) req.renames()의 from→to 힌트가 가리키는
      // 기존 행 (c) 매칭 안 되면 신규(새 id 발급). 매칭 안 된 기존 행은 삭제 대상(사용자가 지운 타입).
      Map<String, Long> existingIdByType =
          tx.select(ET_TYPE, ET_ID).from(ENTITY_TYPE).where(ET_ONTOLOGY_ID.eq(ontologyId))
              .fetch().intoMap(r -> r.get(ET_TYPE), r -> r.get(ET_ID));
      // 이름 기반 renames의 한계: to가 중복되면 앞 항목이 덮어써져 그 from 행이 아래에서 DELETE된다.
      // 구조적 해소는 id 기반 재설계(#304)의 몫이고, 그 전까지는 OntologyService.validate의
      // to/from 중복 검사가 유일한 방어선이다(#306).
      Map<String, String> renameToFrom = new HashMap<>();
      for (var rename : req.renames()) {
        renameToFrom.put(rename.to(), rename.from());
      }

      // 최종 목록의 각 엔티티가 매칭되는 기존 id(있다면)를 먼저 전부 계산한다(DB 쓰기 전, 순수 조회).
      List<Long> matchedIds = new java.util.ArrayList<>();
      for (var e : req.entities()) {
        Long matchedId = existingIdByType.get(e.type());
        if (matchedId == null) {
          String fromName = renameToFrom.get(e.type());
          if (fromName != null) matchedId = existingIdByType.get(fromName);
        }
        matchedIds.add(matchedId);
      }

      // 매칭 안 된 기존 행(=사용자가 삭제한 타입)을 먼저 삭제한다 — 리네임이 삭제된 타입의 옛 이름을
      // 재사용하는 경우(예: "B" 삭제 + "A"를 "B"로 리네임) UPDATE보다 먼저 지워야 UNIQUE(ontology_id,type)
      // 제약의 트랜잭션 내 순간 충돌을 피한다.
      Set<Long> keptIds = new HashSet<>(matchedIds.stream().filter(java.util.Objects::nonNull).toList());
      List<Long> removedIds =
          existingIdByType.values().stream().filter(id -> !keptIds.contains(id)).toList();
      if (!removedIds.isEmpty()) {
        tx.deleteFrom(ENTITY_TYPE).where(ET_ID.in(removedIds)).execute(); // property는 CASCADE 삭제.
      }

      int etOrder = 0;
      for (int i = 0; i < req.entities().size(); i++) {
        var e = req.entities().get(i);
        Long matchedId = matchedIds.get(i);
        long entityTypeId;
        if (matchedId != null) {
          tx.update(ENTITY_TYPE)
              .set(ET_TYPE, e.type())
              .set(ET_DESC, e.description())
              .set(ET_NAMING, e.naming())
              .set(ET_RES, e.resolution())
              .set(ET_ORDER, etOrder++)
              .where(ET_ID.eq(matchedId))
              .execute();
          entityTypeId = matchedId;
        } else {
          entityTypeId =
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
        }

        // 속성은 id 안정성이 필요 없으므로 기존과 동일하게 해당 entityTypeId 기준 delete-then-reinsert.
        tx.deleteFrom(ENTITY_PROP).where(EP_TYPE_ID.eq(entityTypeId)).execute();
        int epOrder = 0;
        List<OntologyResponse.Property> props =
            e.properties() == null ? List.of() : e.properties();
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

      return req.schemaVersion() + 1;
    });
  }

  // 하위호환 — 기존 단일 온톨로지 편집 호출부(테스트 snapshot/restore, 레거시 PUT)는 id=1을 편집.
  public int updateOntology(UpdateOntologyRequest req) {
    return updateOntology(1L, req);
  }
}
