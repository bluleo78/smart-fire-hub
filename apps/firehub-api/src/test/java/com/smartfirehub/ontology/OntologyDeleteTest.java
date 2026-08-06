package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 삭제 규칙 검증 — 거부 사유는 "참조 중"과 "기본 온톨로지"뿐이고 상태는 사유가 아니다.
class OntologyDeleteTest extends IntegrationTestBase {

  @Autowired private OntologyService service;
  @Autowired private OntologyRepository repository;
  @Autowired private DSLContext dsl;

  private Long createdId;
  private Long boundDatasetId;

  @AfterEach
  void cleanup() {
    if (boundDatasetId != null) {
      dsl.deleteFrom(table(name("dataset_ontology")))
          .where(field(name("dataset_id"), Long.class).eq(boundDatasetId))
          .execute();
      boundDatasetId = null;
    }
    OntologyTestSupport.deleteRow(dsl, createdId);
    createdId = null;
  }

  private long given(String domain, String status) {
    createdId = OntologyTestSupport.createWithStatus(service, repository, domain, status);
    return createdId;
  }

  // dataset_ontology는 FK 없이 dataset_id를 저장하므로(감사 테이블 패턴) 임의 id로 바인딩해도 된다.
  private void bindTo(long ontologyId, long datasetId) {
    boundDatasetId = datasetId;
    dsl.insertInto(table(name("dataset_ontology")))
        .set(field(name("dataset_id"), Long.class), datasetId)
        .set(field(name("ontology_id"), Long.class), ontologyId)
        .execute();
  }

  @Test
  void 참조가_없으면_draft를_삭제할_수_있다() {
    long id = given("삭제 테스트 draft", "draft");
    service.deleteOntology(id);
    assertThatThrownBy(() -> repository.findStatusById(id))
        .isInstanceOf(IllegalArgumentException.class);
    createdId = null; // 이미 삭제됨
  }

  @Test
  void 참조가_없으면_active도_삭제할_수_있다() {
    // 상태는 삭제 거부 사유가 아니다 — 잘못 활성화한 온톨로지를 회수할 수 있어야 한다.
    long id = given("삭제 테스트 active", "active");
    service.deleteOntology(id);
    assertThatThrownBy(() -> repository.findStatusById(id))
        .isInstanceOf(IllegalArgumentException.class);
    createdId = null;
  }

  @Test
  void 바인딩된_데이터셋이_있으면_삭제할_수_없다() {
    long id = given("삭제 테스트 참조중", "active");
    bindTo(id, 999_001L);

    assertThatThrownBy(() -> service.deleteOntology(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("사용 중");

    // 회귀 가드 — datasetCount가 실제로 조인되어 1로 집계되는지 확인한다(0으로 항상 통과하는 결함 방지).
    List<OntologySummary> summaries = repository.findAllSummaries(null);
    OntologySummary summary =
        summaries.stream().filter(s -> s.id() == id).findFirst().orElseThrow();
    assertThat(summary.datasetCount()).isEqualTo(1);
  }

  // 코드리뷰 결함 #2: 바인딩(dataset_ontology)과 매핑(dataset_mapping)이 같은 데이터셋을 가리키면
  // countReferences가 이를 distinct하게 세야 한다. MappingService.save는 항상 바인딩을 먼저 만들므로
  // 매핑이 있는 데이터셋은 바인딩도 함께 있다 — 합산하면 데이터셋 1개가 "2개"로 잘못 집계된다.
  @Test
  void 바인딩과_매핑이_모두_있는_데이터셋은_1개로_집계된다() {
    long id = given("삭제 테스트 바인딩+매핑", "active");
    long datasetId = 999_002L;
    bindTo(id, datasetId);
    dsl.insertInto(table(name("dataset_mapping")))
        .set(field(name("dataset_id"), Long.class), datasetId)
        .set(field(name("ontology_id"), Long.class), id)
        .set(field(name("spec"), org.jooq.JSONB.class), org.jooq.JSONB.valueOf("{\"entities\":[],\"relations\":[]}"))
        .execute();

    try {
      assertThatThrownBy(() -> service.deleteOntology(id))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("1개 데이터셋이 사용 중입니다");
    } finally {
      dsl.deleteFrom(table(name("dataset_mapping")))
          .where(field(name("dataset_id"), Long.class).eq(datasetId))
          .execute();
    }
  }

  @Test
  void 기본_온톨로지는_삭제할_수_없다() {
    assertThatThrownBy(() -> service.deleteOntology(1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("기본 온톨로지");
    assertThat(repository.findStatusById(1L)).isEqualTo("active");
  }

  @Test
  void 삭제하면_엔티티_타입도_함께_사라진다() {
    long id = given("삭제 테스트 CASCADE", "draft");
    service.deleteOntology(id);

    int remaining =
        dsl.fetchCount(
            table(name("ontology_entity_type")),
            field(name("ontology_id"), Long.class).eq(id));
    assertThat(remaining).isZero();
    createdId = null;
  }
}
