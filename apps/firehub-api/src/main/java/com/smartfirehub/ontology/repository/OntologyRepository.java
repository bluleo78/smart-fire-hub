package com.smartfirehub.ontology.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.ontology.dto.OntologyResponse;
import java.util.List;
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

  private static final Table<?> ENTITY_TYPE = table(name("ontology_entity_type"));
  private static final Field<Long> ET_ID = field(name("ontology_entity_type", "id"), Long.class);
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
  private static final Field<String> R_SUBJECT = field(name("ontology_relation", "subject"), String.class);
  private static final Field<String> R_RELATION = field(name("ontology_relation", "relation"), String.class);
  private static final Field<String> R_OBJECT = field(name("ontology_relation", "object"), String.class);
  private static final Field<String> R_DESC = field(name("ontology_relation", "description"), String.class);
  private static final Field<Integer> R_ORDER = field(name("ontology_relation", "sort_order"), Integer.class);

  // 단일 온톨로지(id=1) 를 조회해 OntologyResponse 로 조립한다.
  public OntologyResponse findOntology() {
    String domain = dsl.select(O_DOMAIN).from(ONTOLOGY).where(O_ID.eq(1L)).fetchOne(r -> r.get(O_DOMAIN));

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
                  r.get(ET_TYPE), r.get(ET_DESC), r.get(ET_NAMING), r.get(ET_RES), props);
            });

    List<OntologyResponse.Triple> relations =
        dsl.select(R_SUBJECT, R_RELATION, R_OBJECT, R_DESC)
            .from(RELATION)
            .orderBy(R_ORDER)
            .fetch(r -> new OntologyResponse.Triple(
                r.get(R_SUBJECT), r.get(R_RELATION), r.get(R_OBJECT), r.get(R_DESC)));

    return new OntologyResponse(domain, entities, relations);
  }
}
