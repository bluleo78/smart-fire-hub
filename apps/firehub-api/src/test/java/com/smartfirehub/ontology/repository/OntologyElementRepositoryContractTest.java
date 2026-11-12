package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.CreateRelationRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateEntityTypeRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdatePropertyRequest;
import com.smartfirehub.ontology.element.dto.ElementDtos.UpdateRelationRequest;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OntologyElementRepository 계약 테스트(WD-711) — 공개 메서드 18개 전부를 구현마다 같은 시나리오로 확인한다.
 *
 * <p>읽기 확인은 같은 구현 계열의 {@link OntologyRepository#findById} 로 한다(요소 리포지토리의 읽기 계약이 그것이다). 온톨로지는 픽스처로
 * 테넌트 A 에 만들고, 테넌트 정리가 지운다(하위 요소는 FK CASCADE). 이름 목록 메서드(findTypeNames·findPropertyNames·
 * findRelationIdsTouching)는 jOOQ 가 정렬을 걸지 않으므로 순서 없이 비교한다.
 */
abstract class OntologyElementRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract OntologyElementRepository repository();

  /** 읽기 확인용 온톨로지 리포지토리 — 대상과 같은 구현 계열. */
  protected abstract OntologyRepository ontologyRepository();

  private long ontologyId;

  @BeforeEach
  void createOntology() {
    ontologyId = fx.createOntology(tenantA, "el-" + UUID.randomUUID().toString().substring(0, 8));
  }

  private long insertType(String type) {
    return inA(
        () ->
            repository()
                .insertEntityType(
                    ontologyId,
                    new CreateEntityTypeRequest(type, type + " 설명", "표기 그대로", "exact")));
  }

  private OntologyResponse read() {
    return inA(() -> ontologyRepository().findById(ontologyId));
  }

  private OntologyResponse.EntityType typeOf(String type) {
    return read().entities().stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("bumpVersion 은 schema_version 을 1씩 올려 새 값을 돌려주고 수정 시각을 새로 쓴다")
  void bumpVersion_은_단조_증가한다() {
    fx.setOntologyUpdatedAt(ontologyId, Instant.parse("2020-01-02T03:04:05Z"));

    assertThat(inA(() -> repository().bumpVersion(ontologyId))).isEqualTo(2);
    assertThat(inA(() -> repository().bumpVersion(ontologyId))).isEqualTo(3);

    assertThat(inA(() -> ontologyRepository().currentSchemaVersion(ontologyId))).isEqualTo(3);
    OntologySummary s =
        inA(() -> ontologyRepository().findAllSummaries()).stream()
            .filter(x -> x.id() == ontologyId)
            .findFirst()
            .orElseThrow();
    assertThat(Duration.between(s.updatedAt().toInstant(), fx.dbNow()).abs())
        .isLessThan(Duration.ofMinutes(1));
  }

  @Test
  @DisplayName("updateDomain 은 도메인만 바꾼다")
  void updateDomain() {
    String d = "새도메인-" + UUID.randomUUID().toString().substring(0, 8);
    runA(() -> repository().updateDomain(ontologyId, d));
    assertThat(read().domain()).isEqualTo(d);
    assertThat(read().schemaVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("타입은 맨 뒤(max+1)에 붙고 삭제로 생긴 빈 번호를 메우지 않으며, 이름·개수·소속 판정이 맞다")
  void 엔티티_타입_삽입_순서와_조회() {
    assertThat(inA(() -> repository().countEntityTypes(ontologyId))).isZero();
    assertThat(inA(() -> repository().findTypeNames(ontologyId))).isEmpty();

    long a = insertType("A");
    long b = insertType("B");
    long c = insertType("C");
    runA(() -> repository().deleteEntityType(b));
    long d = insertType("D");

    assertThat(read().entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("A", "C", "D");
    assertThat(read().entities())
        .extracting(OntologyResponse.EntityType::id)
        .containsExactly(a, c, d);
    assertThat(inA(() -> repository().findTypeNames(ontologyId)))
        .containsExactlyInAnyOrder("A", "C", "D");
    assertThat(inA(() -> repository().countEntityTypes(ontologyId))).isEqualTo(3);
    assertThat(inA(() -> repository().findTypeName(ontologyId, c))).isEqualTo("C");
    // 지운 타입·다른 온톨로지의 타입은 null(id 만 알아도 남의 타입을 고치는 구멍 차단).
    assertThat(inA(() -> repository().findTypeName(ontologyId, b))).isNull();
    long other =
        fx.createOntology(tenantA, "el-other-" + UUID.randomUUID().toString().substring(0, 8));
    assertThat(inA(() -> repository().findTypeName(other, a))).isNull();
    OntologyResponse.EntityType ta = typeOf("A");
    assertThat(ta.description()).isEqualTo("A 설명");
    assertThat(ta.naming()).isEqualTo("표기 그대로");
    assertThat(ta.resolution()).isEqualTo("exact");
  }

  @Test
  @DisplayName("updateEntityType 은 null 이 아닌 필드만 바꾸고 전부 null 이면 아무것도 쓰지 않는다")
  void updateEntityType_은_PATCH_의미론() {
    long a = insertType("A");

    runA(
        () ->
            repository().updateEntityType(a, new UpdateEntityTypeRequest(null, null, null, null)));
    assertThat(typeOf("A").description()).isEqualTo("A 설명");

    runA(
        () ->
            repository()
                .updateEntityType(a, new UpdateEntityTypeRequest("A2", null, "새 규칙", null)));
    OntologyResponse.EntityType t = typeOf("A2");
    assertThat(t.id()).isEqualTo(a);
    assertThat(t.description()).isEqualTo("A 설명");
    assertThat(t.naming()).isEqualTo("새 규칙");
    assertThat(t.resolution()).isEqualTo("exact");

    runA(
        () ->
            repository()
                .updateEntityType(a, new UpdateEntityTypeRequest(null, "새 설명", null, "embedding")));
    t = typeOf("A2");
    assertThat(t.description()).isEqualTo("새 설명");
    assertThat(t.resolution()).isEqualTo("embedding");
  }

  /** Review Focus 5 — 요소 단위 경로로 넣은·바꾼 빈 설명·명명 규칙도 두 DB 모두 {@code ""} 로 읽힌다(프롬프트 바이트 동일). */
  @Test
  @DisplayName("요소 경로의 빈 설명·명명 규칙은 두 DB 모두 빈 문자열로 읽히고 빈 단위는 null 로 정규화된다")
  void 빈_프롬프트_열은_빈_문자열로_읽힌다() {
    long empty =
        inA(
            () ->
                repository()
                    .insertEntityType(
                        ontologyId, new CreateEntityTypeRequest("Empty", "", "", "exact")));
    long other = insertType("Other");
    runA(() -> repository().insertProperty(empty, new CreatePropertyRequest("p", "", "text", "")));
    runA(
        () ->
            repository()
                .insertRelation(ontologyId, new CreateRelationRequest(empty, "R", other, "")));
    // 채워진 값을 빈 문자열로 바꾸는 PATCH 도 "변경"이다.
    runA(
        () ->
            repository().updateEntityType(other, new UpdateEntityTypeRequest(null, "", "", null)));

    OntologyResponse r = read();
    OntologyResponse.EntityType e = r.entities().get(0);
    assertThat(e.description()).isEqualTo("");
    assertThat(e.naming()).isEqualTo("");
    assertThat(e.properties().get(0).description()).isEqualTo("");
    assertThat(e.properties().get(0).unit()).isNull();
    assertThat(r.entities().get(1).description()).isEqualTo("");
    assertThat(r.entities().get(1).naming()).isEqualTo("");
    assertThat(r.relations().get(0).description()).isEqualTo("");
  }

  @Test
  @DisplayName("속성은 맨 뒤에 붙고 이름·소속 판정·PATCH·빈 단위 정규화·삭제가 맞다")
  void 속성_삽입_수정_삭제() {
    long a = insertType("A");
    long b = insertType("B");
    long p1 =
        inA(
            () ->
                repository()
                    .insertProperty(a, new CreatePropertyRequest("금액", "피해 금액", "number", "원")));
    long p2 =
        inA(
            () ->
                repository()
                    .insertProperty(a, new CreatePropertyRequest("일자", "발생 일자", "date", null)));
    runA(() -> repository().deleteProperty(p1));
    long p3 =
        inA(
            () ->
                repository()
                    .insertProperty(a, new CreatePropertyRequest("메모", "비고", "text", null)));

    assertThat(typeOf("A").properties())
        .extracting(OntologyResponse.Property::id)
        .containsExactly(p2, p3);
    assertThat(inA(() -> repository().findPropertyNames(a))).containsExactlyInAnyOrder("일자", "메모");
    assertThat(inA(() -> repository().findPropertyNames(b))).isEmpty();
    assertThat(inA(() -> repository().findPropertyName(a, p2))).isEqualTo("일자");
    assertThat(inA(() -> repository().findPropertyName(b, p2))).isNull();
    assertThat(inA(() -> repository().findPropertyName(a, p1))).isNull();

    // PATCH — 전부 null 은 변경 없음, 단위 지정, 빈 단위는 NULL 로 지우기.
    runA(() -> repository().updateProperty(p2, new UpdatePropertyRequest(null, null, null, null)));
    runA(
        () ->
            repository().updateProperty(p2, new UpdatePropertyRequest("일자2", null, "number", "일")));
    OntologyResponse.Property prop = typeOf("A").properties().get(0);
    assertThat(prop.name()).isEqualTo("일자2");
    assertThat(prop.description()).isEqualTo("발생 일자");
    assertThat(prop.dataType()).isEqualTo("number");
    assertThat(prop.unit()).isEqualTo("일");
    runA(() -> repository().updateProperty(p2, new UpdatePropertyRequest(null, "", null, "")));
    prop = typeOf("A").properties().get(0);
    assertThat(prop.unit()).isNull();
    assertThat(prop.description()).isEqualTo("");
    assertThat(prop.name()).isEqualTo("일자2");
  }

  @Test
  @DisplayName("관계는 맨 뒤에 붙고 트리플 존재 판정·PATCH·삭제·타입 삭제 연쇄가 맞다")
  void 관계_삽입_수정_삭제() {
    long a = insertType("A");
    long b = insertType("B");
    long c = insertType("C");
    long r1 =
        inA(
            () ->
                repository()
                    .insertRelation(ontologyId, new CreateRelationRequest(a, "R1", b, "첫째")));
    long r2 =
        inA(
            () ->
                repository()
                    .insertRelation(ontologyId, new CreateRelationRequest(b, "R2", c, "둘째")));
    long r3 =
        inA(
            () ->
                repository()
                    .insertRelation(ontologyId, new CreateRelationRequest(c, "R3", c, "셋째")));

    assertThat(read().relations())
        .extracting(OntologyResponse.Triple::id)
        .containsExactly(r1, r2, r3);
    OntologyResponse.Triple first = read().relations().get(0);
    assertThat(first.subject()).isEqualTo("A");
    assertThat(first.object()).isEqualTo("B");
    assertThat(first.subjectTypeId()).isEqualTo(a);
    assertThat(first.objectTypeId()).isEqualTo(b);

    assertThat(inA(() -> repository().existsTriple(ontologyId, a, "R1", b))).isTrue();
    assertThat(inA(() -> repository().existsTriple(ontologyId, b, "R1", a))).isFalse();
    assertThat(inA(() -> repository().existsTriple(ontologyId, a, "R9", b))).isFalse();

    runA(() -> repository().updateRelation(r1, new UpdateRelationRequest(null, null)));
    runA(() -> repository().updateRelation(r1, new UpdateRelationRequest("R1X", null)));
    assertThat(read().relations().get(0).relation()).isEqualTo("R1X");
    assertThat(read().relations().get(0).description()).isEqualTo("첫째");
    runA(() -> repository().updateRelation(r1, new UpdateRelationRequest(null, "새 설명")));
    assertThat(read().relations().get(0).description()).isEqualTo("새 설명");

    // B 를 건드리는 관계는 R1(목적어)·R2(주어).
    assertThat(inA(() -> repository().findRelationIdsTouching(b)))
        .containsExactlyInAnyOrder(r1, r2);
    assertThat(inA(() -> repository().findRelationIdsTouching(c)))
        .containsExactlyInAnyOrder(r2, r3);

    runA(() -> repository().deleteRelation(r3));
    assertThat(read().relations()).extracting(OntologyResponse.Triple::id).containsExactly(r1, r2);

    // 타입을 지우면 그 타입을 건드리는 관계도 FK CASCADE 로 사라진다.
    runA(() -> repository().deleteEntityType(b));
    assertThat(read().relations()).isEmpty();
    assertThat(inA(() -> repository().findRelationIdsTouching(c))).isEmpty();
  }

  @Test
  @DisplayName("다른 테넌트의 온톨로지 요소는 보이지도 바뀌지도 않는다")
  void 다른_테넌트_요소는_차단된다() {
    long a = insertType("A");

    assertThat(inB(() -> repository().findTypeNames(ontologyId))).isEmpty();
    assertThat(inB(() -> repository().countEntityTypes(ontologyId))).isZero();
    assertThat(inB(() -> repository().findTypeName(ontologyId, a))).isNull();
    fx.inTenant(
        tenantB,
        () -> {
          repository().updateEntityType(a, new UpdateEntityTypeRequest("Hacked", null, null, null));
          repository().deleteEntityType(a);
          repository().updateDomain(ontologyId, "Hacked");
          return null;
        });

    assertThat(read().entities())
        .extracting(OntologyResponse.EntityType::type)
        .containsExactly("A");
    assertThat(read().domain()).isNotEqualTo("Hacked");
  }
}
