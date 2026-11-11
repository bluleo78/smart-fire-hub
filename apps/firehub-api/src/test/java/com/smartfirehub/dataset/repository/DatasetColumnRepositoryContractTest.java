package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetColumnSnapshot;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetColumnRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-707). 공개 메서드 16개 전부 + 리뷰 포인트: 불리언 열 왕복, 다음
 * 열 순서(max), 재정렬, PK 목록 1001 개(IN 한도), 다른 테넌트 컬럼 차단, 빈 문자열(emptyStringIsNull).
 */
public abstract class DatasetColumnRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetColumnRepository repository();

  private static DatasetColumnRequest req(
      String name, String display, String type, Integer len, boolean nullable, boolean indexed) {
    return new DatasetColumnRequest(name, display, type, len, nullable, indexed, null, false);
  }

  @Test
  @DisplayName("save — 모든 항목·불리언(true/false) 왕복, findById·findByDatasetId 가 column_order 순")
  void save_roundTrip_andOrdering() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    DatasetColumnResponse b =
        inA(
            () ->
                repository()
                    .save(
                        ds,
                        new DatasetColumnRequest(
                            "beta", "베타", "VARCHAR", 50, false, true, "설명", true),
                        1));
    DatasetColumnResponse a =
        inA(() -> repository().save(ds, req("alpha", null, "TEXT", null, true, false), 0));

    assertThat(b.id()).isNotNull();
    assertThat(b.columnName()).isEqualTo("beta");
    assertThat(b.displayName()).isEqualTo("베타");
    assertThat(b.dataType()).isEqualTo("VARCHAR");
    assertThat(b.maxLength()).isEqualTo(50);
    assertThat(b.isNullable()).isFalse();
    assertThat(b.isIndexed()).isTrue();
    assertThat(b.description()).isEqualTo("설명");
    assertThat(b.columnOrder()).isEqualTo(1);
    assertThat(b.isPrimaryKey()).isTrue();
    assertThat(a.isNullable()).isTrue();
    assertThat(a.isIndexed()).isFalse();
    assertThat(a.isPrimaryKey()).isFalse();
    assertThat(a.displayName()).isNull();
    assertThat(a.maxLength()).isNull();

    assertThat(inA(() -> repository().findById(b.id()))).contains(b);
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findByDatasetId(ds)))
        .extracting(DatasetColumnResponse::columnName)
        .containsExactly("alpha", "beta");
  }

  @Test
  @DisplayName("saveBatch — 요청 순서대로 0 부터 열 순서, getMaxOrder 는 없으면 -1·있으면 최댓값(빈틈 포함)")
  void saveBatch_andMaxOrder() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    assertThat(inA(() -> repository().getMaxOrder(ds))).isEqualTo(-1);

    inA(
        () -> {
          repository()
              .saveBatch(
                  ds,
                  List.of(
                      req("c0", null, "TEXT", null, true, false),
                      req("c1", null, "TEXT", null, true, false),
                      req("c2", null, "TEXT", null, true, false)));
          return null;
        });
    assertThat(inA(() -> repository().findByDatasetId(ds)))
        .extracting(DatasetColumnResponse::columnName, DatasetColumnResponse::columnOrder)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("c0", 0),
            org.assertj.core.groups.Tuple.tuple("c1", 1),
            org.assertj.core.groups.Tuple.tuple("c2", 2));
    assertThat(inA(() -> repository().getMaxOrder(ds))).isEqualTo(2);

    // 다음 순번 = max + 1 — 빈틈이 있어도 최댓값 기준.
    int next = inA(() -> repository().getMaxOrder(ds)) + 1;
    inA(() -> repository().save(ds, req("c9", null, "TEXT", null, true, false), next + 5));
    assertThat(inA(() -> repository().getMaxOrder(ds))).isEqualTo(8);
  }

  @Test
  @DisplayName("update — null 항목은 보존, 불리언 갱신, 데이터 타입이 VARCHAR 가 아니면 max_length 비움")
  void update_partialAndBooleans() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id =
        inA(() ->
                repository()
                    .save(
                        ds,
                        new DatasetColumnRequest(
                            "col", "표시", "VARCHAR", 30, true, false, "d", false),
                        0))
            .id();

    // 전부 null — 아무것도 바뀌지 않는다.
    inA(
        () -> {
          repository()
              .update(id, new UpdateColumnRequest(null, null, null, null, null, null, null, null));
          return null;
        });
    DatasetColumnResponse same = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(same.displayName()).isEqualTo("표시");
    assertThat(same.maxLength()).isEqualTo(30);
    assertThat(same.isNullable()).isTrue();
    assertThat(same.isIndexed()).isFalse();
    assertThat(same.description()).isEqualTo("d");

    // maxLength 만 바꾸면(타입 없음) 값이 바뀐다.
    inA(
        () -> {
          repository()
              .update(id, new UpdateColumnRequest(null, null, null, 77, null, null, null, null));
          return null;
        });
    assertThat(inA(() -> repository().findById(id)).orElseThrow().maxLength()).isEqualTo(77);

    // 불리언 반전 + 이름·표시·설명 변경.
    inA(
        () -> {
          repository()
              .update(
                  id,
                  new UpdateColumnRequest("renamed", "새표시", null, null, false, true, "nd", true));
          return null;
        });
    DatasetColumnResponse changed = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(changed.columnName()).isEqualTo("renamed");
    assertThat(changed.displayName()).isEqualTo("새표시");
    assertThat(changed.description()).isEqualTo("nd");
    assertThat(changed.isNullable()).isFalse();
    assertThat(changed.isIndexed()).isTrue();
    assertThat(changed.isPrimaryKey()).isTrue();
    assertThat(changed.maxLength()).isEqualTo(77);

    // VARCHAR 가 아닌 타입으로 바꾸면 max_length 가 비워진다(값이 함께 와도).
    inA(
        () -> {
          repository()
              .update(id, new UpdateColumnRequest(null, null, "TEXT", 99, null, null, null, null));
          return null;
        });
    DatasetColumnResponse text = inA(() -> repository().findById(id)).orElseThrow();
    assertThat(text.dataType()).isEqualTo("TEXT");
    assertThat(text.maxLength()).isNull();
  }

  @Test
  @DisplayName("빈 문자열 설명·표시 이름 — PG 는 그대로, Tibero 는 NULL 로 돌아온다")
  void emptyStringDescription() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = inA(() -> repository().save(ds, req("col", null, "TEXT", null, true, false), 0)).id();
    inA(
        () -> {
          repository()
              .update(id, new UpdateColumnRequest(null, "", null, null, null, null, "", null));
          return null;
        });
    DatasetColumnResponse r = inA(() -> repository().findById(id)).orElseThrow();
    if (emptyStringIsNull()) {
      assertThat(r.description()).isNull();
      assertThat(r.displayName()).isNull();
    } else {
      assertThat(r.description()).isEmpty();
      assertThat(r.displayName()).isEmpty();
    }
    inA(
        () -> {
          repository().updateDescription(id, "");
          return null;
        });
    String d = inA(() -> repository().findById(id)).orElseThrow().description();
    if (emptyStringIsNull()) {
      assertThat(d).isNull();
    } else {
      assertThat(d).isEmpty();
    }
  }

  @Test
  @DisplayName("updateDescription — 설정·null 로 비우기")
  void updateDescription() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long id = fx.createColumn(ds, "col", 0);
    inA(
        () -> {
          repository().updateDescription(id, "새 설명");
          return null;
        });
    assertThat(inA(() -> repository().findById(id)).orElseThrow().description()).isEqualTo("새 설명");
    inA(
        () -> {
          repository().updateDescription(id, null);
          return null;
        });
    assertThat(inA(() -> repository().findById(id)).orElseThrow().description()).isNull();
  }

  @Test
  @DisplayName("updateOrders — 목록 순서대로 0.. 재정렬, 다른 데이터셋 컬럼은 건드리지 않는다")
  void updateOrders_reorders() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    long c1 = fx.createColumn(ds, "a", 0);
    long c2 = fx.createColumn(ds, "b", 1);
    long c3 = fx.createColumn(ds, "c", 2);
    long o1 = fx.createColumn(other, "a", 5);

    inA(
        () -> {
          // 다른 데이터셋의 컬럼 id 를 섞어도 이 데이터셋 조건 때문에 무시된다.
          repository().updateOrders(ds, List.of(c3, c1, o1, c2));
          return null;
        });

    assertThat(inA(() -> repository().findByDatasetId(ds)))
        .extracting(DatasetColumnResponse::id)
        .containsExactly(c3, c1, c2);
    assertThat(inA(() -> repository().findById(c3)).orElseThrow().columnOrder()).isZero();
    assertThat(inA(() -> repository().findById(c2)).orElseThrow().columnOrder()).isEqualTo(3);
    assertThat(inA(() -> repository().findById(o1)).orElseThrow().columnOrder()).isEqualTo(5);
  }

  @Test
  @DisplayName("updatePrimaryKeys — 지정 컬럼만 true, 나머지 false, 빈·null 목록은 전부 false")
  void updatePrimaryKeys() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long c1 = fx.createColumn(ds, "a", 0);
    long c2 = fx.createColumn(ds, "b", 1);
    long c3 = fx.createColumn(ds, "c", 2);

    inA(
        () -> {
          repository().updatePrimaryKeys(ds, List.of(c1, c3));
          return null;
        });
    assertThat(pkFlags(ds)).containsExactly(true, false, true);

    inA(
        () -> {
          repository().updatePrimaryKeys(ds, List.of(c2));
          return null;
        });
    assertThat(pkFlags(ds)).containsExactly(false, true, false);

    inA(
        () -> {
          repository().updatePrimaryKeys(ds, List.of());
          return null;
        });
    assertThat(pkFlags(ds)).containsExactly(false, false, false);
    inA(
        () -> {
          repository().updatePrimaryKeys(ds, List.of(c1));
          repository().updatePrimaryKeys(ds, null);
          return null;
        });
    assertThat(pkFlags(ds)).containsExactly(false, false, false);
  }

  @Test
  @DisplayName("updatePrimaryKeys — id 1001 개(IN 한도 1000 초과)도 청크 경계를 넘어 앞·중간·끝이 맞는다")
  void updatePrimaryKeys_over1000Ids() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long first = fx.createColumn(ds, "first", 0);
    long middle = fx.createColumn(ds, "middle", 1);
    long last = fx.createColumn(ds, "last", 2);
    fx.createColumn(ds, "off", 3);
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      ids.add(-1L - i);
    }
    ids.set(0, first);
    ids.set(700, middle);
    ids.set(1000, last);

    inA(
        () -> {
          repository().updatePrimaryKeys(ds, ids);
          return null;
        });

    assertThat(pkFlags(ds)).containsExactly(true, true, true, false);
  }

  private List<Boolean> pkFlags(long ds) {
    return inA(() -> repository().findByDatasetId(ds)).stream()
        .map(DatasetColumnResponse::isPrimaryKey)
        .toList();
  }

  @Test
  @DisplayName("existsByDatasetIdAndColumnName — 같은 데이터셋·이름만, excludeColumnId 는 비교에서 뺀다")
  void existsByDatasetIdAndColumnName() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    long c1 = fx.createColumn(ds, "alpha", 0);
    fx.createColumn(other, "beta", 0);

    assertThat(inA(() -> repository().existsByDatasetIdAndColumnName(ds, "alpha", null))).isTrue();
    assertThat(inA(() -> repository().existsByDatasetIdAndColumnName(ds, "alpha", c1))).isFalse();
    assertThat(inA(() -> repository().existsByDatasetIdAndColumnName(ds, "alpha", -1L))).isTrue();
    assertThat(inA(() -> repository().existsByDatasetIdAndColumnName(ds, "beta", null))).isFalse();
    assertThat(inA(() -> repository().existsByDatasetIdAndColumnName(ds, "Alpha", null))).isFalse();
  }

  @Test
  @DisplayName("findDatasetIdByColumnId·deleteById·deleteByDatasetId")
  void findDatasetId_andDeletes() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long other = fx.createDataset(tenantA, prefix + " other");
    long c1 = fx.createColumn(ds, "a", 0);
    long c2 = fx.createColumn(ds, "b", 1);
    long o1 = fx.createColumn(other, "a", 0);

    assertThat(inA(() -> repository().findDatasetIdByColumnId(c1))).contains(ds);
    assertThat(inA(() -> repository().findDatasetIdByColumnId(-1L))).isEmpty();

    inA(
        () -> {
          repository().deleteById(c1);
          return null;
        });
    assertThat(inA(() -> repository().findById(c1))).isEmpty();
    assertThat(inA(() -> repository().findById(c2))).isPresent();

    inA(
        () -> {
          repository().deleteByDatasetId(ds);
          return null;
        });
    assertThat(inA(() -> repository().findByDatasetId(ds))).isEmpty();
    assertThat(inA(() -> repository().findById(o1))).isPresent();
  }

  @Test
  @DisplayName("findFirstDescriptionByColumnName — 다른 데이터셋의 비어 있지 않은 첫 설명, 없으면 null")
  void findFirstDescription() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long d1 = fx.createDataset(tenantA, prefix + " d1");
    long d2 = fx.createDataset(tenantA, prefix + " d2");
    long d3 = fx.createDataset(tenantA, prefix + " d3");
    long own = fx.createColumn(ds, "shared", 0);
    long c1 = fx.createColumn(d1, "shared", 0);
    long c2 = fx.createColumn(d2, "shared", 0);
    long c3 = fx.createColumn(d3, "shared", 0);

    // 자기 데이터셋의 설명은 제외 대상이라 무시된다.
    inA(
        () -> {
          repository().updateDescription(own, "자기 설명");
          return null;
        });
    assertThat(inA(() -> repository().findFirstDescriptionByColumnName("shared", ds))).isNull();

    // 빈 설명·null 은 건너뛴다. 먼저 만든(id 가 작은) 컬럼의 설명이 이긴다.
    inA(
        () -> {
          repository().updateDescription(c1, "");
          repository().updateDescription(c2, "두번째");
          repository().updateDescription(c3, "세번째");
          return null;
        });
    assertThat(inA(() -> repository().findFirstDescriptionByColumnName("shared", ds)))
        .isEqualTo("두번째");
    assertThat(inA(() -> repository().findFirstDescriptionByColumnName("nope", ds))).isNull();
  }

  @Test
  @DisplayName("snapshot·restore — id·검색 대상·열 순서까지 같은 행으로 되돌리고 보관본이 모든 열을 담는다")
  void snapshot_restore_roundTrip() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    inA(
        () -> {
          repository()
              .saveBatch(
                  ds,
                  List.of(
                      new DatasetColumnRequest("code", "코드", "TEXT", null, false, true, "설명", true),
                      req("amount", null, "VARCHAR", 50, true, false)));
          return null;
        });
    List<DatasetColumnSnapshot> raw = inA(() -> repository().snapshotByDatasetId(ds));
    assertThat(raw).hasSize(2);
    assertThat(raw.get(0).columnName()).isEqualTo("code");
    assertThat(raw.get(0).isPrimaryKey()).isTrue();
    assertThat(raw.get(0).isNullable()).isFalse();
    assertThat(raw.get(0).isIndexed()).isTrue();
    assertThat(raw.get(0).isSearchable()).isFalse();
    assertThat(raw.get(0).tenantId()).isEqualTo(tenantA);
    assertThat(raw.get(1).maxLength()).isEqualTo(50);

    // 검색 대상(응답 DTO 에 없는 열)을 켠 보관본으로 되돌린다 — 지우고 id 까지 그대로 다시 넣는다.
    DatasetColumnSnapshot s0 = raw.get(0);
    List<DatasetColumnSnapshot> wanted =
        List.of(
            new DatasetColumnSnapshot(
                s0.id(),
                s0.datasetId(),
                s0.columnName(),
                s0.displayName(),
                s0.dataType(),
                s0.isNullable(),
                s0.isIndexed(),
                s0.description(),
                s0.columnOrder(),
                s0.maxLength(),
                s0.isPrimaryKey(),
                s0.tenantId(),
                true),
            raw.get(1));
    inA(
        () -> {
          repository().deleteByDatasetId(ds);
          repository().saveBatch(ds, List.of(req("other", null, "TEXT", null, true, false)));
          return null;
        });
    assertThat(inA(() -> repository().snapshotByDatasetId(ds))).isNotEqualTo(wanted);

    inA(
        () -> {
          repository().restoreSnapshot(ds, wanted);
          return null;
        });
    assertThat(inA(() -> repository().snapshotByDatasetId(ds))).isEqualTo(wanted);
  }

  @Test
  @DisplayName("다른 테넌트의 데이터셋 컬럼은 읽기·쓰기·삭제·이름 검사 모두에서 보이지 않는다")
  void otherTenantColumnsAreInvisible() {
    long ds = fx.createDataset(tenantA, prefix + " ds");
    long col = fx.createColumn(ds, "secret", 0);
    inA(
        () -> {
          repository().updateDescription(col, "비밀 설명");
          return null;
        });

    assertThat(fx.inTenant(tenantB, () -> repository().findById(col))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findByDatasetId(ds))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().getMaxOrder(ds))).isEqualTo(-1);
    assertThat(fx.inTenant(tenantB, () -> repository().snapshotByDatasetId(ds))).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findDatasetIdByColumnId(col))).isEmpty();
    assertThat(
            fx.inTenant(
                tenantB, () -> repository().existsByDatasetIdAndColumnName(ds, "secret", null)))
        .isFalse();
    assertThat(
            fx.inTenant(
                tenantB, () -> repository().findFirstDescriptionByColumnName("secret", -1L)))
        .isNull();

    // 다른 테넌트에서의 쓰기·삭제는 아무 행도 건드리지 못한다.
    fx.inTenant(
        tenantB,
        () -> {
          repository().updateDescription(col, "침입");
          repository().deleteById(col);
          repository().deleteByDatasetId(ds);
          repository().updatePrimaryKeys(ds, List.of(col));
          return null;
        });
    DatasetColumnResponse still = inA(() -> repository().findById(col)).orElseThrow();
    assertThat(still.description()).isEqualTo("비밀 설명");
    assertThat(still.isPrimaryKey()).isFalse();
  }
}
