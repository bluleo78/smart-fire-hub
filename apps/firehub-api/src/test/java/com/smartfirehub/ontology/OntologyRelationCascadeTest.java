package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

// V80: 엔티티 타입 삭제 시 FK CASCADE로 관계가 함께 삭제되는지 검증한다.
// 시드 데이터를 실제로 삭제하는 테스트라 다른 테스트의 시드를 훼손하지 않도록
// @Transactional로 감싸 테스트 종료 시 자동 롤백시킨다(IntegrationTestBase는 이를 보장하지 않는다).
@Transactional
class OntologyRelationCascadeTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  private static final Table<?> ET = table(name("ontology_entity_type"));
  private static final Field<String> ET_TYPE = field(name("ontology_entity_type", "type"), String.class);
  private static final Field<Long> ET_ID = field(name("ontology_entity_type", "id"), Long.class);
  private static final Table<?> REL = table(name("ontology_relation"));
  private static final Field<Long> REL_SUBJ_ID = field(name("ontology_relation", "subject_type_id"), Long.class);
  private static final Field<Long> REL_OBJ_ID = field(name("ontology_relation", "object_type_id"), Long.class);

  // V80: 엔티티 타입을 지우면 그 타입을 참조하던 관계도 DB(FK CASCADE)가 함께 지운다.
  // 애플리케이션 코드가 선제 정리하지 않아도 참조 무결성이 유지되는지가 이 테스트의 관심사다.
  @Test
  void V80_엔티티_타입_삭제시_참조_관계가_FK_CASCADE로_함께_삭제된다() {
    Long equipmentId =
        dsl.select(ET_ID).from(ET).where(ET_TYPE.eq("Equipment")).fetchOne().get(ET_ID);
    // Equipment는 HAS_EQUIPMENT(object)와 GOVERNED_BY(subject) 2건에 참여한다.
    assertThat(countRelationsTouching(equipmentId)).isEqualTo(2);

    dsl.deleteFrom(ET).where(ET_ID.eq(equipmentId)).execute();

    assertThat(countRelationsTouching(equipmentId)).isZero();
  }

  private int countRelationsTouching(Long entityTypeId) {
    return dsl.fetchCount(
        dsl.selectOne().from(REL)
            .where(REL_SUBJ_ID.eq(entityTypeId).or(REL_OBJ_ID.eq(entityTypeId))));
  }
}
