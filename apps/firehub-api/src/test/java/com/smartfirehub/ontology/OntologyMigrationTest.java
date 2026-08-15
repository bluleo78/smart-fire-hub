package com.smartfirehub.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

// V71 시드 검증 — CORE_ONTOLOGY 원본이 순서·문자열 그대로 적재됐는지 확인한다(바이트 동일성 보증의 DB 측 근거).
// V72 시드(엔티티 데이터 프로퍼티) 검증도 함께 포함한다.
//
// V102 로 온톨로지 8테이블에 RLS 가 걸린 뒤로는, 이 테스트가 직접 쏘는 raw dsl 조회도 정책의 대상이다.
// GUC 는 트랜잭션이 열릴 때만 주입되므로 트랜잭션 밖 SELECT 는 "조용히 0행"이 된다. 그래서 raw 조회만
// runInTenantTransaction 으로 감싼다 — 클래스 레벨 @Transactional 은 쓰지 않는다(프로덕션 배선 결함을
// 테스트가 대신 공급해 영구히 가린다).
class OntologyMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private OntologyRepository ontologyRepository;
  @Autowired private TransactionTemplate tx;

  /** 시드 행은 기본 테넌트(1) 소유다 — 그 컨텍스트의 트랜잭션 안에서 읽어야 정책을 통과한다. */
  private <T> T readAsDefaultTenant(java.util.function.Supplier<T> query) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, DEFAULT_TEST_TENANT_ID, query);
  }

  private static final Table<?> ET = table(name("ontology_entity_type"));
  private static final Field<String> ET_TYPE = field(name("ontology_entity_type", "type"), String.class);
  private static final Field<String> ET_RES = field(name("ontology_entity_type", "resolution"), String.class);
  private static final Field<Integer> ET_ORDER = field(name("ontology_entity_type", "sort_order"), Integer.class);
  private static final Field<Long> ET_ID = field(name("ontology_entity_type", "id"), Long.class);
  private static final Table<?> REL = table(name("ontology_relation"));
  private static final Field<String> REL_RELATION = field(name("ontology_relation", "relation"), String.class);
  private static final Field<Integer> REL_ORDER = field(name("ontology_relation", "sort_order"), Integer.class);
  private static final Field<Long> REL_SUBJ_ID = field(name("ontology_relation", "subject_type_id"), Long.class);
  private static final Field<Long> REL_OBJ_ID = field(name("ontology_relation", "object_type_id"), Long.class);

  @Test
  void 시드_엔티티는_원본_순서로_6개_적재된다() {
    List<String> types =
        readAsDefaultTenant(() -> dsl.select(ET_TYPE).from(ET).orderBy(ET_ORDER).fetch(r -> r.get(ET_TYPE)));
    assertThat(types).containsExactly("Incident", "Building", "Cause", "Damage", "Equipment", "Regulation");
    // resolution 정책 6종 전부 순서대로 검증한다. resolution 은 추출 프롬프트에 실리지 않아
    // "프롬프트 바이트 동일" 회귀가 커버하지 못하므로(오직 semantic-resolver 병합 정책에만 영향),
    // 여기서 Incident/Damage=exact, 나머지=embedding 을 명시적으로 단언해 시드 오류를 잡는다.
    List<String> resolutions =
        readAsDefaultTenant(() -> dsl.select(ET_RES).from(ET).orderBy(ET_ORDER).fetch(r -> r.get(ET_RES)));
    assertThat(resolutions).containsExactly("exact", "embedding", "embedding", "exact", "embedding", "embedding");
  }

  @Test
  void 시드_관계는_원본_순서로_6개_적재된다() {
    List<String> rels =
        readAsDefaultTenant(
            () -> dsl.select(REL_RELATION).from(REL).orderBy(REL_ORDER).fetch(r -> r.get(REL_RELATION)));
    assertThat(rels).containsExactly(
        "OCCURRED_AT", "CAUSED_BY", "RESULTED_IN", "HAS_EQUIPMENT", "VIOLATED", "GOVERNED_BY");
  }

  // V72 시드: Incident 는 '피해액'(number, 원) 속성 1개를 가져야 한다. 다른 타입은 속성 없음.
  @Test
  void incident_hasDamageAmountProperty() {
    OntologyResponse res = ontologyRepository.findOntology();
    OntologyResponse.EntityType incident = res.entities().stream()
        .filter(e -> e.type().equals("Incident")).findFirst().orElseThrow();
    assertThat(incident.properties()).hasSize(1);
    OntologyResponse.Property p = incident.properties().get(0);
    assertThat(p.name()).isEqualTo("피해액");
    assertThat(p.dataType()).isEqualTo("number");
    assertThat(p.unit()).isEqualTo("원");
    // 속성 미정의 타입은 빈 목록.
    OntologyResponse.EntityType building = res.entities().stream()
        .filter(e -> e.type().equals("Building")).findFirst().orElseThrow();
    assertThat(building.properties()).isEmpty();
  }

  // V71 시드의 schema_version(=1)이 응답/조회로 노출되어야 한다.
  @Test
  void ontology_exposesSchemaVersion() {
    OntologyResponse res = ontologyRepository.findOntology();
    assertThat(res.schemaVersion()).isEqualTo(1);
    assertThat(ontologyRepository.currentSchemaVersion()).isEqualTo(1);
  }

  // V80: TEXT 이름 참조를 FK로 옮긴 뒤에도 시드 6개 트리플이 "같은 타입 쌍"을 가리켜야 한다.
  // 이름이 아니라 id로 확인한다 — 백필이 엉뚱한 타입에 붙어도 이름 비교로는 드러나지 않기 때문이다.
  @Test
  void V80_백필은_시드_관계를_올바른_타입_id_쌍으로_옮긴다() {
    Map<String, Long> idByType =
        readAsDefaultTenant(
            () -> dsl.select(ET_TYPE, ET_ID).from(ET).fetch().intoMap(r -> r.get(ET_TYPE), r -> r.get(ET_ID)));

    List<String> actual =
        readAsDefaultTenant(
            () ->
                dsl.select(REL_SUBJ_ID, REL_RELATION, REL_OBJ_ID)
                    .from(REL)
                    .orderBy(REL_ORDER)
                    .fetch(r -> nameOf(idByType, r.get(REL_SUBJ_ID))
                        + "|" + r.get(REL_RELATION)
                        + "|" + nameOf(idByType, r.get(REL_OBJ_ID))));

    assertThat(actual).containsExactly(
        "Incident|OCCURRED_AT|Building",
        "Incident|CAUSED_BY|Cause",
        "Incident|RESULTED_IN|Damage",
        "Building|HAS_EQUIPMENT|Equipment",
        "Incident|VIOLATED|Regulation",
        "Equipment|GOVERNED_BY|Regulation");
  }

  // id → 타입명 역조회(테스트 가독성용).
  private static String nameOf(Map<String, Long> idByType, Long id) {
    return idByType.entrySet().stream()
        .filter(e -> e.getValue().equals(id))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new AssertionError("알 수 없는 entity_type_id: " + id));
  }

  // 요소 단위 편집 API는 이름이 아니라 id로 대상을 지목한다. 읽기 응답이 그 id를 실어 주지 않으면
  // 클라이언트가 무엇을 수정할지 지목할 방법이 없다.
  @Test
  void 읽기_응답은_관계와_속성의_안정_id를_함께_노출한다() {
    OntologyResponse res = ontologyRepository.findOntology();

    assertThat(res.relations()).isNotEmpty();
    assertThat(res.relations()).allSatisfy(t -> {
      assertThat(t.id()).isNotNull();
      assertThat(t.subjectTypeId()).isNotNull();
      assertThat(t.objectTypeId()).isNotNull();
    });

    // subjectTypeId는 같은 이름의 엔티티 타입 id와 일치해야 한다(엉뚱한 id를 채우지 않았다는 확인).
    Map<String, Long> idByType =
        res.entities().stream().collect(java.util.stream.Collectors.toMap(
            OntologyResponse.EntityType::type, OntologyResponse.EntityType::id));
    assertThat(res.relations()).allSatisfy(t -> {
      assertThat(t.subjectTypeId()).isEqualTo(idByType.get(t.subject()));
      assertThat(t.objectTypeId()).isEqualTo(idByType.get(t.object()));
    });

    OntologyResponse.EntityType incident = res.entities().stream()
        .filter(e -> e.type().equals("Incident")).findFirst().orElseThrow();
    assertThat(incident.properties().get(0).id()).isNotNull();
  }
}
