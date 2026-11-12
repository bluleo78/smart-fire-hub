package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GraphOntologySourceRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485·WD-711).
 *
 * <p>시험마다 새 테넌트 둘(A·B)을 만들고 픽스처가 출처·이력·데이터셋을 소유자 시점으로 심는다. 리포지토리 호출은 테넌트 범위(inA/inB)로 감싼다. 출처는 데이터셋
 * FK 가 없어 데이터셋을 지워도 남는다(삭제된 데이터셋 출처). 차단 판정(existsUnreadableSource)이 안 보이는 등급·삭제된 데이터셋을 가려내고, 1000
 * 개 넘는 id 도 Tibero 에서 터지지 않는지(Review Focus 3)를 함께 본다.
 */
abstract class GraphOntologySourceRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract GraphOntologySourceRepository repository();

  private long ontologyA;
  private long ontologyA2;
  private long ontologyB;
  private long user;

  @BeforeEach
  void setUpOntologies() {
    String s = UUID.randomUUID().toString().substring(0, 8);
    ontologyA = fx.createOntology(tenantA, "gs-a-" + s);
    ontologyA2 = fx.createOntology(tenantA, "gs-a2-" + s);
    ontologyB = fx.createOntology(tenantB, "gs-b-" + s);
    user = fx.createUser(prefix + "-u", null);
  }

  private DatasetVisibility rank(int rank) {
    return new DatasetVisibility(
        new Clearance(user, tenantA, rank, Set.of(), false, Set.of()), null);
  }

  private boolean unreadable(long ontologyId, DatasetVisibility v, boolean deletedReadable) {
    return inA(() -> repository().existsUnreadableSource(ontologyId, v, deletedReadable));
  }

  @Test
  @DisplayName("record 는 출처를 남기고 findDatasetIds 가 돌려준다 — tenant_id 는 컨텍스트가 채운다")
  void record_thenFindDatasetIds() {
    runA(() -> repository().record(ontologyA, 7001L));
    runA(() -> repository().record(ontologyA, 7002L));
    runA(() -> repository().record(ontologyA2, 7003L));

    assertThat(inA(() -> repository().findDatasetIds(ontologyA)))
        .containsExactlyInAnyOrder(7001L, 7002L);
    assertThat(inA(() -> repository().findDatasetIds(ontologyA2))).containsExactly(7003L);
    assertThat(fx.countGraphSources(tenantA, ontologyA, 7001L)).isEqualTo(1);
    assertThat(inA(() -> repository().findDatasetIds(999_999_999L))).isEmpty();
  }

  @Test
  @DisplayName("같은 출처를 다시 남겨도 한 행이다(멱등)")
  void record_isIdempotent() {
    runA(() -> repository().record(ontologyA, 7101L));
    runA(() -> repository().record(ontologyA, 7101L));

    assertThat(fx.countGraphSources(tenantA, ontologyA, 7101L)).isEqualTo(1);
    assertThat(inA(() -> repository().findDatasetIds(ontologyA))).containsExactly(7101L);
  }

  @Test
  @DisplayName("같은 데이터셋 id 를 두 테넌트가 각자 남겨도 서로 덮지 않는다")
  void record_sameDatasetIdTwoTenants() {
    runA(() -> repository().record(ontologyA, 7201L));
    inB(
        () -> {
          repository().record(ontologyB, 7201L);
          return null;
        });

    assertThat(fx.countGraphSources(tenantA, ontologyA, 7201L)).isEqualTo(1);
    assertThat(fx.countGraphSources(tenantB, ontologyB, 7201L)).isEqualTo(1);
    assertThat(inA(() -> repository().findDatasetIds(ontologyB))).isEmpty();
  }

  @Test
  @DisplayName("삭제된 데이터셋의 출처도 findDatasetIds 에 포함된다")
  void findDatasetIds_includesDeletedDatasets() {
    long ds = fx.createDataset(tenantA, prefix + "-gone");
    runA(() -> repository().record(ontologyA, ds));
    fx.deleteDataset(ds);

    assertThat(inA(() -> repository().findDatasetIds(ontologyA))).containsExactly(ds);
  }

  @Test
  @DisplayName("existsUnreadableSource: 모두 보이면 false, 안 보이는 등급 출처가 하나라도 있으면 true")
  void existsUnreadableSource_visibility() {
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long open = fx.createDataset(tenantA, prefix + "-open");
    long secret = fx.createDataset(tenantA, prefix + "-secret");
    fx.setDatasetField(secret, "security_level_id", secretLevel);

    assertThat(unreadable(ontologyA, rank(2), false)).isFalse(); // 출처 없음

    runA(() -> repository().record(ontologyA, open));
    assertThat(unreadable(ontologyA, rank(2), false)).isFalse();

    runA(() -> repository().record(ontologyA, secret));
    assertThat(unreadable(ontologyA, rank(2), false)).isTrue();
    assertThat(unreadable(ontologyA, rank(5), false)).isFalse();
    assertThat(unreadable(ontologyA, DatasetVisibility.UNRESTRICTED, false)).isFalse();
    // 등급 없는 자격(NO_RANK)은 존재하는 출처를 하나도 못 본다.
    assertThat(
            unreadable(
                ontologyA, new DatasetVisibility(Clearance.none(user, tenantA), null), false))
        .isTrue();
    // 다른 온톨로지는 영향받지 않는다.
    assertThat(unreadable(ontologyA2, rank(2), false)).isFalse();
  }

  @Test
  @DisplayName(
      "existsUnreadableSource: 시험 트랜잭션 없이 테넌트 컨텍스트만 세워도 안 보이는 출처를 true 로 판정한다(fail-open 아님 — 리포지토리 @Transactional 이 테넌트를 심는다)")
  void existsUnreadableSource_withoutTestTransaction() {
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long secret = fx.createDataset(tenantA, prefix + "-secret");
    fx.setDatasetField(secret, "security_level_id", secretLevel);
    runA(() -> repository().record(ontologyA, secret));

    // inA 는 테넌트 트랜잭션으로 감싼다 — 컨텍스트만 세워 리포지토리 자신의 트랜잭션이 정책 컨텍스트를 심는지 본다.
    // 트랜잭션이 없으면 출처가 0행으로 보여 false(전부 열림)가 된다.
    assertThat(
            TenantContext.runScopedGet(
                tenantA, () -> repository().existsUnreadableSource(ontologyA, rank(2), false)))
        .isTrue();
    assertThat(
            TenantContext.runScopedGet(
                tenantA, () -> repository().existsUnreadableSource(ontologyA, rank(5), false)))
        .isFalse();
  }

  @Test
  @DisplayName("existsUnreadableSource: 삭제된 데이터셋 출처는 deletedSourceReadable 이 false 일 때만 막는다")
  void existsUnreadableSource_deletedSource() {
    long open = fx.createDataset(tenantA, prefix + "-open");
    long gone = fx.createDataset(tenantA, prefix + "-gone");
    runA(() -> repository().record(ontologyA, open));
    runA(() -> repository().record(ontologyA, gone));
    fx.deleteDataset(gone);

    assertThat(unreadable(ontologyA, rank(2), false)).isTrue();
    assertThat(unreadable(ontologyA, rank(2), true)).isFalse();
    assertThat(unreadable(ontologyA, DatasetVisibility.UNRESTRICTED, false)).isTrue();
    assertThat(unreadable(ontologyA, DatasetVisibility.UNRESTRICTED, true)).isFalse();
  }

  @Test
  @DisplayName("existsUnreadableSource: 다른 테넌트의 출처·데이터셋은 판정에 섞이지 않는다")
  void existsUnreadableSource_ignoresOtherTenant() {
    // 테넌트 B 의 온톨로지에 삭제된 데이터셋 출처가 있어도 A 에서는 보이지 않는다(RLS).
    fx.insertGraphSource(tenantB, ontologyB, 7301L);

    assertThat(unreadable(ontologyB, rank(2), false)).isFalse();
    assertThat(inB(() -> repository().existsUnreadableSource(ontologyB, rank(2), false))).isTrue();
  }

  @Test
  @DisplayName("findDatasetsWithGraphHistory: 출처·적재 이력 합집합을 중복 없이 오름차순으로, 다른 테넌트는 섞지 않는다")
  void findDatasetsWithGraphHistory_unionSortedTenantScoped() {
    LocalDateTime t = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
    runA(() -> repository().record(ontologyA, 8003L)); // 출처만
    fx.insertGraphIngest(tenantA, 8001L, 1, t); // 이력만
    runA(() -> repository().record(ontologyA, 8002L)); // 둘 다
    fx.insertGraphIngest(tenantA, 8002L, 1, t);
    fx.insertGraphIngest(tenantA, 8002L, 1, t.plusDays(1)); // 이력 두 번도 한 번만
    fx.insertGraphSource(tenantB, ontologyB, 8004L); // 다른 테넌트
    fx.insertGraphIngest(tenantB, 8005L, 1, t);

    List<Long> ids = List.of(8005L, 8004L, 8003L, 8002L, 8001L, 8000L);

    assertThat(inA(() -> repository().findDatasetsWithGraphHistory(tenantA, ids)))
        .containsExactly(8001L, 8002L, 8003L);
    assertThat(inB(() -> repository().findDatasetsWithGraphHistory(tenantB, ids)))
        .containsExactly(8004L, 8005L);
    // 호출 테넌트와 다른 tenantId 를 명시하면 정책과 명시 조건이 겹쳐 아무것도 안 나온다.
    assertThat(inA(() -> repository().findDatasetsWithGraphHistory(tenantB, ids))).isEmpty();
  }

  @Test
  @DisplayName("findDatasetsWithGraphHistory: 빈 목록은 빈 결과")
  void findDatasetsWithGraphHistory_emptyInput() {
    assertThat(inA(() -> repository().findDatasetsWithGraphHistory(tenantA, List.of()))).isEmpty();
  }

  @Test
  @DisplayName("findDatasetsWithGraphHistory: id 가 1001 개를 넘어도(Tibero IN 상한) 결과가 맞다")
  void findDatasetsWithGraphHistory_moreThan1000Ids() {
    List<Long> ids = new ArrayList<>();
    for (long i = 0; i < 1201; i++) {
      ids.add(600_000L + i);
    }
    runA(() -> repository().record(ontologyA, 600_000L)); // 첫 청크
    fx.insertGraphIngest(tenantA, 600_700L, 1, LocalDateTime.of(2024, 1, 2, 3, 4, 5)); // 둘째 청크
    runA(() -> repository().record(ontologyA, 601_200L)); // 셋째 청크 끝

    assertThat(inA(() -> repository().findDatasetsWithGraphHistory(tenantA, ids)))
        .containsExactly(600_000L, 600_700L, 601_200L);
  }
}
