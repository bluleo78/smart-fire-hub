package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// V71 시드 검증 — CORE_ONTOLOGY 원본이 순서·문자열 그대로 적재됐는지 확인한다(바이트 동일성 보증의 DB 측 근거).
class OntologyMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  private static final Table<?> ET = table(name("ontology_entity_type"));
  private static final Field<String> ET_TYPE = field(name("ontology_entity_type", "type"), String.class);
  private static final Field<String> ET_RES = field(name("ontology_entity_type", "resolution"), String.class);
  private static final Field<Integer> ET_ORDER = field(name("ontology_entity_type", "sort_order"), Integer.class);
  private static final Table<?> REL = table(name("ontology_relation"));
  private static final Field<String> REL_RELATION = field(name("ontology_relation", "relation"), String.class);
  private static final Field<Integer> REL_ORDER = field(name("ontology_relation", "sort_order"), Integer.class);

  @Test
  void 시드_엔티티는_원본_순서로_6개_적재된다() {
    List<String> types = dsl.select(ET_TYPE).from(ET).orderBy(ET_ORDER).fetch(r -> r.get(ET_TYPE));
    assertThat(types).containsExactly("Incident", "Building", "Cause", "Damage", "Equipment", "Regulation");
    // resolution 정책 6종 전부 순서대로 검증한다. resolution 은 추출 프롬프트에 실리지 않아
    // "프롬프트 바이트 동일" 회귀가 커버하지 못하므로(오직 semantic-resolver 병합 정책에만 영향),
    // 여기서 Incident/Damage=exact, 나머지=embedding 을 명시적으로 단언해 시드 오류를 잡는다.
    List<String> resolutions = dsl.select(ET_RES).from(ET).orderBy(ET_ORDER).fetch(r -> r.get(ET_RES));
    assertThat(resolutions).containsExactly("exact", "embedding", "embedding", "exact", "embedding", "embedding");
  }

  @Test
  void 시드_관계는_원본_순서로_6개_적재된다() {
    List<String> rels = dsl.select(REL_RELATION).from(REL).orderBy(REL_ORDER).fetch(r -> r.get(REL_RELATION));
    assertThat(rels).containsExactly(
        "OCCURRED_AT", "CAUSED_BY", "RESULTED_IN", "HAS_EQUIPMENT", "VIOLATED", "GOVERNED_BY");
  }
}
