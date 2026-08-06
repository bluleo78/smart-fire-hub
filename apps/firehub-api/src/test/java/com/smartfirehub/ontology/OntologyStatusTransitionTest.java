package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.OntologyTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 상태 전이 규칙 검증. 허용 3종 / 거부 4종을 전수로 고정한다.
// 실 DB를 쓰는 이유: 전이는 findStatusById → 규칙 판정 → updateStatus 의 왕복이라 mock으로는
// 규칙이 실제 저장 상태와 맞물리는지 확인할 수 없다.
class OntologyStatusTransitionTest extends IntegrationTestBase {

  @Autowired private OntologyService service;
  @Autowired private OntologyRepository repository;

  @Autowired private DSLContext dsl;

  private Long createdId;

  // 삭제 서비스(Task 5)에 의존하지 않도록 DSL로 직접 지운다 — 태스크 간 순서 결합을 만들지 않는다.
  // entity_type/relation은 ON DELETE CASCADE로 함께 사라진다.
  @AfterEach
  void cleanup() {
    OntologyTestSupport.deleteRow(dsl, createdId);
    createdId = null;
  }

  // 엔티티 1개를 가진 온톨로지를 주어진 상태로 만든다.
  private long given(String domain, String status) {
    createdId = OntologyTestSupport.createWithStatus(service, repository, domain, status);
    return createdId;
  }

  // 상태만 바꾸는 요청 — 본문은 현재 스키마를 그대로 실어 보낸다(full-document PUT이므로).
  private void transitionTo(long id, String target) {
    OntologyTestSupport.transitionTo(service, repository, id, target);
  }

  @Test
  void draft에서_active로_활성화할_수_있다() {
    long id = given("전이 테스트 draft→active", "draft");
    transitionTo(id, "active");
    assertThat(repository.findStatusById(id)).isEqualTo("active");
  }

  @Test
  void active에서_archived로_은퇴할_수_있다() {
    long id = given("전이 테스트 active→archived", "active");
    transitionTo(id, "archived");
    assertThat(repository.findStatusById(id)).isEqualTo("archived");
  }

  @Test
  void archived에서_active로_복귀할_수_있다() {
    long id = given("전이 테스트 archived→active", "archived");
    transitionTo(id, "active");
    assertThat(repository.findStatusById(id)).isEqualTo("active");
  }

  @Test
  void active에서_draft로_강등할_수_없다() {
    long id = given("전이 테스트 active→draft", "active");
    assertThatThrownBy(() -> transitionTo(id, "draft"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("초안으로 되돌릴 수 없습니다");
  }

  @Test
  void archived에서_draft로_강등할_수_없다() {
    long id = given("전이 테스트 archived→draft", "archived");
    assertThatThrownBy(() -> transitionTo(id, "draft"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void draft를_바로_archived로_보낼_수_없다() {
    long id = given("전이 테스트 draft→archived", "draft");
    assertThatThrownBy(() -> transitionTo(id, "archived"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 엔티티가_없는_draft는_활성화할_수_없다() {
    createdId =
        service.createOntology(
            new CreateOntologyRequest("전이 테스트 빈 draft 활성화", List.of(), List.of(), "draft"));
    assertThatThrownBy(() -> transitionTo(createdId, "active"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("엔티티 타입은 최소 1개");
  }

  @Test
  void 기본_온톨로지는_은퇴시킬_수_없다() {
    // id=1은 문서 적재가 단수 /ontology로 의존한다 — 은퇴시키면 적재가 조용히 깨진다.
    assertThatThrownBy(() -> OntologyTestSupport.transitionTo(service, repository, 1L, "archived"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("기본 온톨로지");
    assertThat(repository.findStatusById(1L)).isEqualTo("active");
  }

  @Test
  void archived_온톨로지의_스키마는_편집할_수_없다() {
    long id = given("전이 테스트 archived 편집", "archived");
    OntologyResponse current = repository.findById(id);
    assertThatThrownBy(
            () ->
                service.updateOntology(
                    id,
                    new UpdateOntologyRequest(
                        "이름 바꾸기 시도",
                        current.schemaVersion(),
                        current.entities(),
                        current.relations(),
                        List.of(),
                        null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("은퇴한 온톨로지");
  }
}
