package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// OntologyRepository 통합 테스트 — V71 시드를 OntologyResponse 로 조립하며, 순서·값이 CORE_ONTOLOGY 와 일치하는지 검증.
// (Task 7) 전체 스키마 교체 updateOntology()는 요소 단위 편집(OntologyElementRepository/Service)으로
// 대체되어 삭제됐다 — 이 클래스가 스냅샷/복원 훅으로 감싸던 쓰기 테스트들도 함께 사라졌다.
// (Task 7 리뷰 I-3 정정) IntegrationTestBase는 @Transactional/@Rollback이 없어 실제로 롤백하지 않는다
// (이전 스냅샷/복원 훅이 수동으로 존재했던 이유가 바로 이것이다). 남는
// findOntology_는_시드를_순서대로_조립한다는 상태를 전혀 바꾸지 않는 읽기 전용 테스트이므로
// 스냅샷/복원이 애초에 불필요하다 — "롤백되니 괜찮다"가 아니라 "쓰지 않으니 괜찮다"이다.
class OntologyRepositoryTest extends IntegrationTestBase {

  @Autowired private OntologyRepository repository;

  @Test
  void findOntology_는_시드를_순서대로_조립한다() {
    OntologyResponse res = repository.findOntology();
    assertThat(res.domain()).isEqualTo("화재조사 보고서");
    assertThat(res.entities()).extracting(OntologyResponse.EntityType::type)
        .containsExactly("Incident", "Building", "Cause", "Damage", "Equipment", "Regulation");
    assertThat(res.entities().get(0).naming())
        .isEqualTo("문서마다 고유해야 한다. 핵심 식별 속성(장소·일자 등)을 포함해 구성하고, 일반명이나 문서 번호를 이름으로 쓰지 마라. 한 문서에서 정확히 1개만 추출한다.");
    assertThat(res.entities().get(0).resolution()).isEqualTo("exact");
    assertThat(res.relations()).extracting(OntologyResponse.Triple::relation)
        .containsExactly("OCCURRED_AT", "CAUSED_BY", "RESULTED_IN", "HAS_EQUIPMENT", "VIOLATED", "GOVERNED_BY");
    assertThat(res.relations().get(0).description()).isEqualTo("사건이 발생한 장소");
  }
}
