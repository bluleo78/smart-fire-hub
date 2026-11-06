package com.smartfirehub.dataset.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetRepository.DatasetTableLevel;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * WD-494 에서 플랫폼 테이블 조회를 리포지토리로 옮긴 두 메서드({@link DatasetRepository#findSchemaMetas}·{@link
 * DatasetRepository#findTableLevels})의 계약 테스트(최종 리뷰 지적 6).
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-491 의 MyBatis 짝도 같은 계약을 통과해야 한다). 컨테이너를 다른 테스트와 함께 쓰므로 단언은
 * 이 테스트가 만든 픽스처로만 좁힌다(가시성 조각·id 목록·테이블 이름 접두사). 픽스처는 클래스 {@code @Transactional} 롤백으로 사라진다.
 */
@Transactional
abstract class DatasetRepositoryPlatformMetaContractTest extends IntegrationTestBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DatasetRepository repository();

  @Autowired private DSLContext dsl;

  /** 이 실행의 픽스처 이름 접두사 — 공유 컨테이너의 다른 데이터셋과 섞이지 않게 한다. */
  private final String prefix = "wd494_rm_" + UUID.randomUUID().toString().substring(0, 8) + "_";

  private Long createUser() {
    String username = prefix + "user";
    return dsl.fetchOne(
            "INSERT INTO \"user\"(username, password, name, email) VALUES (?, 'x', ?, ?) RETURNING id",
            username,
            username,
            username + "@example.com")
        .get(0, Long.class);
  }

  private Long createDataset(String suffix, String storageType, Long userId) {
    return dsl.fetchOne(
            "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                + " VALUES (?,?,?,?,?) RETURNING id",
            "메타 " + suffix,
            prefix + suffix,
            storageType,
            "SOURCE",
            userId)
        .get(0, Long.class);
  }

  private void addColumn(Long datasetId, String columnName, String displayName, int order) {
    dsl.execute(
        "INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type, column_order)"
            + " VALUES (?,?,?,?,?)",
        datasetId,
        columnName,
        displayName,
        "TEXT",
        order);
  }

  private Long securityLevelOf(Long datasetId) {
    return dsl.fetchOne("SELECT security_level_id FROM dataset WHERE id = ?", datasetId)
        .get(0, Long.class);
  }

  @Test
  void findSchemaMetas_가시성_조건에_맞는_데이터셋만_테이블_이름순으로_돌려준다() {
    Long userId = createUser();
    Long b = createDataset("b", "TABLE", userId);
    Long a = createDataset("a", "TABLE", userId);
    Long hidden = createDataset("c", "TABLE", userId);

    // 가시성 조각은 호출자(DatasetAccessGuard.visibleSql)가 별칭 d 기준으로 만든다 — 여기서는 숨김 하나를 빼는 조각으로 흉내 낸다.
    String visibility = "d.table_name LIKE '" + prefix + "%' AND d.id <> " + hidden;
    List<DatasetSchemaMeta> metas = repository().findSchemaMetas(null, visibility);

    assertThat(metas).extracting(DatasetSchemaMeta::datasetId).containsExactly(a, b);
    assertThat(metas.get(0).tableName()).isEqualTo(prefix + "a");
    assertThat(metas.get(0).datasetName()).isEqualTo("메타 a");
    assertThat(metas.get(0).storageType()).isEqualTo("TABLE");
  }

  @Test
  void findSchemaMetas_id_목록이_있으면_그_id만_돌려준다() {
    Long userId = createUser();
    Long a = createDataset("a", "TABLE", userId);
    createDataset("b", "TABLE", userId);

    List<DatasetSchemaMeta> metas = repository().findSchemaMetas(List.of(a), "1=1");

    assertThat(metas).extracting(DatasetSchemaMeta::datasetId).containsExactly(a);
  }

  @Test
  void findSchemaMetas_컬럼은_column_order_순이고_표시_이름까지_담는다() {
    Long userId = createUser();
    Long id = createDataset("cols", "TABLE", userId);
    // 삽입 순서와 column_order 를 일부러 어긋나게 둔다 — 정렬이 삽입 순서에 기대면 실패한다.
    addColumn(id, "third", "셋째", 3);
    addColumn(id, "first", "첫째", 1);
    addColumn(id, "second", null, 2);

    List<DatasetSchemaMeta> metas = repository().findSchemaMetas(List.of(id), "1=1");

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
    Long userId = createUser();
    Long empty = createDataset("empty", "TABLE", userId);
    Long doc = createDataset("doc", "DOCUMENT", userId);

    List<DatasetSchemaMeta> metas = repository().findSchemaMetas(List.of(empty, doc), "1=1");

    assertThat(metas).extracting(DatasetSchemaMeta::datasetId).containsExactly(doc, empty);
    assertThat(metas.get(0).storageType()).isEqualTo("DOCUMENT");
    assertThat(metas).allSatisfy(m -> assertThat(m.columns()).isEmpty());
  }

  @Test
  void findSchemaMetas_맞는_데이터셋이_없으면_빈_목록이다() {
    assertThat(repository().findSchemaMetas(null, "1=0")).isEmpty();
  }

  @Test
  void findTableLevels_전체_조회는_데이터셋_테이블과_보안_등급을_돌려준다() {
    Long userId = createUser();
    Long t = createDataset("lvl_t", "TABLE", userId);
    Long doc = createDataset("lvl_doc", "DOCUMENT", userId);

    List<DatasetTableLevel> all = repository().findTableLevels(null);

    // 공유 컨테이너라 다른 행도 섞인다 — 포함 여부만 본다. DOCUMENT 도 거르지 않는 것이 지금 동작이다(table_name 이 NOT NULL).
    assertThat(all)
        .contains(
            new DatasetTableLevel(prefix + "lvl_t", securityLevelOf(t)),
            new DatasetTableLevel(prefix + "lvl_doc", securityLevelOf(doc)));
    assertThat(all).allSatisfy(r -> assertThat(r.tableName()).isNotNull());
  }

  @Test
  void findTableLevels_테이블_하나만_고르면_그_테이블만_돌려준다() {
    Long userId = createUser();
    Long t = createDataset("only", "TABLE", userId);
    createDataset("other", "TABLE", userId);

    assertThat(repository().findTableLevels(prefix + "only"))
        .containsExactly(new DatasetTableLevel(prefix + "only", securityLevelOf(t)));
    assertThat(repository().findTableLevels(prefix + "missing")).isEmpty();
  }
}
