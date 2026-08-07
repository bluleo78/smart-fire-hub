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
  // 도메인 충돌 테스트만 같은 이름의 온톨로지를 하나 더 만든다 — 함께 정리해야 다음 테스트를 오염시키지 않는다.
  private Long successorId;

  @AfterEach
  void cleanup() {
    OntologyTestSupport.deleteRow(dsl, createdId);
    OntologyTestSupport.deleteRow(dsl, successorId);
    createdId = null;
    successorId = null;
  }

  // 엔티티 1개를 가진 온톨로지를 주어진 상태로 만든다.
  private long given(String domain, String status) {
    createdId = OntologyTestSupport.createWithStatus(service, domain, status);
    return createdId;
  }

  // 상태 전이 — 전용 경로(changeStatus). 본문을 실어 보내지 않는다.
  private void transitionTo(long id, String target) {
    OntologyTestSupport.transitionTo(service, id, target);
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
    assertThatThrownBy(() -> OntologyTestSupport.transitionTo(service, 1L, "archived"))
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
                        List.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("은퇴한 온톨로지");
  }

  @Test
  void 상태_전이는_schema_version을_올리지_않는다() {
    // 전이는 스키마를 바꾸지 않는다. 버전이 올라가면 이 온톨로지로 이미 적재된 노드의 schemaVersion
    // 스탬프가 전부 "구버전"으로 뒤집힌다 — 전이를 full-document PUT에 얹었을 때 실제로 나던 결함이다.
    long id = given("전이 테스트 버전 불변", "draft");
    int before = repository.currentSchemaVersion(id);

    transitionTo(id, "active");
    assertThat(repository.currentSchemaVersion(id)).isEqualTo(before);

    transitionTo(id, "archived");
    assertThat(repository.currentSchemaVersion(id)).isEqualTo(before);
  }

  @Test
  void 같은_도메인이_운영_중이면_복귀할_수_없다() {
    // V79 부분 유니크 인덱스는 archived를 제외한다 — 은퇴 중에 같은 도메인의 후속을 세울 수 있다.
    // 그 상태에서 옛것을 복귀시키면 인덱스 위반이 영문 DB 문구로 새어나가므로 한국어 409로 먼저 막는다.
    String domain = "전이 테스트 복귀 충돌";
    long archived = given(domain, "archived");
    successorId = OntologyTestSupport.createWithStatus(service, domain, "active");

    assertThatThrownBy(() -> transitionTo(archived, "active"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("이미 있어 복귀시킬 수 없습니다");
    // 거부됐으니 상태도 그대로여야 한다(부분 커밋 없음).
    assertThat(repository.findStatusById(archived)).isEqualTo("archived");
  }

  @Test
  void 같은_도메인의_초안이_있어도_복귀할_수_없다() {
    // V79 부분 유니크 인덱스는 draft도 포함한다 — 후속이 아직 초안이어도 복귀는 인덱스를 위반한다.
    // "운영 중"만 막는다고 오해하면 여기서 영문 DB 문구가 새어나간다.
    String domain = "전이 테스트 복귀 충돌(초안)";
    long archived = given(domain, "archived");
    successorId = OntologyTestSupport.createWithStatus(service, domain, "draft");

    assertThatThrownBy(() -> transitionTo(archived, "active"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("초안 포함");
    assertThat(repository.findStatusById(archived)).isEqualTo("archived");
  }

  @Test
  void 은퇴한_동명_온톨로지가_있어도_초안은_활성화할_수_있다() {
    // 두 행이 같은 도메인을 갖는 유일한 경로 — archived 예전 것 + 살아있는 후속.
    // 후속 draft는 이미 인덱스 안에 있으므로 active로 올라가도 새 충돌이 생기지 않는다.
    // 여기에 도메인 검사를 걸면 정상 흐름("은퇴시키고 같은 이름의 후속을 세운다")이 통째로 막힌다.
    String domain = "전이 테스트 후속 활성화";
    createdId = OntologyTestSupport.createWithStatus(service, domain, "archived");
    successorId = OntologyTestSupport.createWithStatus(service, domain, "draft");

    transitionTo(successorId, "active");
    assertThat(repository.findStatusById(successorId)).isEqualTo("active");
    assertThat(repository.findStatusById(createdId)).isEqualTo("archived");
  }

  @Test
  void 알_수_없는_상태로는_전이할_수_없다() {
    // 오타가 DB CHECK 제약까지 내려가 500이 되지 않도록 서비스에서 400으로 막는다.
    long id = given("전이 테스트 알 수 없는 상태", "draft");
    assertThatThrownBy(() -> transitionTo(id, "retired"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("알 수 없는 상태");
    assertThatThrownBy(() -> transitionTo(id, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 같은_상태로의_전이는_멱등이다() {
    // 관리 목록에서 이미 활성인 행의 활성화를 다시 눌러도 409가 아니라 성공이어야 한다.
    long id = given("전이 테스트 멱등", "active");
    transitionTo(id, "active");
    assertThat(repository.findStatusById(id)).isEqualTo("active");
  }
}
