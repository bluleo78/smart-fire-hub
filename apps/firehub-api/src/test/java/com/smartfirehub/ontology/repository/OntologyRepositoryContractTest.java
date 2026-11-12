package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// OntologyRepository 계약 테스트 — V71 시드 조립(순서·값)과 공개 메서드 13개 전부를 구현마다 같은 시나리오로 확인한다.
// (WD-711) jOOQ DSLContext 직접 사용을 걷어 내고 픽스처(AccessFixtures)·테넌트 둘(TenantPairContractBase)로 옮겼다 —
// MyBatis(PG)·Tibero 실서버 하위 클래스가 같은 기반을 쓴다. 시험이 만든 온톨로지는 테넌트 정리 때 함께 지워진다(하위 행은 FK CASCADE).
/** 인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485·WD-711). */
abstract class OntologyRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract OntologyRepository repository();

  /** 기본 테넌트(1) — V71 시드 온톨로지(id=1)가 이 테넌트에 있다(PG 마이그레이션·Tibero 기준선 시드 둘 다). */
  private static final long SEED_TENANT_ID = 1L;

  /** 실행마다 고유한 도메인 이름 — 테넌트별 살아 있는 도메인 유일 색인과 겹치지 않게 한다. */
  private String domain(String label) {
    return label + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** 타입 하나짜리 온톨로지를 테넌트 A 에 리포지토리로 만든다. */
  private long createSimple(String domain, String status) {
    return inA(
        () ->
            repository()
                .createOntology(
                    new CreateOntologyRequest(
                        domain,
                        List.of(new OntologyResponse.EntityType("A", "a", "n", "exact", List.of())),
                        List.of(),
                        status)));
  }

  @Test
  @DisplayName("V71 시드 온톨로지를 sort_order 순서와 값 그대로 조립한다")
  void findOntology_는_시드를_순서대로_조립한다() {
    OntologyResponse res = fx.inTenant(SEED_TENANT_ID, () -> repository().findById(1L));
    assertThat(res.domain()).isEqualTo("화재조사 보고서");
    assertThat(res.entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Incident", "Building", "Cause", "Damage", "Equipment", "Regulation");
    assertThat(res.entities().get(0).naming())
        .isEqualTo(
            "문서마다 고유해야 한다. 핵심 식별 속성(장소·일자 등)을 포함해 구성하고, 일반명이나 문서 번호를 이름으로 쓰지 마라. 한 문서에서 정확히 1개만 추출한다.");
    assertThat(res.entities().get(0).resolution()).isEqualTo("exact");
    assertThat(res.relations())
        .extracting(OntologyResponse.Triple::relation)
        .containsExactly(
            "OCCURRED_AT", "CAUSED_BY", "RESULTED_IN", "HAS_EQUIPMENT", "VIOLATED", "GOVERNED_BY");
    assertThat(res.relations().get(0).description()).isEqualTo("사건이 발생한 장소");
    // 관계의 끝점 이름은 타입 id → 이름으로 되붙인다.
    assertThat(res.relations().get(0).subject()).isEqualTo("Incident");
    assertThat(res.relations().get(0).object()).isEqualTo("Building");
  }

  @Test
  @DisplayName("createOntology 는 타입·속성·관계를 요청 순서대로 저장하고 2 이상의 새 id·schema_version 1 을 준다")
  void createOntology_왕복() {
    long id =
        inA(
            () ->
                repository()
                    .createOntology(
                        new CreateOntologyRequest(
                            domain("생성"),
                            List.of(
                                new OntologyResponse.EntityType(
                                    "Customer",
                                    "고객",
                                    "표기 그대로",
                                    "exact",
                                    List.of(
                                        new OntologyResponse.Property("등급", "고객 등급", "text", null),
                                        new OntologyResponse.Property(
                                            "한도", "신용 한도", "number", "원"))),
                                new OntologyResponse.EntityType(
                                    "Product", "상품", "표기 그대로", "embedding", List.of())),
                            List.of(
                                new OntologyResponse.Triple(
                                    "Customer", "PURCHASED", "Product", "구매")),
                            "draft")));
    assertThat(id).isGreaterThanOrEqualTo(2L);

    OntologyResponse r = inA(() -> repository().findById(id));
    assertThat(r.schemaVersion()).isEqualTo(1);
    assertThat(r.entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("Customer", "Product");
    OntologyResponse.EntityType customer = r.entities().get(0);
    assertThat(customer.id()).isNotNull();
    assertThat(customer.resolution()).isEqualTo("exact");
    assertThat(customer.properties())
        .extracting(OntologyResponse.Property::name)
        .containsExactly("등급", "한도");
    assertThat(customer.properties().get(0).unit()).isNull();
    assertThat(customer.properties().get(1).unit()).isEqualTo("원");
    assertThat(customer.properties().get(1).id()).isNotNull();
    OntologyResponse.Triple rel = r.relations().get(0);
    assertThat(rel.subject()).isEqualTo("Customer");
    assertThat(rel.object()).isEqualTo("Product");
    assertThat(rel.subjectTypeId()).isEqualTo(customer.id());
    assertThat(rel.objectTypeId()).isEqualTo(r.entities().get(1).id());
    assertThat(rel.description()).isEqualTo("구매");
    assertThat(inA(() -> repository().findStatusById(id))).isEqualTo("draft");
    assertThat(inA(() -> repository().currentSchemaVersion(id))).isEqualTo(1);
  }

  /**
   * Review Focus 5 — 온톨로지 설명·명명 규칙은 AI 프롬프트에 바이트 그대로 들어간다(findById → ai-agent buildPrompt). PG 는 빈
   * 문자열을 그대로, Tibero 는 NULL 로 저장하지만 읽기는 두 DB 모두 {@code ""} 여야 한다("null" 문자열이 프롬프트에 섞이지 않게). unit 은
   * PG 도 "단위 없음"을 NULL 로 두는 열이라 변환하지 않는다 — 빈 단위는 PG {@code ""}, Tibero null(프롬프트는 둘 다 단위 없음으로 같다).
   */
  @Test
  @DisplayName("빈 설명·명명 규칙은 저장 뒤 두 DB 모두 빈 문자열로 읽힌다(프롬프트 바이트 동일)")
  void 빈_프롬프트_열은_빈_문자열로_읽힌다() {
    long id =
        inA(
            () ->
                repository()
                    .createOntology(
                        new CreateOntologyRequest(
                            domain("빈문자열"),
                            List.of(
                                new OntologyResponse.EntityType(
                                    "Empty",
                                    "",
                                    "",
                                    "exact",
                                    List.of(new OntologyResponse.Property("p", "", "text", ""))),
                                new OntologyResponse.EntityType(
                                    "Other", "o", "n", "exact", List.of())),
                            List.of(new OntologyResponse.Triple("Empty", "R", "Other", "")))));

    OntologyResponse r = inA(() -> repository().findById(id));
    OntologyResponse.EntityType e = r.entities().get(0);
    assertThat(e.description()).isEqualTo("");
    assertThat(e.naming()).isEqualTo("");
    assertThat(e.properties().get(0).description()).isEqualTo("");
    assertThat(e.properties().get(0).unit()).isEqualTo(emptyStringIsNull() ? null : "");
    assertThat(r.relations().get(0).description()).isEqualTo("");
  }

  @Test
  @DisplayName(
      "존재하지 않는 id 는 findById·findStatusById·lockAndFindStatus·currentSchemaVersion 모두 IllegalArgumentException")
  void 존재하지_않는_id는_IllegalArgumentException() {
    // 호출마다 따로 트랜잭션을 연다 — 예외가 난 트랜잭션은 롤백 전용이 되어 같은 트랜잭션의 다음 호출을 막는다.
    assertThatThrownBy(() -> inA(() -> repository().findById(999_999_999L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inA(() -> repository().findStatusById(999_999_999L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inA(() -> repository().lockAndFindStatus(999_999_999L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inA(() -> repository().currentSchemaVersion(999_999_999L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(inA(() -> repository().existsById(999_999_999L))).isFalse();
  }

  @Test
  @DisplayName("다른 테넌트의 온톨로지는 존재하지 않는 것과 같다(조회·존재·목록·도메인 중복 검사)")
  void 다른_테넌트_온톨로지는_보이지_않는다() {
    String d = domain("남의것");
    long id = createSimple(d, "active");

    assertThat(inB(() -> repository().existsById(id))).isFalse();
    assertThat(inB(() -> repository().existsLiveDomain(d))).isFalse();
    assertThat(inB(() -> repository().findAllSummaries()))
        .extracting(OntologySummary::id)
        .doesNotContain(id);
    assertThatThrownBy(() -> inB(() -> repository().findById(id)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inB(() -> repository().findStatusById(id)))
        .isInstanceOf(IllegalArgumentException.class);
    // 자기 테넌트에서는 보인다.
    assertThat(inA(() -> repository().existsById(id))).isTrue();
  }

  @Test
  @DisplayName("existsLiveDomain 은 archived 를 빼고 같은 도메인을 찾는다")
  void existsLiveDomain_은_archived를_제외한다() {
    String live = domain("살아있음");
    String archived = domain("은퇴");
    createSimple(live, "draft");
    createSimple(archived, "archived");

    assertThat(inA(() -> repository().existsLiveDomain(live))).isTrue();
    assertThat(inA(() -> repository().existsLiveDomain(archived))).isFalse();
    assertThat(inA(() -> repository().existsLiveDomain(domain("없음")))).isFalse();
  }

  @Test
  @DisplayName("lockAndFindStatus·lockForShare 는 잠근 채 상태를 읽고 updateStatus 는 상태와 수정 시각만 바꾼다")
  void 잠금과_상태_전이() {
    long id = createSimple(domain("상태"), "draft");
    // 과거 시각으로 되돌려 updateStatus 가 수정 시각을 새로 쓰는지 판별한다.
    Instant old = Instant.parse("2020-01-02T03:04:05Z");
    fx.setOntologyUpdatedAt(id, old);
    assertThat(summaryOf(id).updatedAt().toInstant()).isEqualTo(old);

    runA(
        () -> {
          assertThat(repository().lockAndFindStatus(id)).isEqualTo("draft");
          repository().lockForShare(id);
          repository().lockForShare(999_999_999L); // 없는 id 는 조용히 지나간다.
          repository().updateStatus(id, "active");
        });

    assertThat(inA(() -> repository().findStatusById(id))).isEqualTo("active");
    OntologySummary s = summaryOf(id);
    // 상태 전이는 스키마 버전을 올리지 않는다.
    assertThat(s.schemaVersion()).isEqualTo(1);
    // UTC 순간으로 DB 현재 시각 근처 — 시간대 해석이 어긋나면 9시간 차이가 난다.
    assertThat(Duration.between(s.updatedAt().toInstant(), fx.dbNow()).abs())
        .isLessThan(Duration.ofMinutes(1));
  }

  @Test
  @DisplayName("목록 요약은 id 순이고 상태 필터·엔티티 수·바인딩 수·수정 시각을 담는다")
  void 목록_요약() {
    long a = createSimple(domain("요약A"), "draft");
    long b =
        inA(
            () ->
                repository()
                    .createOntology(
                        new CreateOntologyRequest(
                            domain("요약B"),
                            List.of(
                                new OntologyResponse.EntityType("X", "x", "n", "exact", List.of()),
                                new OntologyResponse.EntityType(
                                    "Y", "y", "n", "embedding", List.of())),
                            List.of(),
                            "active")));
    fx.bindDatasetOntology(tenantA, 700_001L, b);
    fx.bindDatasetOntology(tenantA, 700_002L, b);
    Instant at = Instant.parse("2021-06-07T08:09:10Z");
    fx.setOntologyUpdatedAt(b, at);

    List<OntologySummary> all = inA(() -> repository().findAllSummaries());
    assertThat(all).extracting(OntologySummary::id).containsExactly(a, b);
    assertThat(inA(() -> repository().findAllSummaries(null)))
        .extracting(OntologySummary::id)
        .containsExactly(a, b);
    OntologySummary sb = all.get(1);
    assertThat(sb.status()).isEqualTo("active");
    assertThat(sb.entityCount()).isEqualTo(2);
    assertThat(sb.datasetCount()).isEqualTo(2);
    assertThat(sb.schemaVersion()).isEqualTo(1);
    assertThat(sb.updatedAt().toInstant()).isCloseTo(at, within(1, ChronoUnit.MILLIS));
    OntologySummary sa = all.get(0);
    assertThat(sa.entityCount()).isEqualTo(1);
    assertThat(sa.datasetCount()).isZero();

    assertThat(inA(() -> repository().findAllSummaries("draft")))
        .extracting(OntologySummary::id)
        .containsExactly(a);
    assertThat(inA(() -> repository().findAllSummaries("active")))
        .extracting(OntologySummary::id)
        .containsExactly(b);
    assertThat(inA(() -> repository().findAllSummaries("archived"))).isEmpty();
  }

  @Test
  @DisplayName("countReferences 는 바인딩 ∪ 매핑의 데이터셋을 중복 없이 센다")
  void countReferences_는_데이터셋을_중복없이_센다() {
    long id = createSimple(domain("참조"), "active");
    assertThat(inA(() -> repository().countReferences(id))).isZero();

    // 데이터셋 1: 바인딩 + 매핑(같은 데이터셋 → 1), 데이터셋 2: 바인딩만, 데이터셋 3: 매핑만.
    fx.bindDatasetOntology(tenantA, 710_001L, id);
    fx.createDatasetMapping(tenantA, 710_001L, id);
    fx.bindDatasetOntology(tenantA, 710_002L, id);
    fx.createDatasetMapping(tenantA, 710_003L, id);

    assertThat(inA(() -> repository().countReferences(id))).isEqualTo(3);
  }

  @Test
  @DisplayName("deleteOntology 는 온톨로지와 하위 타입·관계를 지운다")
  void deleteOntology_는_하위까지_지운다() {
    long id =
        inA(
            () ->
                repository()
                    .createOntology(
                        new CreateOntologyRequest(
                            domain("삭제"),
                            List.of(
                                new OntologyResponse.EntityType("S", "s", "n", "exact", List.of()),
                                new OntologyResponse.EntityType("O", "o", "n", "exact", List.of())),
                            List.of(new OntologyResponse.Triple("S", "R", "O", "r")))));
    assertThat(inA(() -> repository().existsById(id))).isTrue();

    runA(() -> repository().deleteOntology(id));

    assertThat(inA(() -> repository().existsById(id))).isFalse();
    assertThat(inA(() -> repository().findAllSummaries()))
        .extracting(OntologySummary::id)
        .doesNotContain(id);
  }

  @Test
  @DisplayName("관계가 없는 타입을 참조하면 createOntology 가 IllegalArgumentException")
  void createOntology_는_없는_타입_참조를_거절한다() {
    assertThatThrownBy(
            () ->
                inA(
                    () ->
                        repository()
                            .createOntology(
                                new CreateOntologyRequest(
                                    domain("잘못된관계"),
                                    List.of(
                                        new OntologyResponse.EntityType(
                                            "S", "s", "n", "exact", List.of())),
                                    List.of(
                                        new OntologyResponse.Triple("S", "R", "Missing", "r"))))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 테넌트 A 에서 본 그 온톨로지의 목록 요약. */
  private OntologySummary summaryOf(long id) {
    return inA(() -> repository().findAllSummaries()).stream()
        .filter(s -> s.id() == id)
        .findFirst()
        .orElseThrow();
  }
}
