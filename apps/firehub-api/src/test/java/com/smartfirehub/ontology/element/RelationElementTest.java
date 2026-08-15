package com.smartfirehub.ontology.element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import com.smartfirehub.ontology.exception.OntologyElementNotFoundException;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

// 관계(relation) 요소 CRUD 테스트. 픽스처(Sensor/Building + Sensor-INSTALLED_IN->Building draft
// 온톨로지)는 OntologyElementTestSupport가 소유한다.
class RelationElementTest extends OntologyElementTestSupport {

  @Test
  void 관계를_추가하면_id와_이름_양쪽을_담아_반환하고_버전을_올린다() {
    int before = ontologyRepository.findById(ontologyId).schemaVersion();

    var result = elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Building"), "CONTAINS", typeId("Sensor"), "건물이 품은 센서"));

    assertThat(result.relation().id()).isNotNull();
    assertThat(result.relation().subject()).isEqualTo("Building");
    assertThat(result.relation().object()).isEqualTo("Sensor");
    assertThat(result.relation().subjectTypeId()).isEqualTo(typeId("Building"));
    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    OntologyResponse after = ontologyRepository.findById(ontologyId);
    assertThat(after.relations()).hasSize(2);
    assertThat(after.schemaVersion()).isEqualTo(before + 1);
  }

