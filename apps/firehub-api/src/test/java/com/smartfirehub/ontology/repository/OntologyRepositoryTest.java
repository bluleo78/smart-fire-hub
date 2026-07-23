package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// OntologyRepository 통합 테스트 — V71 시드를 OntologyResponse 로 조립하며, 순서·값이 CORE_ONTOLOGY 와 일치하는지 검증.
// updateOntology()는 싱글톤(id=1) 행을 변형하므로, IntegrationTestBase가 롤백하지 않는 점을 감안해
// 각 테스트 전 원본 상태를 스냅샷하고 종료 후 원본으로 완전 복원한다(OntologyMigrationTest의
// schemaVersion==1 단정을 포함해 다른 테스트를 오염시키지 않기 위함).
class OntologyRepositoryTest extends IntegrationTestBase {

  @Autowired private OntologyRepository repository;
  @Autowired private GraphIngestRepository graphIngestRepository;
  @Autowired private DSLContext dsl;

  private OntologyResponse original;

  @BeforeEach
  void snapshot() {
    original = repository.findOntology();
  }

  // 원본 스냅샷으로 완전 복원: 자식 전량 재작성 + schema_version을 1로 절대 리셋(리포지토리 API는 +1만
  // 지원하므로 raw SQL로 직접 절대값을 설정한다).
  @AfterEach
  void restore() {
    dsl.deleteFrom(table(name("dataset_graph_ingest")))
        .where(field(name("dataset_id"), Long.class).ge(9000L))
        .execute();

    UpdateOntologyRequest restoreReq =
        new UpdateOntologyRequest(
            original.domain(), repository.currentSchemaVersion(), original.entities(), original.relations());
    repository.updateOntology(restoreReq);
    dsl.update(table(name("ontology")))
        .set(field(name("schema_version"), Integer.class), 1)
        .where(field(name("id"), Long.class).eq(1L))
        .execute();
  }

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

  // (a) PUT 갱신: description/naming/resolution/domain 변경 + schema_version 원자 증가(1→2) 검증.
  @Test
  void updateOntology_는_필드를_갱신하고_버전을_증가시킨다() {
    var editedIncident =
        new OntologyResponse.EntityType(
            "Incident", "수정된 설명", "수정된 명명 규칙", "exact", original.entities().get(0).properties());
    List<OntologyResponse.EntityType> entities = new java.util.ArrayList<>(original.entities());
    entities.set(0, editedIncident);

    UpdateOntologyRequest req =
        new UpdateOntologyRequest("수정된 도메인", 1, entities, original.relations());
    int newVersion = repository.updateOntology(req);

    assertThat(newVersion).isEqualTo(2);
    OntologyResponse res = repository.findOntology();
    assertThat(res.schemaVersion()).isEqualTo(2);
    assertThat(res.domain()).isEqualTo("수정된 도메인");
    assertThat(res.entities().get(0).description()).isEqualTo("수정된 설명");
    assertThat(res.entities().get(0).naming()).isEqualTo("수정된 명명 규칙");
  }

  // (b) 낙관적 동시성: 기대 버전 불일치(예: 이미 2인데 1을 기대) → IllegalStateException(→컨트롤러에서 409),
  // 자식 테이블은 변경되지 않아야 한다(트랜잭션 롤백).
  @Test
  void updateOntology_는_버전_불일치_시_예외를_던지고_롤백한다() {
    UpdateOntologyRequest first =
        new UpdateOntologyRequest(original.domain(), 1, original.entities(), original.relations());
    repository.updateOntology(first); // 버전 1→2

    UpdateOntologyRequest staleReq =
        new UpdateOntologyRequest("잘못된 시도", 1, original.entities(), original.relations());

    assertThatThrownBy(() -> repository.updateOntology(staleReq))
        .isInstanceOf(IllegalStateException.class);

    OntologyResponse res = repository.findOntology();
    assertThat(res.schemaVersion()).isEqualTo(2); // 실패한 시도로 증가하지 않음
    assertThat(res.domain()).isEqualTo(original.domain()); // 롤백되어 원본 domain 유지
  }

  // (d) 라운드트립: 편집 폼이 손대지 않은 엔티티의 properties/relations를 그대로 되돌려 보내면
  // sort_order·값이 원본과 완전히 동일하게 보존되어야 한다(전체 삭제·재삽입 방식의 핵심 위험).
  @Test
  void updateOntology_는_미편집_properties와_relations를_그대로_보존한다() {
    UpdateOntologyRequest req =
        new UpdateOntologyRequest(original.domain(), 1, original.entities(), original.relations());
    repository.updateOntology(req);

    OntologyResponse res = repository.findOntology();
    assertThat(res.entities()).isEqualTo(original.entities());
    assertThat(res.relations()).isEqualTo(original.relations());
  }

  // (f) stale 시드 시나리오: dataset_graph_ingest에 현재 버전(1)으로 적재 이력을 남긴 뒤 온톨로지를
  // 편집(버전 2)하면, 해당 데이터셋이 GraphIngestRepository.findStale(2)에 나타나야 한다(그래프 재적재 필요 신호).
  @Test
  void updateOntology_는_기존_적재_데이터셋을_stale로_만든다() {
    graphIngestRepository.save(9301L, 1, 10, 20, 15, 0, "SUCCESS");
    assertThat(graphIngestRepository.findStale(1)).extracting("datasetId").doesNotContain(9301L);

    UpdateOntologyRequest req =
        new UpdateOntologyRequest(original.domain(), 1, original.entities(), original.relations());
    int newVersion = repository.updateOntology(req);

    assertThat(graphIngestRepository.findStale(newVersion))
        .extracting("datasetId")
        .contains(9301L);
  }
}
