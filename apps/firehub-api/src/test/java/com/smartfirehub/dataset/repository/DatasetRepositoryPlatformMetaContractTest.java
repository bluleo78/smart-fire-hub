package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetRepository.DatasetTableLevel;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * WD-494 에서 플랫폼 테이블 조회를 리포지토리로 옮긴 두 메서드({@link DatasetRepository#findSchemaMetas}·{@link
 * DatasetRepository#findTableLevels})의 계약 테스트(최종 리뷰 지적 6).
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(jOOQ·MyBatis PG·MyBatis Tibero, WD-707). WD-707 에서 PG 전용
 * {@code RETURNING} 픽스처를 {@link com.smartfirehub.user.repository.AccessFixtures} 솔기로 옮겨 Tibero 에서도
 * 돌게 했고, id 목록 1001 개(Tibero IN 1000 개 상한 — Review Focus 3)와 다른 테넌트 차단(Review Focus 4) 시나리오를 더했다.
 * 테넌트를 시험마다 새로 만들므로 결과는 이 시험이 만든 행뿐이다.
 */
public abstract class DatasetRepositoryPlatformMetaContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DatasetRepository repository();

  /** 픽스처 데이터셋(이름 "메타 " + suffix). */
  private long createDataset(String suffix, String storageType) {
    long id = fx.createDataset(tenantA, prefix + "메타 " + suffix);
    if (!"TABLE".equals(storageType)) {
      fx.setDatasetField(id, "storage_type", storageType);
    }
    return id;
  }

  private String tableNameOf(long datasetId) {
    return inA(() -> repository().findTableNameById(datasetId)).orElseThrow();
  }

  private void addColumn(long datasetId, String columnName, String displayName, int order) {
    long col = fx.createColumn(datasetId, columnName, order);
    fx.setColumnText(col, displayName, null);
  }

  @Test
  void findSchemaMetas_가시성_조건에_맞는_데이터셋만_테이블_이름순으로_돌려준다() {
    long b = createDataset("b", "TABLE");
    long a = createDataset("a", "TABLE");
    long hidden = createDataset("c", "TABLE");
    // 스펙 D3 — 가시성은 값으로 넘긴다. 숨김 하나를 조회자 rank(기본 등급 2)보다 높은 등급으로 옮긴다.
    long high = fx.createSecurityLevel(tenantA, 5, prefix + "-high");
    fx.setDatasetField(hidden, "security_level_id", high);

    List<DatasetSchemaMeta> metas = inA(() -> repository().findSchemaMetas(null, rank(2)));

    // 테이블 이름은 무작위라 그 순서를 기대값으로 삼는다.
    List<Long> byTable = new ArrayList<>(List.of(a, b));
    byTable.sort((x, y) -> tableNameOf(x).compareTo(tableNameOf(y)));
    assertThat(metas).extracting(DatasetSchemaMeta::datasetId).containsExactlyElementsOf(byTable);
    DatasetSchemaMeta metaA = metas.stream().filter(m -> m.datasetId() == a).findFirst().get();
    assertThat(metaA.tableName()).isEqualTo(tableNameOf(a));
    assertThat(metaA.datasetName()).isEqualTo(prefix + "메타 a");
    assertThat(metaA.storageType()).isEqualTo("TABLE");
  }

  @Test
  void findSchemaMetas_id_목록이_있으면_그_id만_돌려준다() {
    long a = createDataset("a", "TABLE");
    createDataset("b", "TABLE");

    List<DatasetSchemaMeta> metas =
        inA(() -> repository().findSchemaMetas(List.of(a), DatasetVisibility.UNRESTRICTED));

    assertThat(metas).extracting(DatasetSchemaMeta::datasetId).containsExactly(a);
  }

  @Test
  void findSchemaMetas_id_목록이_1001개여도_조각을_넘나들며_돌려준다() {
    // Review Focus 3 — Tibero IN 목록 1000 개 상한. 실제 행 2개 + 없는 id 999개, 실제 행 하나는 첫 조각 밖(500번째 뒤)에 둔다.
    long first = createDataset("first", "TABLE");
    long late = createDataset("late", "TABLE");
    List<Long> ids = new ArrayList<>();
    ids.add(first);
    for (long i = 1; ids.size() < 700; i++) {
      ids.add(-i);
    }
    ids.add(late);
    for (long i = 1000; ids.size() < 1001; i++) {
      ids.add(-i);
    }
    assertThat(ids).hasSize(1001);

    List<DatasetSchemaMeta> metas =
        inA(() -> repository().findSchemaMetas(ids, DatasetVisibility.UNRESTRICTED));

    assertThat(metas)
        .extracting(DatasetSchemaMeta::datasetId)
        .containsExactlyInAnyOrder(first, late);
  }

  @Test
  void findSchemaMetas_다른_테넌트_데이터셋은_id로_골라도_보이지_않는다() {
    long mine = createDataset("mine", "TABLE");
    long others = fx.createDataset(tenantB, prefix + "남의");

    assertThat(
            inA(
                () ->
                    repository()
                        .findSchemaMetas(List.of(mine, others), DatasetVisibility.UNRESTRICTED)))
        .extracting(DatasetSchemaMeta::datasetId)
        .containsExactly(mine);
  }

  @Test
  void findSchemaMetas_컬럼은_column_order_순이고_표시_이름까지_담는다() {
    long id = createDataset("cols", "TABLE");
    // 삽입 순서와 column_order 를 일부러 어긋나게 둔다 — 정렬이 삽입 순서에 기대면 실패한다.
    addColumn(id, "third", "셋째", 3);
    addColumn(id, "first", "첫째", 1);
    addColumn(id, "second", null, 2);

    List<DatasetSchemaMeta> metas =
        inA(() -> repository().findSchemaMetas(List.of(id), DatasetVisibility.UNRESTRICTED));

    assertThat(metas).hasSize(1);
    assertThat(metas.get(0).columns())
        .containsExactly(
            new DatasetSchemaMeta.Column("first", "TEXT", "첫째"),
            new DatasetSchemaMeta.Column("second", "TEXT", null),
            new DatasetSchemaMeta.Column("third", "TEXT", "셋째"));
  }

  @Test
  void findSchemaMetas_컬럼_없는_데이터셋과_DOCUMENT_데이터셋도_빈_컬럼_목록으로_돌려준다() {
    // 지금 코드는 저장 방식으로 거르지 않는다 — TABLE 만 고르는 것은 호출자(AnalyticsQueryExecutionService) 몫이다.
    long empty = createDataset("empty", "TABLE");
    long doc = createDataset("doc", "DOCUMENT");

    List<DatasetSchemaMeta> metas =
        inA(
            () ->
                repository().findSchemaMetas(List.of(empty, doc), DatasetVisibility.UNRESTRICTED));

    assertThat(metas)
        .extracting(DatasetSchemaMeta::datasetId)
        .containsExactlyInAnyOrder(doc, empty);
    assertThat(metas.stream().filter(m -> m.datasetId() == doc).findFirst().get().storageType())
        .isEqualTo("DOCUMENT");
    assertThat(metas).allSatisfy(m -> assertThat(m.columns()).isEmpty());
  }

  @Test
  void findSchemaMetas_맞는_데이터셋이_없으면_빈_목록이다() {
    createDataset("x", "TABLE");
    assertThat(inA(() -> repository().findSchemaMetas(null, rank(Clearance.NO_RANK)))).isEmpty();
  }

  @Test
  void findSchemaMetas_가시성_값의_등급_허용_목록_제한_없음_규칙을_따른다() {
    // 스펙 D3 — jOOQ(JooqDatasetVisibility.condition)·MyBatis(DatasetVisibility.visible 조각)가 같은 행 집합을
    // 낸다.
    long plain = createDataset("plain", "TABLE");
    long high = createDataset("high", "TABLE");
    long listed = createDataset("listed", "TABLE");
    long unlisted = createDataset("unlisted", "TABLE");
    long highLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-h");
    long listLevel = fx.createSecurityLevel(tenantA, 3, prefix + "-l");
    fx.setSecurityLevelFlags(listLevel, true, false);
    fx.setDatasetField(high, "security_level_id", highLevel);
    fx.setDatasetField(listed, "security_level_id", listLevel);
    fx.setDatasetField(unlisted, "security_level_id", listLevel);
    long viewer = fx.createUser(prefix + "-viewer", null);
    fx.grantDatasetAccess(tenantA, listed, null, viewer);

    DatasetVisibility rank4 =
        new DatasetVisibility(new Clearance(viewer, tenantA, 4, Set.of(), false, Set.of()), null);
    assertThat(inA(() -> repository().findSchemaMetas(null, rank4)))
        .extracting(DatasetSchemaMeta::datasetId)
        .containsExactlyInAnyOrder(plain, listed);
    assertThat(inA(() -> repository().findSchemaMetas(null, DatasetVisibility.UNRESTRICTED)))
        .extracting(DatasetSchemaMeta::datasetId)
        .containsExactlyInAnyOrder(plain, high, listed, unlisted);
    // id 목록과 가시성은 함께 건다 — 보이지 않는 id 는 골라도 빠진다.
    assertThat(inA(() -> repository().findSchemaMetas(List.of(high, plain), rank4)))
        .extracting(DatasetSchemaMeta::datasetId)
        .containsExactly(plain);
  }

  /** rank 만 정한 조회자 가시성(역할·허용 목록 없음). */
  private DatasetVisibility rank(int rank) {
    return new DatasetVisibility(new Clearance(1L, tenantA, rank, Set.of(), false, Set.of()), null);
  }

  @Test
  void findTableLevels_전체_조회는_데이터셋_테이블과_보안_등급을_돌려준다() {
    long t = createDataset("lvl_t", "TABLE");
    long doc = createDataset("lvl_doc", "DOCUMENT");
    fx.createDataset(tenantB, prefix + "남의");

    List<DatasetTableLevel> all = inA(() -> repository().findTableLevels(null));

    // DOCUMENT 도 거르지 않는 것이 지금 동작이다(table_name 이 NOT NULL). 다른 테넌트 행은 섞이지 않는다.
    assertThat(all)
        .containsExactlyInAnyOrder(
            new DatasetTableLevel(tableNameOf(t), fx.datasetSecurityLevel(t)),
            new DatasetTableLevel(tableNameOf(doc), fx.datasetSecurityLevel(doc)));
  }

  @Test
  void findTableLevels_테이블_하나만_고르면_그_테이블만_돌려준다() {
    long t = createDataset("only", "TABLE");
    createDataset("other", "TABLE");
    String table = tableNameOf(t);

    assertThat(inA(() -> repository().findTableLevels(table)))
        .containsExactly(new DatasetTableLevel(table, fx.datasetSecurityLevel(t)));
    assertThat(inA(() -> repository().findTableLevels("t_missing_" + prefix))).isEmpty();
  }
}
