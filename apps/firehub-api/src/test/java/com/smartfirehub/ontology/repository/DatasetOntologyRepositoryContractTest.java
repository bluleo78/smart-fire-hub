package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.user.repository.AccessFixtures.DatasetOntologyRow;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetOntologyRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485·WD-711).
 *
 * <p>(WD-711) jOOQ DSLContext 직접 사용을 픽스처로 걷어 냈다. 저장 행은 소유자 시점({@code fx.datasetOntologyRows})으로
 * 확인한다. 리포지토리 호출은 테넌트 범위(inA/inB)로 감싼다 — PG 는 테넌트 트랜잭션에 리포지토리의 클래스 단위 {@code @Transactional} 이
 * 합류하고, Tibero 는 하네스 프록시가 호출마다 테넌트 트랜잭션을 연다. dataset_id 에는 FK 가 없어 실제 dataset 행은 만들지 않는다. 연결 행은
 * 온톨로지를 지울 때(테넌트 정리) FK CASCADE 로 함께 사라진다.
 *
 * <p>MyBatis 판은 Tibero 에서 MERGE ON dataset_id 를 쓴다 — 테넌트는 정책이 거르므로 같은 dataset_id 가 두 테넌트에 각자 연결돼도
 * 서로를 덮지 않아야 한다(아래 시나리오).
 */
