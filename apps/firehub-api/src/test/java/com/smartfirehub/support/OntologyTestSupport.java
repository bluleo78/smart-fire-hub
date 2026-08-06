package com.smartfirehub.support;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.UpdateOntologyRequest;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.ontology.service.OntologyService;
import java.util.List;
import org.jooq.DSLContext;

// 온톨로지 생명주기 테스트(OntologyDeleteTest/OntologyStatusEnforcementTest/OntologyStatusTransitionTest/
// OntologyDomainDuplicateTest) 4개 파일이 반복하던 "엔티티 1개짜리 생성 요청"과 "단일 행 정리"만 공유한다.
// 정리 범위(무엇을 지울지)는 테스트마다 다르므로(예: dataset_mapping까지 지우는 파일도 있다) 여기서는
// ontology 단일 행 삭제만 제공하고, 나머지는 각 테스트가 직접 결정하게 둔다 — 과잉 일반화하지 않는다.
public final class OntologyTestSupport {

  private OntologyTestSupport() {}

  // 엔티티(Alpha) 1개를 가진 최소 생성 요청 — 4개 테스트가 공통으로 쓰는 스키마.
  public static CreateOntologyRequest createRequest(String domain, String status) {
    return new CreateOntologyRequest(
        domain,
        List.of(new OntologyResponse.EntityType("Alpha", "가", "표기 그대로", "exact", List.of())),
        List.of(),
        status);
  }

  // 주어진 상태의 온톨로지를 만들어 id를 반환한다. archived는 생성으로 도달할 수 없으므로
  // (OntologyService.createOntology가 거부) active로 만든 뒤 상태 전이로 우회한다.
  public static long createWithStatus(
      OntologyService service, OntologyRepository repository, String domain, String status) {
    long id = service.createOntology(createRequest(domain, "archived".equals(status) ? "active" : status));
    if ("archived".equals(status)) {
      transitionTo(service, repository, id, "archived");
    }
    return id;
  }

  // 상태만 바꾸는 요청 — 본문은 현재 스키마를 그대로 실어 보낸다(full-document PUT이므로).
  public static void transitionTo(
      OntologyService service, OntologyRepository repository, long id, String target) {
    OntologyResponse current = repository.findById(id);
    service.updateOntology(
        id,
        new UpdateOntologyRequest(
            current.domain(), current.schemaVersion(), current.entities(), current.relations(), List.of(), target));
  }

  // 온톨로지 단일 행 삭제. id가 null이거나 시드 기본 온톨로지(id=1)면 아무것도 하지 않는다 —
  // null 가드 누락이 이전에 잔여 행을 남겨 다른 테스트를 오염시킨 전례가 있다.
  public static void deleteRow(DSLContext dsl, Long id) {
    if (id == null || id == 1L) return;
    dsl.deleteFrom(table(name("ontology"))).where(field(name("id"), Long.class).eq(id)).execute();
  }
}
