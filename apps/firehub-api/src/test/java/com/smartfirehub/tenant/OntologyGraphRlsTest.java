package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V102 가 켠 온톨로지·그래프 8테이블의 테넌트 격리를 양방향으로 검증한다.
 *
 * <p>왜 양방향인가: "다른 테넌트에서 0행" 단방향 단언은 빈 테이블에서 공허하게 통과한다(P1 에서
 * 실제로 결함을 통과시킨 전례가 있다). 소유 테넌트에서 실제로 보이는 것을 함께 확인해야 의미가 있다.
 *
 * <p>이 클래스에 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러
 * 트랜잭션을 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다. 더 중요하게는
 * 테스트 트랜잭션이 GUC 를 공급해 프로덕션 배선 결함을 영구히 가린다.
 *
 * <p>테스트 커넥션은 비특권 롤 {@code app_tenant}(NOBYPASSRLS, V83)로 접속한다 — 즉 픽스처 생성·
 * 정리·검증 조회에도 정책이 적용된다. 그래서 그 셋 모두 {@code runInTenantTransaction} 안에서 한다.
 * bare {@code dsl} 로 지우면 0행 삭제가 되고 뒤이은 부모 삭제가 FK 로 터진다.
 *
 * <p>공유 테스트 DB 라 전체 카운트 비교 단언은 쓸 수 없다(다른 세션이 동시에 쓴다). 실행마다 고유한
 * 테넌트 두 개를 만들어 그 범위에서만 단언한다.
 *
 * <p>범위 주의: 이 밴드는 RDB 온톨로지 스키마만 격리한다. Neo4j 그래프 자체는 여전히 미격리다.
 */
class OntologyGraphRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  private long tenantA;
  private long tenantB;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "onto-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "onto-b");
  }

  @AfterEach
  void tearDown() {
    // RLS 스코프 안에서 지우므로 자기 테넌트 행만 지워진다(남의 행은 애초에 보이지 않는다).
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
  }

  /**
   * 해당 테넌트 컨텍스트에서 이 테스트가 만든 8테이블 행을 FK 순서대로 지운다.
   *
   * <p>삭제 순서와 {@code tenant_id} 스코핑은 {@link TenantRlsTestSupport#deleteOntologyGraphCascade}
   * 가 소유한다(RBAC 밴드의 {@code deleteRbacCascade} 선례).
   */
  private void deleteOwnRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantId, () -> TenantRlsTestSupport.deleteOntologyGraphCascade(dsl, tenantId));
  }

  // ── 8테이블 양방향 격리 ─────────────────────────────────────────────────

  @Test
  @DisplayName("ontology 는 테넌트 간 양방향으로 격리된다")
  void ontologyIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "ontology", () -> insertOntology("격리검증"));
  }

  @Test
  @DisplayName("ontology_entity_type 은 테넌트 간 양방향으로 격리된다")
  void entityTypeIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "ontology_entity_type",
        () -> insertEntityType(insertOntology("격리검증-타입")));
  }

  @Test
  @DisplayName("ontology_entity_property 는 테넌트 간 양방향으로 격리된다")
  void entityPropertyIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "ontology_entity_property",
        () -> insertProperty(insertEntityType(insertOntology("격리검증-속성"))));
  }

  @Test
  @DisplayName("ontology_relation 은 테넌트 간 양방향으로 격리된다")
  void relationIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "ontology_relation", this::insertRelationTree);
  }

  @Test
  @DisplayName("dataset_mapping 은 테넌트 간 양방향으로 격리된다")
  void datasetMappingIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "dataset_mapping",
        () -> insertMapping(TenantRlsTestSupport.nextTenantId(), insertOntology("격리검증-매핑")));
  }

  @Test
  @DisplayName("dataset_graph_ingest 는 테넌트 간 양방향으로 격리된다")
  void graphIngestIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "dataset_graph_ingest", this::insertGraphIngest);
  }

  @Test
  @DisplayName("graph_review_item 은 테넌트 간 양방향으로 격리된다")
  void reviewItemIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "graph_review_item", this::insertReviewItem);
  }

  @Test
  @DisplayName("dataset_ontology 는 테넌트 간 양방향으로 격리된다")
  void datasetOntologyIsIsolated() {
    // 이 테이블만 서로게이트 id PK 가 없다 — 행 식별 컬럼을 dataset_id 로 넘겨 나머지 7테이블과
    // 똑같은 3다리 단언(소유자에게 보임 / 남에게 안 보임 / tenant_id DEFAULT)을 받는다.
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "dataset_ontology", "dataset_id", this::insertDatasetOntology);
  }

  // ── 접은 유니크 / fail-closed / WITH CHECK ──────────────────────────────

  @Test
  @DisplayName("같은 domain 을 두 테넌트가 각각 가질 수 있다 (V101 의 유니크 접기)")
  void sameDomainAllowedAcrossTenants() {
    // 유니크 인덱스는 RLS 와 무관하게 전역 적용된다 — V101 이 ontology_domain_unique 를
    // (tenant_id, domain) 으로 접지 않았다면 여기서 "보이지도 않는 행"과 충돌한다.
    // 이름을 고정해야 의미가 있으므로 테넌트별 접미사를 붙이지 않는다.
    String sharedDomain = "동일도메인-" + TenantRlsTestSupport.nextTenantId();

    Long a = TenantRlsTestSupport.runInTenantTransaction(tx, tenantA, () -> insertOntologyWithDomain(sharedDomain));
    Long b = TenantRlsTestSupport.runInTenantTransaction(tx, tenantB, () -> insertOntologyWithDomain(sharedDomain));

    assertThat(a).isNotNull();
    assertThat(b).isNotNull().isNotEqualTo(a);
  }

  @Test
  @DisplayName("컨텍스트가 없으면 자기 ontology 도 보이지 않는다 (fail-closed)")
  void failsClosedWithoutContext() {
    TenantRlsTestSupport.assertFailsClosedWithoutContext(
        tx, dsl, tenantA, "ontology", () -> insertOntology("무컨텍스트"));
  }

  @Test
  @DisplayName("다른 테넌트 id 로 ontology 를 심으려 하면 WITH CHECK 가 거부한다")
  void insertWithMismatchedTenantIsRejected() {
    TenantRlsTestSupport.assertCrossTenantInsertRejected(
        tx,
        tenantA,
        "ontology",
        () ->
            dsl.execute(
                "insert into ontology (domain, status, tenant_id) values (?, 'active', ?)",
                "교차삽입시도-" + TenantRlsTestSupport.nextTenantId(),
                tenantB));
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────
  //
  // 전부 bare 삽입이다 — 호출자(assertTwoSidedIsolation 또는 runInTenantTransaction)가 이미 소유
  // 테넌트 트랜잭션을 열어 둔 안에서 실행된다. 여기서 또 트랜잭션을 열면 중첩이 된다.
  // tenant_id 는 어디에서도 명시하지 않는다 — V101 이 심은 컬럼 DEFAULT 가 GUC 에서 채우는 것을
  // 함께 검증하기 위함이다.

  /** ontology_domain_unique(tenant_id, domain) 때문에 domain 은 실행마다 고유해야 한다. */
  private Long insertOntology(String domainPrefix) {
    return insertOntologyWithDomain(domainPrefix + "-" + TenantRlsTestSupport.nextTenantId());
  }

  private Long insertOntologyWithDomain(String domain) {
    return (Long)
        dsl.fetchValue(
            "insert into ontology (domain, status) values (?, 'active') returning id", domain);
  }

  private Long insertEntityType(Long ontologyId) {
    return (Long)
        dsl.fetchValue(
            "insert into ontology_entity_type"
                + " (ontology_id, type, description, naming, resolution, sort_order)"
                + " values (?, ?, '격리 검증용', '검증', 'exact', 1) returning id",
            ontologyId,
            "Type" + TenantRlsTestSupport.nextTenantId());
  }

  private Long insertProperty(Long entityTypeId) {
    return (Long)
        dsl.fetchValue(
            "insert into ontology_entity_property"
                + " (entity_type_id, name, description, data_type, sort_order)"
                + " values (?, ?, '격리 검증용', 'text', 1) returning id",
            entityTypeId,
            "prop" + TenantRlsTestSupport.nextTenantId());
  }

  /** 관계는 같은 온톨로지 안의 주어·목적어 엔티티 타입 두 개를 먼저 만들어야 한다. */
  private Long insertRelationTree() {
    Long ontologyId = insertOntology("격리검증-관계");
    Long subject = insertEntityType(ontologyId);
    Long object = insertEntityType(ontologyId);
    return (Long)
        dsl.fetchValue(
            "insert into ontology_relation"
                + " (ontology_id, relation, description, sort_order, subject_type_id, object_type_id)"
                + " values (?, ?, '격리 검증용', 1, ?, ?) returning id",
            ontologyId,
            "REL" + TenantRlsTestSupport.nextTenantId(),
            subject,
            object);
  }

  /**
   * dataset_id 에는 FK 가 없다(감사 패턴) — 실제 dataset 행 없이 임의 id 를 써도 된다. 이 테이블은
   * 서로게이트 id 가 없으므로 행 식별자로 쓸 dataset_id 를 그대로 돌려준다.
   */
  private Long insertDatasetOntology() {
    long datasetId = TenantRlsTestSupport.nextTenantId();
    dsl.execute(
        "insert into dataset_ontology (dataset_id, ontology_id) values (?, ?)",
        datasetId,
        insertOntology("격리검증-바인딩"));
    return datasetId;
  }

  private Long insertMapping(long datasetId, Long ontologyId) {
    return (Long)
        dsl.fetchValue(
            "insert into dataset_mapping (dataset_id, ontology_id, spec, status)"
                + " values (?, ?, '{}'::jsonb, 'draft') returning id",
            datasetId,
            ontologyId);
  }

  private Long insertGraphIngest() {
    return (Long)
        dsl.fetchValue(
            "insert into dataset_graph_ingest (dataset_id, schema_version_at_ingest)"
                + " values (?, 1) returning id",
            TenantRlsTestSupport.nextTenantId());
  }

  /** uq_graph_review_item(tenant_id, item_type, dedupe_key) 때문에 dedupe_key 는 고유해야 한다. */
  private Long insertReviewItem() {
    return (Long)
        dsl.fetchValue(
            "insert into graph_review_item (item_type, payload, dedupe_key)"
                + " values ('synonym', '{}'::jsonb, ?) returning id",
            "dedupe-" + TenantRlsTestSupport.nextTenantId());
  }
}
