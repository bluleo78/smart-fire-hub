package com.smartfirehub.dataset.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** DatasetMetaReader 가 dataset/컬럼/태그/카테고리를 합본 Input 으로 정확히 읽는지 검증하는 실제 DB 통합 테스트. */
@Transactional
class DatasetMetaReaderTest extends IntegrationTestBase {

  @Autowired private DatasetMetaReader metaReader;
  @Autowired private DSLContext dsl;

  private Long createUser(String username) {
    return dsl.fetchOne(
            "INSERT INTO \"user\"(username, password, name, email) VALUES (?, 'x', ?, ?) RETURNING id",
            username, username, username + "@example.com")
        .get(0, Long.class);
  }

  private Long createCategory(String name) {
    return dsl.fetchOne("INSERT INTO dataset_category(name) VALUES (?) RETURNING id", name)
        .get(0, Long.class);
  }

  private Long createDataset(String name, String tableName, Long categoryId, Long userId) {
    return dsl.fetchOne(
            "INSERT INTO dataset(name, description, table_name, storage_type, origin_type,"
                + " category_id, created_by) VALUES (?,?,?,?,?,?,?) RETURNING id",
            name,
            "연도별 화재 발생 통계",
            tableName,
            "TABLE",
            "SOURCE",
            categoryId,
            userId)
        .get(0, Long.class);
  }

  private void addColumn(Long datasetId, String columnName, String displayName, int order) {
    dsl.execute(
        "INSERT INTO dataset_column(dataset_id, column_name, display_name, data_type, column_order)"
            + " VALUES (?,?,?,?,?)",
        datasetId, columnName, displayName, "TEXT", order);
  }

  private void addTag(Long datasetId, String tagName) {
    dsl.execute("INSERT INTO dataset_tag(dataset_id, tag_name) VALUES (?,?)", datasetId, tagName);
  }

  @Test
  void read_데이터셋_메타를_컬럼_표시명_태그_카테고리까지_합본Input으로_반환한다() {
    Long userId = createUser("metareader_main");
    Long categoryId = createCategory("화재안전통계");
    Long datasetId = createDataset("화재 통계 데이터셋", "data.meta_a", categoryId, userId);
    addColumn(datasetId, "fire_count", "화재건수", 1);
    addColumn(datasetId, "region", "지역", 2);
    addTag(datasetId, "화재");
    addTag(datasetId, "통계");

    DatasetSourceTextBuilder.Input in = metaReader.read(datasetId);

    assertThat(in).isNotNull();
    assertThat(in.name()).isEqualTo("화재 통계 데이터셋");
    assertThat(in.description()).isEqualTo("연도별 화재 발생 통계");
    assertThat(in.tableName()).isEqualTo("data.meta_a");
    assertThat(in.categoryName()).isEqualTo("화재안전통계");
    // 컬럼명과 표시명이 모두 columnNames 에 합쳐져 검색 텍스트를 풍부화한다.
    assertThat(in.columnNames())
        .containsExactlyInAnyOrder("fire_count", "화재건수", "region", "지역");
    assertThat(in.tagNames()).containsExactlyInAnyOrder("화재", "통계");

    // 빌더로 합본 시 모든 신호가 본문에 포함되는지 확인.
    String sourceText = DatasetSourceTextBuilder.build(in);
    assertThat(sourceText).contains("화재 통계 데이터셋", "data.meta_a", "화재건수", "화재안전통계", "통계");
  }

  @Test
  void read_카테고리없는_데이터셋도_정상조회되고_categoryName은_null이다() {
    Long userId = createUser("metareader_nocat");
    Long datasetId = createDataset("무카테고리 데이터셋", "data.meta_b", null, userId);

    DatasetSourceTextBuilder.Input in = metaReader.read(datasetId);

    assertThat(in).isNotNull();
    assertThat(in.name()).isEqualTo("무카테고리 데이터셋");
    assertThat(in.categoryName()).isNull();
    assertThat(in.columnNames()).isEmpty();
    assertThat(in.tagNames()).isEmpty();
  }

  @Test
  void read_존재하지않는_데이터셋이면_null을_반환한다() {
    assertThat(metaReader.read(99_999_999L)).isNull();
  }
}
