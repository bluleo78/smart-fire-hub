package com.smartfirehub.ontology.element;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

// 요소 편집 테스트 공통 픽스처. 시드 온톨로지(id=1)를 건드리면 다른 테스트가 함께 흔들리므로
// 테스트마다 자기 draft 온톨로지를 새로 만든다. 도메인에 nanoTime을 붙이는 이유는 살아있는
// 온톨로지의 도메인 UNIQUE(V79 부분 인덱스)와 부딪히지 않기 위함이다.
abstract class OntologyElementTestSupport extends IntegrationTestBase {

  @Autowired protected OntologyElementService elementService;
  @Autowired protected OntologyRepository ontologyRepository;
  @Autowired protected OntologyService ontologyService;
  @Autowired protected DSLContext dsl;

  protected long ontologyId;

  @BeforeEach
  void createFixtureOntology() {
    ontologyId = ontologyService.createOntology(new CreateOntologyRequest(
        "요소편집테스트-" + System.nanoTime(),
        List.of(
            new OntologyResponse.EntityType("Sensor", "센서", "표기 그대로", "embedding", List.of()),
            new OntologyResponse.EntityType("Building", "건물", "표기 그대로", "embedding", List.of())),
        List.of(new OntologyResponse.Triple("Sensor", "INSTALLED_IN", "Building", "설치 위치")),
        "draft"));
  }

  // IntegrationTestBase는 per-test 롤백을 하지 않으므로, 여기서 만든 온톨로지를 그대로 두면
  // OntologyMigrationTest처럼 entity_type/relation 테이블 전체를 스캔하는 다른 테스트가
  // 우리 픽스처 행까지 세어 오염된다. FK CASCADE로 엔티티 타입·관계도 함께 지워진다.
  @AfterEach
  void deleteFixtureOntology() {
    OntologyTestSupport.deleteRow(dsl, ontologyId);
  }

  protected long typeId(String type) {
    return ontologyRepository.findById(ontologyId).entities().stream()
        .filter(e -> e.type().equals(type)).findFirst().orElseThrow().id();
  }
}