  @Test
  void 관계는_추가된_순서대로_맨_뒤에_쌓인다() {
    // 픽스처가 이미 Sensor-INSTALLED_IN->Building 하나를 갖고 있다. 정렬 규칙(max+1)이
    // 깨져 항상 0이 되면 새 관계가 맨 앞으로 튀어도 이 단언은 통과하지 못한다.
    elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "NEAR", typeId("Building"), "근접"));
    elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Building"), "CONTAINS", typeId("Sensor"), "포함"));

    assertThat(ontologyRepository.findById(ontologyId).relations())
        .extracting(OntologyResponse.Triple::relation)
        .containsExactly("INSTALLED_IN", "NEAR", "CONTAINS");
  }

  @Test
  void 삭제로_생긴_빈_번호는_메우지_않고_새_관계는_계속_맨_뒤에_붙는다() {
    // 위 정렬 테스트는 픽스처가 sort_order=0을 이미 차지해 max+1이 0으로 퇴화해도
    // (모든 행이 sort_order=0) seq scan의 힙 순서가 우연히 삽입 순서와 같아 통과할 수 있다.
    // 가운데 행을 지워 번호에 구멍을 낸 뒤 값으로 직접 단언해야 "메우지 않는다"는
    // 규칙 자체가 검증된다 — max+1이면 새 관계가 항상 맨 뒤, 구멍 재사용이면 아니다.
    var near = elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "NEAR", typeId("Building"), "근접"));
    elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Building"), "CONTAINS", typeId("Sensor"), "포함"));
    // 세 관계(INSTALLED_IN, NEAR, CONTAINS) 중 가운데(NEAR)를 지워 번호에 구멍을 낸다.
    elementService.deleteRelation(ontologyId, near.relation().id());

    elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "MONITORS", typeId("Building"), "감시"));

    assertThat(ontologyRepository.findById(ontologyId).relations())
        .extracting(OntologyResponse.Triple::relation)
        .containsExactly("INSTALLED_IN", "CONTAINS", "MONITORS");

    // 위 단언은 결과 "순서"만 본다 — ORDER BY sort_order는 값이 전부 같아지면 동점 순서를
    // 보장하지 않으므로(Postgres), sort_order가 실제로 전부 0으로 퇴화하는 회귀는 이 단언만으로는
    // 우연히 통과할 수 있다(작은 테이블에서 seq scan이 삽입 순서를 그대로 돌려주는 경우가 흔하다).
    // sort_order 컬럼 값 자체를 읽어 "메우지 않고 단조 증가"함을 직접 확정한다.
    List<Integer> orders = dsl.select(field(name("ontology_relation", "sort_order"), Integer.class))
        .from(table(name("ontology_relation")))
        .where(field(name("ontology_relation", "ontology_id"), Long.class).eq(ontologyId))
        .orderBy(field(name("ontology_relation", "sort_order"), Integer.class))
        .fetch(r -> r.value1());

    assertThat(orders).doesNotHaveDuplicates();
    for (int i = 1; i < orders.size(); i++) {
      assertThat(orders.get(i)).isGreaterThan(orders.get(i - 1));
    }
  }

  @Test
  void subject나_object_타입_id가_없으면_거부된다() {
    assertThatThrownBy(() -> elementService.addRelation(ontologyId,
        new CreateRelationRequest(null, "REL", typeId("Building"), "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("관계의 subject/object 타입 id는 필수입니다.");

    assertThatThrownBy(() -> elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "REL", null, "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("관계의 subject/object 타입 id는 필수입니다.");
  }

  @Test
  void 빈_관계명은_중복이_아니라_빈_이름으로_진단된다() {
    assertThatThrownBy(() -> elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "  ", typeId("Building"), "x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("관계명은 비어 있을 수 없습니다: Sensor → Building");
  }

  // (Task 7 리뷰 I-1) description은 NOT NULL 컬럼(#305) — null이 그대로 INSERT되면 제약 위반 500이
  // 새어나간다. OntologyRules.validateRelationDescription이 요소 경로(addRelation)에서도 이 규칙을
  // 막는지 고정한다.
  @Test
  void null_description을_가진_관계_추가는_거부된다() {
    assertThatThrownBy(() -> elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "NEAR", typeId("Building"), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("관계 설명(description)은 null일 수 없습니다");
  }

  @Test
  void 같은_트리플_중복_추가는_거부된다() {
    assertThatThrownBy(() -> elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "INSTALLED_IN", typeId("Building"), "중복")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("중복된 관계: Sensor|INSTALLED_IN|Building");
  }

  @Test
  void 다른_온톨로지의_타입을_끝점으로_쓰면_거부된다() {
    long otherOntologyId = ontologyService.createOntology(new CreateOntologyRequest(
        "남의온톨로지-" + System.nanoTime(),
        List.of(new OntologyResponse.EntityType("Foreign", "x", "y", "exact", List.of())),
        List.of(), "draft"));
    try {
      long foreignTypeId = ontologyRepository.findById(otherOntologyId).entities().get(0).id();

      assertThatThrownBy(() -> elementService.addRelation(ontologyId,
          new CreateRelationRequest(typeId("Sensor"), "REL", foreignTypeId, "x")))
          .isInstanceOf(OntologyElementNotFoundException.class)
          .hasMessageContaining("존재하지 않는 엔티티 타입입니다");
    } finally {
      OntologyTestSupport.deleteRowAsDefaultTenant(tx, dsl, otherOntologyId);
    }
  }

  @Test
  void 다른_온톨로지의_타입을_subject로_쓰면_거부된다() {
    // 위 테스트는 object 끝점만 검증한다 — subject 쪽 requireType 호출도 같은 방어선이므로
    // 따로 커버해야 한다(리뷰 지적: 삭제해도 어떤 테스트도 실패하지 않던 갭).
    long otherOntologyId = ontologyService.createOntology(new CreateOntologyRequest(
        "남의온톨로지-subject-" + System.nanoTime(),
        List.of(new OntologyResponse.EntityType("Foreign", "x", "y", "exact", List.of())),
        List.of(), "draft"));
    try {
      long foreignTypeId = ontologyRepository.findById(otherOntologyId).entities().get(0).id();

      assertThatThrownBy(() -> elementService.addRelation(ontologyId,
          new CreateRelationRequest(foreignTypeId, "REL", typeId("Sensor"), "x")))
          .isInstanceOf(OntologyElementNotFoundException.class)
          .hasMessageContaining("존재하지 않는 엔티티 타입입니다");
    } finally {
      OntologyTestSupport.deleteRowAsDefaultTenant(tx, dsl, otherOntologyId);
    }
  }

  @Test
  void 관계명과_설명을_수정할_수_있다() {
    long relationId = ontologyRepository.findById(ontologyId).relations().get(0).id();
    int before = ontologyRepository.findById(ontologyId).schemaVersion();

    var result = elementService.updateRelation(ontologyId, relationId,
        new UpdateRelationRequest("MOUNTED_ON", "설치 위치(개정)"));

    assertThat(result.relation().relation()).isEqualTo("MOUNTED_ON");
    assertThat(result.relation().description()).isEqualTo("설치 위치(개정)");
    assertThat(result.relation().subject()).isEqualTo("Sensor");
    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(ontologyRepository.findById(ontologyId).schemaVersion()).isEqualTo(before + 1);
  }

  @Test
  void 관계명_수정에_빈_이름을_주면_빈_이름으로_진단된다() {
    long relationId = ontologyRepository.findById(ontologyId).relations().get(0).id();

    assertThatThrownBy(() -> elementService.updateRelation(ontologyId, relationId,
        new UpdateRelationRequest("  ", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("관계명은 비어 있을 수 없습니다: Sensor → Building");
  }

  @Test
  void 관계명_수정이_다른_기존_관계와_중복되면_거부된다() {
    // Sensor-INSTALLED_IN->Building은 픽스처가 이미 갖고 있다. 새 관계를 하나 더 만들고,
    // 그 관계명을 INSTALLED_IN으로 바꾸면 같은 트리플이 되어 거부돼야 한다.
    var added = elementService.addRelation(ontologyId,
        new CreateRelationRequest(typeId("Sensor"), "NEAR", typeId("Building"), "근접"));

    assertThatThrownBy(() -> elementService.updateRelation(ontologyId, added.relation().id(),
        new UpdateRelationRequest("INSTALLED_IN", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("중복된 관계: Sensor|INSTALLED_IN|Building");
  }

  @Test
  void 관계_삭제는_엔티티_타입을_건드리지_않는다() {
    long relationId = ontologyRepository.findById(ontologyId).relations().get(0).id();
    int before = ontologyRepository.findById(ontologyId).schemaVersion();

    var result = elementService.deleteRelation(ontologyId, relationId);

    OntologyResponse after = ontologyRepository.findById(ontologyId);
    assertThat(after.relations()).isEmpty();
    assertThat(after.entities()).hasSize(2);
    assertThat(result.schemaVersion()).isEqualTo(before + 1);
    assertThat(after.schemaVersion()).isEqualTo(before + 1);
  }

  @Test
  void 다른_온톨로지의_관계_id로_수정을_시도하면_거부된다() {
    // 삭제 쪽만 커버하면 updateRelation의 requireRelation 호출은 어떤 테스트도 실패시키지
    // 않고 지울 수 있었다(리뷰 지적 — Task 4가 같은 비대칭을 이미 한 번 고쳤다).
    long otherOntologyId = ontologyService.createOntology(new CreateOntologyRequest(
        "남의온톨로지2-update-" + System.nanoTime(),
        List.of(
            new OntologyResponse.EntityType("A", "x", "y", "exact", List.of()),
            new OntologyResponse.EntityType("B", "x", "y", "exact", List.of())),
        List.of(new OntologyResponse.Triple("A", "R", "B", "x")), "draft"));
    try {
      long foreignRelationId = ontologyRepository.findById(otherOntologyId).relations().get(0).id();

      assertThatThrownBy(() -> elementService.updateRelation(ontologyId, foreignRelationId,
          new UpdateRelationRequest("X", null)))
          .isInstanceOf(OntologyElementNotFoundException.class)
          .hasMessageContaining("존재하지 않는 관계입니다");
    } finally {
      OntologyTestSupport.deleteRowAsDefaultTenant(tx, dsl, otherOntologyId);
    }
  }

  @Test
  void 다른_온톨로지의_관계_id는_거부된다() {
    long otherOntologyId = ontologyService.createOntology(new CreateOntologyRequest(
        "남의온톨로지2-" + System.nanoTime(),
        List.of(
            new OntologyResponse.EntityType("A", "x", "y", "exact", List.of()),
            new OntologyResponse.EntityType("B", "x", "y", "exact", List.of())),
        List.of(new OntologyResponse.Triple("A", "R", "B", "x")), "draft"));
    try {
      long foreignRelationId = ontologyRepository.findById(otherOntologyId).relations().get(0).id();

      assertThatThrownBy(() -> elementService.deleteRelation(ontologyId, foreignRelationId))
          .isInstanceOf(OntologyElementNotFoundException.class)
          .hasMessageContaining("존재하지 않는 관계입니다");
    } finally {
      OntologyTestSupport.deleteRowAsDefaultTenant(tx, dsl, otherOntologyId);
    }
  }
}
