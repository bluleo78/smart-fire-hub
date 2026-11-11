package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.rowsearch.SearchConfig;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SearchColumnRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). dataset_column 은 테넌트 정책 아래 있다. 리뷰 포인트
 * 3: 이름 목록이 1000 개를 넘어도(1001 개) Tibero 에서 터지지 않는다.
 */
public abstract class SearchColumnRepositoryContractTest extends TenantPairContractBase {

  protected abstract SearchColumnRepository repository();

  private void set(long ds, List<String> names) {
    inA(
        () -> {
          repository().setSearchable(ds, names);
          return null;
        });
  }

  private List<String> searchableNames(long ds) {
    return inA(() -> repository().findConfig(ds)).fields().stream()
        .map(SearchConfig.Field::columnName)
        .toList();
  }

  @Test
  @DisplayName("setSearchable 은 지정한 컬럼만 true 로 만들고 findConfig 가 column_order 순으로 읽는다")
  void setSearchable_findConfig_inColumnOrder() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long c1 = fx.createColumn(ds, "alpha", 3);
    long c2 = fx.createColumn(ds, "beta", 1);
    long c3 = fx.createColumn(ds, "gamma", 2);

    assertThat(searchableNames(ds)).isEmpty();
    set(ds, List.of("alpha", "beta"));
    assertThat(searchableNames(ds)).containsExactly("beta", "alpha");
    assertThat(inA(() -> repository().isSearchable(c1))).isTrue();
    assertThat(inA(() -> repository().isSearchable(c2))).isTrue();
    assertThat(inA(() -> repository().isSearchable(c3))).isFalse();

    // 다시 지정하면 나머지는 false 로 돌아간다(덮어쓰기).
    set(ds, List.of("gamma"));
    assertThat(searchableNames(ds)).containsExactly("gamma");
    assertThat(inA(() -> repository().isSearchable(c1))).isFalse();
    assertThat(inA(() -> repository().isSearchable(c3))).isTrue();
  }

  @Test
  @DisplayName("빈 목록·없는 이름·중복 이름 — 빈 목록은 전부 false, 없는 이름은 무시, 중복은 한 번")
  void setSearchable_emptyUnknownAndDuplicate() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    fx.createColumn(ds, "alpha", 1);
    fx.createColumn(ds, "beta", 2);

    set(ds, List.of("alpha", "alpha", "nope"));
    assertThat(searchableNames(ds)).containsExactly("alpha");
    set(ds, List.of());
    assertThat(searchableNames(ds)).isEmpty();
  }

  @Test
  @DisplayName("isSearchable — 없는 컬럼 id 는 false")
  void isSearchable_unknownColumn() {
    assertThat(inA(() -> repository().isSearchable(-1L))).isFalse();
  }

  @Test
  @DisplayName("다른 데이터셋의 컬럼은 건드리지 않는다")
  void setSearchable_onlyTargetDataset() {
    long ds1 = fx.createDataset(tenantA, prefix + " ds1");
    long ds2 = fx.createDataset(tenantA, prefix + " ds2");
    fx.createColumn(ds1, "alpha", 1);
    fx.createColumn(ds2, "alpha", 1);

    set(ds1, List.of("alpha"));

    assertThat(searchableNames(ds1)).containsExactly("alpha");
    assertThat(searchableNames(ds2)).isEmpty();
  }

  @Test
  @DisplayName("이름 1001 개 — IN 목록 한도(1000)를 넘어도 청크 OR 로 앞·중간·끝 이름이 모두 맞는다")
  void setSearchable_over1000Names() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    fx.createColumn(ds, "col_first", 1);
    fx.createColumn(ds, "col_middle", 2);
    fx.createColumn(ds, "col_last", 3);
    fx.createColumn(ds, "col_off", 4);
    List<String> names = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      names.add("filler_" + i);
    }
    // 청크 경계(500·1000)를 가로지르는 위치에 실제 컬럼 이름을 둔다.
    names.set(0, "col_first");
    names.set(700, "col_middle");
    names.set(1000, "col_last");

    set(ds, names);

    assertThat(searchableNames(ds)).containsExactly("col_first", "col_middle", "col_last");
  }

  @Test
  @DisplayName("다른 테넌트 차단 — B 컨텍스트는 A 컬럼을 읽지도 바꾸지도 못한다")
  void otherTenantIsBlocked() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long col = fx.createColumn(ds, "alpha", 1);
    set(ds, List.of("alpha"));

    assertThat(fx.inTenant(tenantB, () -> repository().findConfig(ds)).fields()).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().isSearchable(col))).isFalse();
    fx.inTenant(
        tenantB,
        () -> {
          repository().setSearchable(ds, List.of());
          return null;
        });
    assertThat(searchableNames(ds)).containsExactly("alpha");
  }
}
