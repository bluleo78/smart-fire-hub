package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.OntologyTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 도메인 중복 생성 회귀 가드(코드리뷰 결함 #1) — 사전 검사 없이 부분 유니크 인덱스(V79)까지 내려가면
// DataIntegrityViolationException이 영문 DB 문구("Data integrity violation: duplicate entry")로 번역돼
// 생성 다이얼로그에 그대로 노출된다. OntologyService.createOntology가 한국어 409로 먼저 막는지,
// 그리고 그 사전 검사가 인덱스와 정합해 archived는 제외하는지(archived와 같은 이름은 생성 가능해야 함)를
// 실제 DB(부분 유니크 인덱스 포함)로 검증한다 — 리포지토리를 mock하면 인덱스 정합성을 검증할 수 없다.
class OntologyDomainDuplicateTest extends IntegrationTestBase {

  @Autowired private OntologyService service;
  @Autowired private DSLContext dsl;

  private Long firstId;
  private Long secondId;

  @AfterEach
  void cleanup() {
    // firstId/secondId 중 하나는 테스트에 따라 null일 수 있다 — deleteRow가 null-safe로 처리한다.
    OntologyTestSupport.deleteRow(dsl, firstId);
    OntologyTestSupport.deleteRow(dsl, secondId);
  }

  private CreateOntologyRequest request(String domain, String status) {
    return OntologyTestSupport.createRequest(domain, status);
  }

  @Test
  void 살아있는_도메인과_중복이면_409를_던지고_두번째_행을_만들지_않는다() {
    String domain = "중복검사 도메인 " + System.nanoTime();
    firstId = service.createOntology(request(domain, "active"));

    assertThatThrownBy(() -> service.createOntology(request(domain, "draft")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("이미 같은 도메인의 온톨로지가 있습니다");

    // 실패한 시도가 행을 남기지 않았는지 확인 — 사전 검사가 삽입 전에 걸린다는 뜻이다.
    int count = dsl.fetchCount(table(name("ontology")), field(name("domain"), String.class).eq(domain));
    assertThat(count).isEqualTo(1);
  }

  @Test
  void archived와_같은_도메인은_생성에_성공한다() {
    String domain = "은퇴재사용 도메인 " + System.nanoTime();
    firstId = service.createOntology(request(domain, "active"));
    dsl.update(table(name("ontology")))
        .set(field(name("status"), String.class), "archived")
        .where(field(name("id"), Long.class).eq(firstId))
        .execute();

    // archived는 부분 유니크 인덱스와 사전 검사 모두에서 제외되므로 같은 도메인의 후속 생성이 성공해야 한다.
    secondId = service.createOntology(request(domain, "active"));
    assertThat(secondId).isNotEqualTo(firstId);
  }
}
