package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetFavoriteRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). dataset_favorite 는 테넌트 정책 아래 있다.
 */
public abstract class DatasetFavoriteRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetFavoriteRepository repository();

  private long user1;
  private long user2;

  @BeforeEach
  void setUpExtra() {
    user1 = fx.createUser(prefix + "-u1", null);
    user2 = fx.createUser(prefix + "-u2", null);
  }

  private void insertA(long userId, long datasetId) {
    inA(
        () -> {
          repository().insert(userId, datasetId);
          return null;
        });
  }

  @Test
  @DisplayName("insert 는 즐겨찾기를 넣고, 두 번 넣어도 한 행·예외 없음")
  void insert_isIdempotent() {
    long ds = fx.createDataset(tenantA, prefix + " ds");

    assertThat(inA(() -> repository().existsByUserIdAndDatasetId(user1, ds))).isFalse();
    insertA(user1, ds);
    insertA(user1, ds);

    assertThat(inA(() -> repository().existsByUserIdAndDatasetId(user1, ds))).isTrue();
    assertThat(inA(() -> repository().existsByUserIdAndDatasetId(user2, ds))).isFalse();
    assertThat(inA(() -> repository().findDatasetIdsByUserId(user1))).containsExactly(ds);
  }

  @Test
  @DisplayName("delete 는 해제하고(없어도 조용히), findDatasetIdsByUserId 는 사용자별 집합")
  void delete_findDatasetIdsByUserId() {
    long ds1 = fx.createDataset(tenantA, prefix + " ds1");
    long ds2 = fx.createDataset(tenantA, prefix + " ds2");
    insertA(user1, ds1);
    insertA(user1, ds2);
    insertA(user2, ds1);

    assertThat(inA(() -> repository().findDatasetIdsByUserId(user1))).isEqualTo(Set.of(ds1, ds2));
    inA(
        () -> {
          repository().delete(user1, ds1);
          repository().delete(user1, ds1);
          return null;
        });
    assertThat(inA(() -> repository().findDatasetIdsByUserId(user1))).isEqualTo(Set.of(ds2));
    assertThat(inA(() -> repository().findDatasetIdsByUserId(user2))).isEqualTo(Set.of(ds1));
    assertThat(inA(() -> repository().findDatasetIdsByUserId(-1L))).isEmpty();
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트에서는 A 의 즐겨찾기가 보이지 않고 지워지지도 않는다")
  void otherTenantIsBlocked() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    insertA(user1, ds);

    assertThat(fx.inTenant(tenantB, () -> repository().existsByUserIdAndDatasetId(user1, ds)))
        .isFalse();
    assertThat(fx.inTenant(tenantB, () -> repository().findDatasetIdsByUserId(user1))).isEmpty();
    fx.inTenant(
        tenantB,
        () -> {
          repository().delete(user1, ds);
          return null;
        });
    assertThat(inA(() -> repository().existsByUserIdAndDatasetId(user1, ds))).isTrue();
  }
}
