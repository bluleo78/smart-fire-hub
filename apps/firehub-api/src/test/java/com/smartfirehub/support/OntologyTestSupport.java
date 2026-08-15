package com.smartfirehub.support;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.service.OntologyService;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionTemplate;

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
  public static long createWithStatus(OntologyService service, String domain, String status) {
    long id = service.createOntology(createRequest(domain, "archived".equals(status) ? "active" : status));
    if ("archived".equals(status)) {
      transitionTo(service, id, "archived");
    }
    return id;
  }

  // 상태 전이 — 전용 경로(PATCH /ontology/{id}/status)에 대응하는 서비스 메서드를 호출한다.
  // 본문을 실어 보내던 시절과 달리 현재 스키마를 미리 조회할 필요가 없다.
  public static void transitionTo(OntologyService service, long id, String target) {
    service.changeStatus(id, target);
  }

  // 온톨로지 단일 행 삭제. id가 null이거나 시드 기본 온톨로지(id=1)면 아무것도 하지 않는다 —
  // null 가드 누락이 이전에 잔여 행을 남겨 다른 테스트를 오염시킨 전례가 있다.
  //
  // V102 가 ontology 에 RLS 를 건 뒤로 TransactionTemplate 을 받는다. GUC(app.tenant_id)는
  // 트랜잭션이 열릴 때만 주입되므로, 트랜잭션 밖 DELETE 는 예외 없이 조용히 0행이 되고 정리가
  // 통째로 무력화된다 — 그러면 entity_type/relation 테이블 전체를 스캔하는 OntologyMigrationTest 가
  // 남의 픽스처까지 세어 실패한다(실제로 그렇게 깨졌다). 삭제만 트랜잭션으로 감싸는 것이 정답이고,
  // 테스트 클래스에 @Transactional 을 붙이는 것은 프로덕션 배선 결함을 가리므로 금지다.
  // 이름에 "AsDefaultTenant" 를 박아 둔 이유: 본문이 기본 테넌트 GUC 로 고정돼 있다. 다른 테넌트에서
  // 만든 픽스처에 이 헬퍼를 쓰면 RLS 가 그 행을 가려 0행 삭제가 되고, 예외 없이 잔여 행이 남아
  // 이 헬퍼가 애초에 고치려던 오염이 그대로 재발한다. 그런 픽스처는 직접 트랜잭션을 열어 지울 것.
  public static void deleteRowAsDefaultTenant(TransactionTemplate tx, DSLContext dsl, Long id) {
    if (id == null || id == 1L) return;
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        IntegrationTestBase.DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("ontology")))
                .where(field(name("id"), Long.class).eq(id))
                .execute());
  }
}
