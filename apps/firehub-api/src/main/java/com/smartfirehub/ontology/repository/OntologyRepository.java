package com.smartfirehub.ontology.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.ontology.dto.OntologyResponse;
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

// 온톨로지 DB 읽기 — 단일 온톨로지(id=1)를 OntologyResponse 계약으로 조립한다.
// sort_order 정렬로 ai-agent 프롬프트 조립 순서(바이트 동일성)를 보존한다. plain-SQL DSL(생성 클래스 비의존).
@Repository
@RequiredArgsConstructor
public class OntologyRepository {

  private final DSLContext dsl;

  private static final Table<?> ONTOLOGY = table(name("ontology"));
  private static final Field<String> O_DOMAIN = field(name("ontology", "domain"), String.class);
  private static final Field<Long> O_ID = field(name("ontology", "id"), Long.class);
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

  // 단일 온톨로지(id=1) 를 조회해 OntologyResponse 로 조립한다.
  public OntologyResponse findOntology() {
    // domain, schema_version 을 한 번에 조회한다.
    var head = dsl.select(O_DOMAIN, O_SCHEMA_VERSION).from(ONTOLOGY).where(O_ID.eq(1L)).fetchOne();
    String domain = head.get(O_DOMAIN);
    int schemaVersion = head.get(O_SCHEMA_VERSION);

    List<OntologyResponse.EntityType> entities =
        dsl.select(ET_ID, ET_TYPE, ET_DESC, ET_NAMING, ET_RES)
            .from(ENTITY_TYPE)
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
            .orderBy(R_ORDER)
            .fetch(r -> new OntologyResponse.Triple(
                r.get(R_SUBJECT), r.get(R_RELATION), r.get(R_OBJECT), r.get(R_DESC)));

    return new OntologyResponse(domain, schemaVersion, entities, relations);
  }

  // stale 질의용 경량 조회 — 온톨로지 본문 없이 schema_version 만 확인한다(Task 3: dataset_graph_ingest 재사용 여부 판단).
  public int currentSchemaVersion() {
    return dsl.select(O_SCHEMA_VERSION).from(ONTOLOGY).where(O_ID.eq(1L)).fetchOne(r -> r.get(O_SCHEMA_VERSION));
  }

  // 단일 온톨로지(id=1) 전체를 교체하는 full-document 편집(B-2b). 단일 트랜잭션 원자성:
  // ① 낙관적 잠금 + 버전 증가(기대 버전과 일치할 때만) ② relation은 전량 재작성(id 안정성 불필요 —
  // subject/object는 문자열 참조라 FK 없음) ③ entity_type은 매칭 기반 UPDATE/INSERT/DELETE로 id 보존(5-6).
  // 반환값 = 증가된 새 schema_version. 기대 버전 불일치 시 IllegalStateException → 409(전역 핸들러 규약).
  public int updateOntology(UpdateOntologyRequest req) {
    return dsl.transactionResult(cfg -> {
      DSLContext tx = using(cfg);

      // ① 낙관적 잠금 + 버전 증가 + domain 갱신. 기대 버전과 일치할 때만 1행 갱신된다.
      int updated =
          tx.update(ONTOLOGY)
              .set(O_DOMAIN, req.domain())
              .set(O_SCHEMA_VERSION, O_SCHEMA_VERSION.plus(1))
              .set(O_UPDATED_AT, currentOffsetDateTime())
              .where(O_ID.eq(1L).and(O_SCHEMA_VERSION.eq(req.schemaVersion())))
              .execute();
      if (updated == 0) {
        // 기대 버전 불일치 = 다른 사용자가 먼저 수정(또는 잘못된 버전). 트랜잭션 롤백 → 자식 변경 없음.
        throw new IllegalStateException(
            "지식 모델이 다른 사용자에 의해 이미 수정되었습니다. 새로고침 후 다시 시도하세요.");
      }

      // ② relation은 전량 재작성(기존과 동일 — subject/object는 문자열 참조라 entity_type_id와 무관).
      tx.deleteFrom(RELATION).where(R_ONTOLOGY_ID.eq(1L)).execute();
      int rOrder = 0;
      for (var t : req.relations()) {
        tx.insertInto(RELATION)
            .set(R_ONTOLOGY_ID, 1L)
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
          tx.select(ET_TYPE, ET_ID).from(ENTITY_TYPE).where(ET_ONTOLOGY_ID.eq(1L))
              .fetch().intoMap(r -> r.get(ET_TYPE), r -> r.get(ET_ID));
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
                  .set(ET_ONTOLOGY_ID, 1L)
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
}
