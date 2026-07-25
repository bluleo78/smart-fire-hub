package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 싱글톤(id=1) 제약 해제 후, 서로 다른 온톨로지의 타입/관계가 섞이지 않는지(WHERE ontology_id 필터)
// 판별한다. 온톨로지 1개만으론 판별력이 없으므로 반드시 2번째 온톨로지를 생성해 검증한다.
class MultiOntologyRepositoryTest extends IntegrationTestBase {

  @Autowired private OntologyRepository repository;
  @Autowired private DSLContext dsl;

  private Long createdId;

  @AfterEach
  void cleanup() {
    // 롤백 없음 — 이 테스트가 만든 온톨로지(id≥2)를 직접 삭제(CASCADE로 타입/관계 함께 삭제).
    if (createdId != null) {
      dsl.deleteFrom(table(name("ontology")))
          .where(field(name("id"), Long.class).eq(createdId))
          .execute();
    }
  }

  @Test
  void 두번째_온톨로지는_자기_타입과_관계만_반환한다() {
    CreateOntologyRequest req =
        new CreateOntologyRequest(
            "테스트 판매 도메인",
            List.of(
                new OntologyResponse.EntityType(
                    "Customer", "고객", "표기 그대로", "exact", List.of()),
                new OntologyResponse.EntityType(
                    "Product", "상품", "표기 그대로", "embedding", List.of())),
            List.of(new OntologyResponse.Triple("Customer", "PURCHASED", "Product", "구매")));
    createdId = repository.createOntology(req);
    assertThat(createdId).isGreaterThanOrEqualTo(2L);

    OntologyResponse created = repository.findById(createdId);
    assertThat(created.domain()).isEqualTo("테스트 판매 도메인");
    assertThat(created.entities()).extracting(OntologyResponse.EntityType::type)
        .containsExactly("Customer", "Product");
    assertThat(created.relations()).extracting(OntologyResponse.Triple::relation)
        .containsExactly("PURCHASED");

    // 화재조사(id=1)는 신규 온톨로지 타입에 오염되지 않아야 한다(미필터 fetch 버그 판별).
    OntologyResponse fire = repository.findById(1L);
    assertThat(fire.entities()).extracting(OntologyResponse.EntityType::type)
        .contains("Incident", "Building")
        .doesNotContain("Customer", "Product");
    assertThat(fire.relations()).extracting(OntologyResponse.Triple::relation)
        .doesNotContain("PURCHASED");
  }

  @Test
  void findOntology_는_findById_1과_동일하다() {
    // 하위호환: 무인자 findOntology()는 id=1 위임이어야 한다.
    assertThat(repository.findOntology()).isEqualTo(repository.findById(1L));
  }

  @Test
  void 존재하지_않는_id는_IllegalArgumentException() {
    // 신규 라우트가 임의 id를 받으므로 NPE→500이 아니라 400 매핑 예외여야 한다.
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findById(999999L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findAllSummaries_는_생성된_온톨로지를_포함한다() {
    createdId =
        repository.createOntology(
            new CreateOntologyRequest(
                "요약 테스트 도메인",
                List.of(new OntologyResponse.EntityType("A", "a", "n", "exact", List.of())),
                List.of()));
    List<OntologySummary> all = repository.findAllSummaries();
    assertThat(all).extracting(OntologySummary::id).contains(1L, createdId);
  }
}