abstract class DatasetOntologyRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DatasetOntologyRepository repository();

  private long ontologyA1;
  private long ontologyA2;
  private long ontologyB;

  /** 테넌트 A 에 온톨로지 둘, 테넌트 B 에 하나. */
  @BeforeEach
  void createOntologies() {
    String s = UUID.randomUUID().toString().substring(0, 8);
    ontologyA1 = fx.createOntology(tenantA, "do-a1-" + s);
    ontologyA2 = fx.createOntology(tenantA, "do-a2-" + s);
    ontologyB = fx.createOntology(tenantB, "do-b-" + s);
  }

  @Test
  @DisplayName("바인딩을 저장하면 조회되고 bound_by·bound_at 이 채워진다")
  void 바인딩_저장후_조회된다() {
    long datasetId = 999_001L;
    runA(() -> repository().bind(datasetId, ontologyA1, 42L));

    assertThat(inA(() -> repository().findOntologyIdByDataset(datasetId))).contains(ontologyA1);
    List<DatasetOntologyRow> rows = fx.datasetOntologyRows(tenantA, datasetId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).ontologyId()).isEqualTo(ontologyA1);
    assertThat(rows.get(0).boundBy()).isEqualTo(42L);
    // 연결은 출처(graph_ontology_source)도 같은 트랜잭션에서 남긴다(WD-28).
    assertThat(fx.countGraphSources(tenantA, ontologyA1, datasetId)).isEqualTo(1);
    // bound_at 은 UTC 순간으로 DB 현재 시각 근처(시간대 해석이 어긋나면 9시간 차이).
    assertThat(Duration.between(rows.get(0).boundAt(), fx.dbNow()).abs())
        .isLessThan(Duration.ofMinutes(1));
  }

  @Test
  @DisplayName(
      "시험 트랜잭션 없이 테넌트 컨텍스트만 세워도 bind·findOntologyIdByDataset 이 맞다 — 클래스 단위 @Transactional 이 테넌트를 심는다")
  void 트랜잭션_없이_컨텍스트만으로_동작한다() {
    long datasetId = 999_009L;

    // inA 는 테넌트 트랜잭션으로 감싼다 — 여기서는 컨텍스트만 세워 리포지토리 자신의 트랜잭션 경계를 시험한다.
    TenantContext.runScoped(tenantA, () -> repository().bind(datasetId, ontologyA1, 42L));

    assertThat(
            TenantContext.runScopedGet(
                tenantA, () -> repository().findOntologyIdByDataset(datasetId)))
        .contains(ontologyA1);
    // 행이 테넌트 A 로 들어갔다(트랜잭션이 없으면 tenant_id DEFAULT 가 비어 실패하거나 정책이 0행으로 거른다).
    assertThat(fx.datasetOntologyRows(tenantA, datasetId)).hasSize(1);
    assertThat(fx.countGraphSources(tenantA, ontologyA1, datasetId)).isEqualTo(1);
    assertThat(
            TenantContext.runScopedGet(
                tenantB, () -> repository().findOntologyIdByDataset(datasetId)))
        .isEmpty();
  }

  @Test
  @DisplayName("다른 온톨로지로 재바인딩하면 한 행에서 ontology_id·bound_by·bound_at 을 덮어쓴다")
  void 재바인딩은_기존값을_덮어쓴다() {
    long datasetId = 999_002L;
    runA(() -> repository().bind(datasetId, ontologyA1, 42L));
    fx.setDatasetOntologyBoundAt(tenantA, datasetId, Instant.parse("2020-01-02T03:04:05Z"));

    runA(() -> repository().bind(datasetId, ontologyA2, null)); // 업서트 경로(만든 이 없음).

    assertThat(inA(() -> repository().findOntologyIdByDataset(datasetId))).contains(ontologyA2);
    List<DatasetOntologyRow> rows = fx.datasetOntologyRows(tenantA, datasetId);
    assertThat(rows).hasSize(1); // (tenant_id, dataset_id) 유일 — 중복 행 없음.
    assertThat(rows.get(0).ontologyId()).isEqualTo(ontologyA2);
    assertThat(rows.get(0).boundBy()).isNull();
    assertThat(Duration.between(rows.get(0).boundAt(), fx.dbNow()).abs())
        .isLessThan(Duration.ofMinutes(1));
  }

  @Test
  @DisplayName("같은 온톨로지로 다시 바인딩해도 한 행이다")
  void 같은_온톨로지_재바인딩은_한_행이다() {
    long datasetId = 999_003L;
    runA(() -> repository().bind(datasetId, ontologyA1, 42L));
    runA(() -> repository().bind(datasetId, ontologyA1, 43L));

    List<DatasetOntologyRow> rows = fx.datasetOntologyRows(tenantA, datasetId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).boundBy()).isEqualTo(43L);
  }

  @Test
  @DisplayName("같은 dataset_id 를 두 테넌트가 각자 바인딩하면 서로 덮지 않고 각자 자기 것만 본다")
  void 같은_데이터셋_id_두_테넌트_바인딩은_섞이지_않는다() {
    long datasetId = 999_004L;
    runA(() -> repository().bind(datasetId, ontologyA1, 1L));
    fx.inTenant(
        tenantB,
        () -> {
          repository().bind(datasetId, ontologyB, 2L);
          return null;
        });

    assertThat(inA(() -> repository().findOntologyIdByDataset(datasetId))).contains(ontologyA1);
    assertThat(inB(() -> repository().findOntologyIdByDataset(datasetId))).contains(ontologyB);
    assertThat(fx.datasetOntologyRows(tenantA, datasetId))
        .extracting(DatasetOntologyRow::ontologyId)
        .containsExactly(ontologyA1);
    assertThat(fx.datasetOntologyRows(tenantB, datasetId))
        .extracting(DatasetOntologyRow::ontologyId)
        .containsExactly(ontologyB);
  }

  @Test
  @DisplayName("리포지토리가 쓰지 않은 연결 행도 조회되고, 미바인딩·다른 테넌트 데이터셋은 empty")
  void 미바인딩_데이터셋은_empty() {
    long datasetId = 998_877L;
    fx.bindDatasetOntology(tenantA, datasetId, ontologyA1);

    assertThat(inA(() -> repository().findOntologyIdByDataset(datasetId))).contains(ontologyA1);
    assertThat(inB(() -> repository().findOntologyIdByDataset(datasetId))).isEmpty();
    assertThat(inA(() -> repository().findOntologyIdByDataset(999_999L))).isEmpty();
  }
}
