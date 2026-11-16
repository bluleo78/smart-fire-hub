package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.search.DatasetSourceTextBuilder;
import com.smartfirehub.user.repository.TenantPairContractBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetMetaReader 계약 테스트 — dataset·컬럼·태그·카테고리를 검색 합본 입력으로 정확히 읽는지 본다. 구현마다 하위 클래스로 실행한다(WD-485,
 * WD-746 에서 PG 전용 픽스처를 공용 픽스처로 옮겨 MyBatis·Tibero 실서버도 같은 계약을 돈다).
 *
 * <p>모든 표가 테넌트 정책 아래 있어 호출은 {@code inA} 로 감싼다. 컬럼·태그 순서는 구현마다 다를 수 있어(jOOQ 판은 정렬하지 않는다) 순서 없이 비교한다.
 */
public abstract class DatasetMetaReaderContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DatasetMetaReader repository();

  @Test
  @DisplayName("read 는 이름·설명·테이블 이름·카테고리·컬럼 이름과 표시명·태그를 합본 입력으로 돌려준다")
  void read_returnsFullInput() {
    long categoryId = fx.createCategory(tenantA, prefix + " 화재안전통계");
    long ds = fx.createDataset(tenantA, prefix + " 화재 통계 데이터셋");
    fx.assignCategory(ds, categoryId);
    fx.setDatasetField(ds, "description", "연도별 화재 발생 통계");
    long fireCount = fx.createColumn(ds, "fire_count", 1);
    fx.setColumnText(fireCount, "화재건수", null);
    long region = fx.createColumn(ds, "region", 2);
    fx.setColumnText(region, "지역", null);
    fx.addTag(ds, "화재");
    fx.addTag(ds, "통계");

    DatasetSourceTextBuilder.Input in = inA(() -> repository().read(ds));

    assertThat(in).isNotNull();
    assertThat(in.name()).isEqualTo(prefix + " 화재 통계 데이터셋");
    assertThat(in.description()).isEqualTo("연도별 화재 발생 통계");
    assertThat(in.tableName()).matches("[a-z0-9_]+");
    assertThat(in.categoryName()).isEqualTo(prefix + " 화재안전통계");
    // 컬럼 이름과 표시명이 한 목록에 합쳐져 검색 원문을 풍부하게 한다.
    assertThat(in.columnNames()).containsExactlyInAnyOrder("fire_count", "화재건수", "region", "지역");
    assertThat(in.tagNames()).containsExactlyInAnyOrder("화재", "통계");

    // 빌더로 합치면 모든 신호가 원문에 들어간다.
    assertThat(DatasetSourceTextBuilder.build(in))
        .contains(prefix + " 화재 통계 데이터셋", in.tableName(), "화재건수", "화재안전통계", "통계");
  }

  @Test
  @DisplayName("카테고리·컬럼·태그가 없는 데이터셋도 읽히고 비어 있는 값은 null·빈 목록이다")
  void read_withoutCategoryColumnsTags() {
    long ds = fx.createDataset(tenantA, prefix + " 무카테고리 데이터셋");

    DatasetSourceTextBuilder.Input in = inA(() -> repository().read(ds));

    assertThat(in).isNotNull();
    assertThat(in.name()).isEqualTo(prefix + " 무카테고리 데이터셋");
    assertThat(in.description()).isNull();
    assertThat(in.categoryName()).isNull();
    assertThat(in.columnNames()).isEmpty();
    assertThat(in.tagNames()).isEmpty();
  }

  @Test
  @DisplayName("없는 데이터셋과 다른 테넌트의 데이터셋은 null 이다")
  void read_missingOrOtherTenant_returnsNull() {
    long other = fx.createDataset(tenantB, prefix + " 다른 테넌트");

    assertThat(inA(() -> repository().read(-1L))).isNull();
    assertThat(inA(() -> repository().read(other))).isNull();
  }

  @Test
  @DisplayName("findAllIds 는 이 테넌트의 데이터셋 id 를 오름차순으로, 다른 테넌트 것은 빼고 돌려준다")
  void findAllIds_ascendingWithinTenant() {
    long first = fx.createDataset(tenantA, prefix + " a1");
    long second = fx.createDataset(tenantA, prefix + " a2");
    long other = fx.createDataset(tenantB, prefix + " b1");

    var ids = inA(() -> repository().findAllIds());

    assertThat(ids).contains(first, second).doesNotContain(other).isSorted();
  }
}
