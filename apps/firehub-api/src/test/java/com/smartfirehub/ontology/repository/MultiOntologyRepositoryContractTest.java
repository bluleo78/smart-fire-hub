package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 싱글톤(id=1) 제약 해제 후, 서로 다른 온톨로지의 타입/관계가 섞이지 않는지(WHERE ontology_id 필터)
// 판별한다. 온톨로지 1개만으론 판별력이 없으므로 반드시 2번째 온톨로지를 생성해 검증한다.
// (WD-711) jOOQ DSLContext 정리 코드를 걷어 내고 픽스처 테넌트(A)에 만든다 — 테넌트 정리가 온톨로지를 지운다(하위는 FK CASCADE).
/** 인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485·WD-711). */
abstract class MultiOntologyRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract OntologyRepository repository();

  /** 테넌트 A 에 온톨로지를 만든다(도메인은 실행마다 고유하게). */
  private long create(CreateOntologyRequest req) {
    return inA(() -> repository().createOntology(req));
  }

  private static String unique(String label) {
    return label + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  @DisplayName("두 온톨로지는 각자 자기 타입과 관계만 돌려준다")
  void 두번째_온톨로지는_자기_타입과_관계만_반환한다() {
    long first =
        create(
            new CreateOntologyRequest(
                unique("테스트 화재 도메인"),
                List.of(
                    new OntologyResponse.EntityType("Incident", "사건", "표기 그대로", "exact", List.of()),
                    new OntologyResponse.EntityType(
                        "Building", "건물", "표기 그대로", "embedding", List.of())),
                List.of(new OntologyResponse.Triple("Incident", "OCCURRED_AT", "Building", "장소"))));
    long second =
        create(
            new CreateOntologyRequest(
                unique("테스트 판매 도메인"),
                List.of(
                    new OntologyResponse.EntityType("Customer", "고객", "표기 그대로", "exact", List.of()),
                    new OntologyResponse.EntityType(
                        "Product", "상품", "표기 그대로", "embedding", List.of())),
                List.of(new OntologyResponse.Triple("Customer", "PURCHASED", "Product", "구매"))));
    assertThat(second).isGreaterThan(first);

    OntologyResponse created = inA(() -> repository().findById(second));
    assertThat(created.domain()).startsWith("테스트 판매 도메인");
    assertThat(created.entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Customer", "Product");
    assertThat(created.relations())
        .extracting(OntologyResponse.Triple::relation)
        .containsExactly("PURCHASED");

    // 첫 온톨로지는 두 번째 온톨로지 타입에 오염되지 않아야 한다(미필터 fetch 버그 판별).
    OntologyResponse fire = inA(() -> repository().findById(first));
    assertThat(fire.entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Incident", "Building");
    assertThat(fire.relations())
        .extracting(OntologyResponse.Triple::relation)
        .containsExactly("OCCURRED_AT");
  }

  @Test
  @DisplayName("findAllSummaries 는 새로 만든 온톨로지를 담는다")
  void findAllSummaries_는_생성된_온톨로지를_포함한다() {
    long id =
        create(
            new CreateOntologyRequest(
                unique("요약 테스트 도메인"),
                List.of(new OntologyResponse.EntityType("A", "a", "n", "exact", List.of())),
                List.of()));
    List<OntologySummary> all = inA(() -> repository().findAllSummaries());
    assertThat(all).extracting(OntologySummary::id).contains(id);
  }

  @Test
  @DisplayName("목록 요약은 상태·엔티티 수·바인딩 수(0)·수정 시각을 담는다")
  void 목록_요약은_상태와_엔티티_개수와_수정일을_포함한다() {
    long id =
        create(
            new CreateOntologyRequest(
                unique("V79 요약 필드 테스트"),
                List.of(
                    new OntologyResponse.EntityType("Alpha", "가", "표기 그대로", "exact", List.of()),
                    new OntologyResponse.EntityType("Beta", "나", "표기 그대로", "embedding", List.of())),
                List.of(),
                "draft"));

    var summary =
        inA(() -> repository().findAllSummaries(null)).stream()
            .filter(s -> s.id() == id)
            .findFirst()
            .orElseThrow();

    assertThat(summary.status()).isEqualTo("draft");
    assertThat(summary.entityCount()).isEqualTo(2);
    // 바인딩한 적이 없으므로 0. dataset_ontology 조인이 빠지면 여기서 걸린다.
    assertThat(summary.datasetCount()).isZero();
    assertThat(summary.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("목록 요약은 상태로 거른다")
  void 목록_요약은_상태로_필터링된다() {
    long id =
        create(
            new CreateOntologyRequest(
                unique("V79 상태 필터 테스트"),
                List.of(
                    new OntologyResponse.EntityType("Alpha", "가", "표기 그대로", "exact", List.of())),
                List.of(),
                "draft"));

    // active 필터에는 잡히지 않고
    assertThat(inA(() -> repository().findAllSummaries("active")))
        .extracting(OntologySummary::id)
        .doesNotContain(id);
    // draft 필터에는 잡힌다
    assertThat(inA(() -> repository().findAllSummaries("draft")))
        .extracting(OntologySummary::id)
        .contains(id);
  }

  @Test
  @DisplayName("findStatusById 는 저장된 상태를 돌려준다")
  void findStatusById는_저장된_상태를_반환한다() {
    long archived =
        create(
            new CreateOntologyRequest(
                unique("V79 상태 조회 테스트"),
                List.of(
                    new OntologyResponse.EntityType("Alpha", "가", "표기 그대로", "exact", List.of())),
                List.of(),
                "archived"));
    long active = fx.createOntology(tenantA, unique("기본 상태"));

    assertThat(inA(() -> repository().findStatusById(archived))).isEqualTo("archived");
    // 열 기본값(active) — 리포지토리가 직접 쓰지 않은 행도 읽는다.
    assertThat(inA(() -> repository().findStatusById(active))).isEqualTo("active");
  }

  /**
   * findById 의 프로퍼티 조회를 타입당 1회(N+1)에서 온톨로지당 1회로 합친 뒤의 회귀 가드.
   *
   * <p>한 방에 읽어 메모리에서 타입별로 묶는 형태라, 이 테스트가 보는 것은 세 가지다: (1) 프로퍼티가 엉뚱한 타입에 붙지 않는가, (2) 타입 안의
   * sort_order 순서가 보존되는가, (3) 프로퍼티가 없는 타입이 null 이 아니라 빈 목록인가. 타입이 하나뿐이면 (1)을 판별할 수 없으므로 반드시 두 타입
   * 이상을 만든다.
   */
  @Test
  @DisplayName("findById 는 프로퍼티를 타입별로 순서대로 묶고 프로퍼티 없는 타입은 빈 목록이다")
  void findById_는_프로퍼티를_타입별로_올바르게_묶는다() {
    long id =
        create(
            new CreateOntologyRequest(
                unique("테스트 프로퍼티 묶기 도메인"),
                List.of(
                    new OntologyResponse.EntityType(
                        "Customer",
                        "고객",
                        "표기 그대로",
                        "exact",
                        List.of(
                            new OntologyResponse.Property("등급", "고객 등급", "text", null),
                            new OntologyResponse.Property("가입일", "가입 일자", "date", null))),
                    new OntologyResponse.EntityType(
                        "Product",
                        "상품",
                        "표기 그대로",
                        "exact",
                        List.of(new OntologyResponse.Property("단가", "판매 단가", "number", "원"))),
                    new OntologyResponse.EntityType("Store", "매장", "표기 그대로", "exact", List.of())),
                List.of()));

    OntologyResponse found = inA(() -> repository().findById(id));
    var byType =
        found.entities().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    OntologyResponse.EntityType::type, e -> e.properties()));

    // 타입별로 자기 프로퍼티만, 요청한 순서 그대로.
    assertThat(byType.get("Customer"))
        .extracting(OntologyResponse.Property::name)
        .containsExactly("등급", "가입일");
    assertThat(byType.get("Product"))
        .extracting(OntologyResponse.Property::name)
        .containsExactly("단가");
    // 프로퍼티가 없는 타입은 null 이 아니라 빈 목록이어야 한다(기존 계약).
    assertThat(byType.get("Store")).isEmpty();
    // 단위·타입 같은 부속 필드도 같이 실려 온다(묶기 과정에서 누락되기 쉬운 지점).
    assertThat(byType.get("Product").get(0).unit()).isEqualTo("원");
    assertThat(byType.get("Product").get(0).dataType()).isEqualTo("number");
  }
}
