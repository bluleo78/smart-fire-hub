package com.smartfirehub.dataset.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** dataset_embedding 코사인/트라이그램 검색이 동작하는지 검증하는 실제 DB 통합 테스트. */
@Transactional
class DatasetSearchRepositoryTest extends IntegrationTestBase {

  @Autowired private DatasetSearchRepository searchRepository;
  @Autowired private DSLContext dsl;

  /** [a,b,0,0,...] 형태의 1024차원 쿼리 벡터. */
  private float[] vec(float a, float b) {
    float[] v = new float[1024];
    v[0] = a;
    v[1] = b;
    return v;
  }

  /** [a,b,0,...,0] 1024차원 pgvector 텍스트 리터럴(시드/프로브 공용). */
  private static String literal(int a, int b) {
    StringBuilder sb = new StringBuilder("[").append(a).append(',').append(b);
    for (int i = 2; i < 1024; i++) sb.append(",0");
    return sb.append("]").toString();
  }

  /** 테스트용 사용자 생성 후 id 반환. */
  private Long createUser(String username) {
    return dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES (?, 'x', ?, ?) RETURNING id",
        username, username, username + "@example.com").get(0, Long.class);
  }

  /** 데이터셋 생성 후 id 반환. */
  private Long createDataset(
      String name, String tableName, String storageType, String originType, Long userId) {
    return dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
            + " VALUES (?, ?, ?, ?, ?) RETURNING id",
        name, tableName, storageType, originType, userId).get(0, Long.class);
  }

  /** source_text + embedding(있으면)을 dataset_embedding 에 시드. embedLiteral 이 null 이면 embedding 컬럼을 생략. */
  private void seedEmbedding(Long datasetId, String sourceText, String embedLiteral) {
    if (embedLiteral == null) {
      // embedding 미생성(비동기 대기) 행: NULL 을 ?::vector 로 캐스팅하지 않고 컬럼 자체를 생략한다.
      dsl.execute(
          "INSERT INTO dataset_embedding(dataset_id, source_text, embedding_model) VALUES (?,?,?)",
          datasetId, sourceText, "bge-m3");
    } else {
      dsl.execute(
          "INSERT INTO dataset_embedding(dataset_id, source_text, embedding, embedding_model)"
              + " VALUES (?,?,?::vector,?)",
          datasetId, sourceText, embedLiteral, "bge-m3");
    }
  }

  @Test
  void searchByTrigramFindsTermAndScoresPositive() {
    Long userId = createUser("dssearch_trgm");
    Long matched =
        createDataset("화재 통계 데이터셋", "data.ds_trgm_a", "DOCUMENT", "SOURCE", userId);
    Long unrelated =
        createDataset("전혀 무관한 인사 자료", "data.ds_trgm_b", "DOCUMENT", "SOURCE", userId);
    seedEmbedding(matched, "연도별 화재 발생 건수 및 피해 통계", null);
    seedEmbedding(unrelated, "직원 인사 발령 일반 자료", null);

    var hits = searchRepository.searchByTrigram("화재", null, 10);

    // 시드한 '화재' 포함 데이터셋이 후보로 나오고 score > 0.
    assertThat(hits).isNotEmpty();
    assertThat(hits).anyMatch(h -> h.datasetId().equals(matched));
    var hit = hits.stream().filter(h -> h.datasetId().equals(matched)).findFirst().orElseThrow();
    assertThat(hit.score()).isGreaterThan(0.0);
    assertThat(hit.name()).isEqualTo("화재 통계 데이터셋");
  }

  @Test
  void searchByTrigramAppliesStorageTypeFilter() {
    Long userId = createUser("dssearch_filter");
    Long doc = createDataset("화재 문서셋", "data.ds_f_doc", "DOCUMENT", "SOURCE", userId);
    Long table = createDataset("화재 테이블셋", "data.ds_f_tbl", "TABLE", "SOURCE", userId);
    seedEmbedding(doc, "화재 관련 비정형 문서 모음", null);
    seedEmbedding(table, "화재 관련 정형 테이블 데이터", null);

    var hits = searchRepository.searchByTrigram("화재", "DOCUMENT", 10);

    // storageType 필터 → 결과 전부 DOCUMENT.
    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(h -> h.storageType().equals("DOCUMENT"));
    assertThat(hits).anyMatch(h -> h.datasetId().equals(doc));
    assertThat(hits).noneMatch(h -> h.datasetId().equals(table));
  }

  @Test
  void searchByCosineRanksNearestFirstAndExcludesNullEmbedding() {
    Long userId = createUser("dssearch_cos");
    Long near = createDataset("근접 데이터셋", "data.ds_cos_near", "TABLE", "SOURCE", userId);
    Long far = createDataset("원거리 데이터셋", "data.ds_cos_far", "TABLE", "DERIVED", userId);
    Long pending = createDataset("임베딩 대기 데이터셋", "data.ds_cos_pending", "TABLE", "SOURCE", userId);
    seedEmbedding(near, "근접 본문", literal(1, 0)); // 쿼리 [1,0,..] 과 동일 방향
    seedEmbedding(far, "원거리 본문", literal(0, 1)); // 직교
    seedEmbedding(pending, "임베딩 미생성 본문", null); // embedding NULL — 코사인 결과에서 제외되어야 함

    var hits = searchRepository.searchByCosine(vec(1f, 0f), null, 10);

    assertThat(hits).isNotEmpty();
    // (a) near 가 first, score 내림차순.
    var nearHit = hits.stream().filter(h -> h.datasetId().equals(near)).findFirst().orElseThrow();
    var farHit = hits.stream().filter(h -> h.datasetId().equals(far)).findFirst().orElseThrow();
    assertThat(nearHit.score()).isGreaterThan(farHit.score());
    // 시드 3건 중 near/far 만 등장하는 부분집합에서 near 가 far 보다 앞.
    int nearIdx = indexOf(hits, near);
    int farIdx = indexOf(hits, far);
    assertThat(nearIdx).isLessThan(farIdx);
    // (b) embedding NULL 행은 코사인 결과에서 제외.
    assertThat(hits).noneMatch(h -> h.datasetId().equals(pending));
    // 매핑 필드 정합성 확인.
    assertThat(nearHit.originType()).isEqualTo("SOURCE");
    assertThat(nearHit.tableName()).isEqualTo("data.ds_cos_near");
  }

  @Test
  void searchByCosineAppliesStorageTypeFilter() {
    Long userId = createUser("dssearch_cos_filter");
    Long doc = createDataset("코사인 문서셋", "data.ds_cf_doc", "DOCUMENT", "SOURCE", userId);
    Long table = createDataset("코사인 테이블셋", "data.ds_cf_tbl", "TABLE", "SOURCE", userId);
    seedEmbedding(doc, "문서 본문", literal(1, 0));
    seedEmbedding(table, "테이블 본문", literal(1, 0));

    var hits = searchRepository.searchByCosine(vec(1f, 0f), "DOCUMENT", 10);

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(h -> h.storageType().equals("DOCUMENT"));
    assertThat(hits).noneMatch(h -> h.datasetId().equals(table));
  }

  private static int indexOf(java.util.List<DatasetSearchHit> hits, Long datasetId) {
    for (int i = 0; i < hits.size(); i++) {
      if (hits.get(i).datasetId().equals(datasetId)) return i;
    }
    return -1;
  }
}
