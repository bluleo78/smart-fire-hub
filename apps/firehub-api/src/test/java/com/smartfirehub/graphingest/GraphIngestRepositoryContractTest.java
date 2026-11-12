package com.smartfirehub.graphingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.graphingest.dto.GraphIngestRecord;
import com.smartfirehub.graphingest.dto.GraphIngestRecord.StaleRow;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GraphIngestRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485·WD-711).
 *
 * <p>(WD-711) jOOQ DSLContext 직접 사용과 고정 테넌트·{@code dataset_id >= 9000} 정리 규칙을 걷어 냈다. 시험마다 새 테넌트
 * 둘(A·B)을 만들고 픽스처가 적재 이력·온톨로지·연결을 소유자 시점으로 심는다. 리포지토리 호출은 테넌트 범위(inA/inB)로 감싼다 — PG 는 테넌트 트랜잭션에 클래스
 * 단위 {@code @Transactional} 이 합류하고, Tibero 는 하네스 프록시가 호출마다 테넌트 트랜잭션을 연다. 이력은 데이터셋 FK 가 없어 실제 데이터셋
 * 행이 필요한 시나리오(가시성)만 데이터셋을 만든다.
 */
public abstract class GraphIngestRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract GraphIngestRepository repository();

  private static final LocalDateTime T0 = LocalDateTime.of(2024, 1, 2, 3, 4, 5);

  private DatasetVisibility unrestricted() {
    return DatasetVisibility.UNRESTRICTED;
  }

  private List<StaleRow> staleA(DatasetVisibility v) {
    return inA(() -> repository().findStale(v));
  }

  /** 테넌트 A 의 온톨로지를 만들고 schema_version 을 정한다. */
  private long ontologyA(int schemaVersion) {
    long o =
        fx.createOntology(tenantA, prefix + "-" + UUID.randomUUID().toString().substring(0, 6));
    fx.setOntologySchemaVersion(o, schemaVersion);
    return o;
  }

  @Test
  @DisplayName("save 는 생성 id 를 돌려주고 모든 열이 findByDataset 로 왕복된다")
  void save_roundTripsAllColumns() {
    long id = inA(() -> repository().save(9001L, 2, 10, 20, 15, 3, "PARTIAL"));

    List<GraphIngestRecord> rows = inA(() -> repository().findByDataset(9001L));

    assertThat(rows).hasSize(1);
    GraphIngestRecord r = rows.get(0);
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.datasetId()).isEqualTo(9001L);
    assertThat(r.schemaVersionAtIngest()).isEqualTo(2);
    assertThat(r.chunkCount()).isEqualTo(10);
    assertThat(r.nodeCount()).isEqualTo(20);
    assertThat(r.edgeCount()).isEqualTo(15);
    assertThat(r.extractionFailures()).isEqualTo(3);
    assertThat(r.status()).isEqualTo("PARTIAL");
    assertThat(r.ingestedAt()).isNotNull();
  }

  @Test
  @DisplayName("save 두 번 → findByDataset 은 최신순으로 두 행을 돌려준다")
  void save_and_findByDataset_returnsNewestFirst() {
    inA(() -> repository().save(9001L, 1, 10, 20, 15, 0, "SUCCESS"));
    inA(() -> repository().save(9001L, 1, 11, 22, 16, 2, "PARTIAL"));

    var rows = inA(() -> repository().findByDataset(9001L));

    assertThat(rows).hasSize(2);
    // 최신순(DESC) 정렬 검증
    assertThat(rows.get(0).ingestedAt()).isAfterOrEqualTo(rows.get(1).ingestedAt());
    assertThat(rows).extracting("status").contains("SUCCESS", "PARTIAL");
  }

  @Test
  @DisplayName("시각이 다르면 최신 먼저, 같으면 id 큰 것 먼저")
  void findByDataset_ordersByTimeThenIdDesc() {
    long old = fx.insertGraphIngest(tenantA, 9002L, 1, T0);
    long tieLow = fx.insertGraphIngest(tenantA, 9002L, 1, T0.plusHours(1));
    long tieHigh = fx.insertGraphIngest(tenantA, 9002L, 1, T0.plusHours(1));

    var rows = inA(() -> repository().findByDataset(9002L));

    assertThat(rows).extracting(GraphIngestRecord::id).containsExactly(tieHigh, tieLow, old);
    assertThat(rows.get(2).ingestedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("findByDataset 은 다른 데이터셋·다른 테넌트의 이력을 섞지 않는다")
  void findByDataset_isolatesDatasetAndTenant() {
    inA(() -> repository().save(9003L, 1, 1, 1, 1, 0, "SUCCESS"));
    inA(() -> repository().save(9004L, 1, 1, 1, 1, 0, "SUCCESS"));
    fx.insertGraphIngest(tenantB, 9003L, 1, T0);

    assertThat(inA(() -> repository().findByDataset(9003L))).hasSize(1);
    assertThat(inB(() -> repository().findByDataset(9003L))).hasSize(1);
    assertThat(inA(() -> repository().findByDataset(9999L))).isEmpty();
  }

  @Test
  @DisplayName("save 한 이력은 호출 테넌트의 것으로 저장된다(tenant_id 는 컨텍스트가 채운다)")
  void save_fillsTenantFromContext() {
    inB(() -> repository().save(9005L, 1, 1, 1, 1, 0, "SUCCESS"));

    assertThat(inA(() -> repository().findByDataset(9005L))).isEmpty();
    assertThat(inB(() -> repository().findByDataset(9005L))).hasSize(1);
  }

  @Test
  @DisplayName("findStale: 바인딩 온톨로지 버전보다 낮게 적재된 데이터셋만, 값과 함께 돌려준다")
  void findStale_returnsDatasetsBelowBoundOntologyVersion_latestRowOnly() {
    long ontologyId = ontologyA(3);
    fx.bindDatasetOntology(tenantA, 9101L, ontologyId);
    fx.bindDatasetOntology(tenantA, 9102L, ontologyId);
    fx.insertGraphIngest(tenantA, 9101L, 1, T0); // v1 적재(낡음, 바인딩 온톨로지 v3 미만)
    fx.insertGraphIngest(tenantA, 9102L, 3, T0); // v3 적재(최신, 낡지 않음)

    var stale = staleA(unrestricted());

    assertThat(stale).extracting(StaleRow::datasetId).containsExactly(9101L);
    StaleRow row = stale.get(0);
    assertThat(row.latestIngestedAt()).isEqualTo(T0);
    assertThat(row.schemaVersionAtIngest()).isEqualTo(1);
    assertThat(row.currentSchemaVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName("findStale: 데이터셋별 최신 적재행만 본다 — 과거 v1 이어도 이후 v3 로 재적재됐으면 낡지 않음")
  void findStale_usesOnlyLatestRowPerDataset() {
    long ontologyId = ontologyA(3);
    fx.bindDatasetOntology(tenantA, 9201L, ontologyId);
    fx.insertGraphIngest(tenantA, 9201L, 1, T0);
    fx.insertGraphIngest(tenantA, 9201L, 3, T0.plusDays(1));

    assertThat(staleA(unrestricted())).isEmpty();
  }

  @Test
  @DisplayName("findStale: 최신이 낡은 쪽이면(과거 v3, 이후 v1) 낡음으로 돌려준다")
  void findStale_latestOlderVersionIsStale() {
    long ontologyId = ontologyA(3);
    fx.bindDatasetOntology(tenantA, 9202L, ontologyId);
    fx.insertGraphIngest(tenantA, 9202L, 3, T0);
    fx.insertGraphIngest(tenantA, 9202L, 1, T0.plusDays(1));

    assertThat(staleA(unrestricted())).extracting(StaleRow::datasetId).containsExactly(9202L);
  }

  @Test
  @DisplayName("findStale: 최신 시각이 같으면 id 큰 행이 최신이다")
  void findStale_sameTimestampBreaksTieById() {
    long ontologyId = ontologyA(3);
    fx.bindDatasetOntology(tenantA, 9203L, ontologyId);
    fx.bindDatasetOntology(tenantA, 9204L, ontologyId);
    // 9203: id 큰 행이 v3 → 낡지 않음. 9204: id 큰 행이 v1 → 낡음.
    fx.insertGraphIngest(tenantA, 9203L, 1, T0);
    fx.insertGraphIngest(tenantA, 9203L, 3, T0);
    fx.insertGraphIngest(tenantA, 9204L, 3, T0);
    fx.insertGraphIngest(tenantA, 9204L, 1, T0);

    assertThat(staleA(unrestricted())).extracting(StaleRow::datasetId).containsExactly(9204L);
  }

  @Test
  @DisplayName("findStale: 바인딩이 없는 데이터셋은 결과에서 제외된다")
  void findStale_바인딩이_없는_데이터셋은_결과에서_제외된다() {
    // 적재 이력만 있고 dataset_ontology 바인딩이 없는 데이터셋은 INNER JOIN 에서 걸러진다(적재 자체가 바인딩 전제).
    fx.insertGraphIngest(tenantA, 9301L, 0, T0);

    assertThat(staleA(unrestricted())).isEmpty();
  }

  @Test
  @DisplayName("findStale: 데이터셋마다 자기 바인딩 온톨로지 버전과 비교한다")
  void findStale_comparesAgainstOwnBoundOntology() {
    long v2 = ontologyA(2);
    long v5 = ontologyA(5);
    fx.bindDatasetOntology(tenantA, 9401L, v2);
    fx.bindDatasetOntology(tenantA, 9402L, v5);
    fx.insertGraphIngest(tenantA, 9401L, 2, T0); // v2 vs v2 → 최신
    fx.insertGraphIngest(tenantA, 9402L, 2, T0); // v2 vs v5 → 낡음

    assertThat(staleA(unrestricted())).extracting(StaleRow::datasetId).containsExactly(9402L);
  }

  @Test
  @DisplayName("findStale: 다른 테넌트의 낡은 적재는 보이지 않는다")
  void findStale_doesNotLeakOtherTenant() {
    long ontologyA = ontologyA(3);
    long ontologyB = fx.createOntology(tenantB, prefix + "-b");
    fx.setOntologySchemaVersion(ontologyB, 3);
    fx.bindDatasetOntology(tenantA, 9501L, ontologyA);
    fx.bindDatasetOntology(tenantB, 9502L, ontologyB);
    fx.insertGraphIngest(tenantA, 9501L, 1, T0);
    fx.insertGraphIngest(tenantB, 9502L, 1, T0);

    assertThat(staleA(unrestricted())).extracting(StaleRow::datasetId).containsExactly(9501L);
    assertThat(inB(() -> repository().findStale(unrestricted())))
        .extracting(StaleRow::datasetId)
        .containsExactly(9502L);
  }

  @Test
  @DisplayName("findStale: 볼 수 없는 등급의 데이터셋은 빠지고, 데이터셋 행이 없는 이력은 그대로 남는다")
  void findStale_hidesInvisibleDatasetButKeepsOrphanHistory() {
    long ontologyId = ontologyA(3);
    long user = fx.createUser(prefix + "-u", null);
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long open = fx.createDataset(tenantA, prefix + "-open");
    long secret = fx.createDataset(tenantA, prefix + "-secret");
    fx.setDatasetField(secret, "security_level_id", secretLevel);
    long orphan = 9601L; // 데이터셋 행 없음
    for (long d : new long[] {open, secret, orphan}) {
      fx.bindDatasetOntology(tenantA, d, ontologyId);
      fx.insertGraphIngest(tenantA, d, 1, T0);
    }
    DatasetVisibility rank2 =
        new DatasetVisibility(new Clearance(user, tenantA, 2, Set.of(), false, Set.of()), null);

    assertThat(staleA(rank2))
        .extracting(StaleRow::datasetId)
        .containsExactlyInAnyOrder(open, orphan);
    assertThat(staleA(unrestricted()))
        .extracting(StaleRow::datasetId)
        .containsExactlyInAnyOrder(open, secret, orphan);
    DatasetVisibility none = new DatasetVisibility(Clearance.none(user, tenantA), null);
    assertThat(staleA(none)).extracting(StaleRow::datasetId).containsExactly(orphan);
  }
}
