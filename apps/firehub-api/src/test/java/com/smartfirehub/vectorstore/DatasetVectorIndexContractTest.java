package com.smartfirehub.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.support.IntegrationTestBase;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * dataset_embedding 코사인/트라이그램 검색이 동작하는지 검증하는 실제 DB 통합 테스트.
 *
 * <p>포트 계약 테스트다. 구현마다 하위 클래스로 실행한다(ADR 0002 결정 2, 예전 DatasetSearchRepository 계약 테스트를 옮겼다). 꺼짐
 * 구현(Tibero+platform) 하위는 {@link #supportsVectorSearch()}·{@link #supportsTrigramSearch()} 를 거짓으로
 * 두고 각 시나리오 대신 UnsupportedOperationException 을 단언한다.
 */
@Transactional
abstract class DatasetVectorIndexContractTest extends IntegrationTestBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract DatasetVectorIndex repository();

  /** 코사인(벡터) 검색을 구현하는가(pgvector 참, 꺼짐 거짓). */
  protected boolean supportsVectorSearch() {
    return true;
  }

  /** 트라이그램(키워드) 검색을 구현하는가(pgvector 참, 꺼짐 거짓). */
  protected boolean supportsTrigramSearch() {
    return true;
  }

  /**
   * 꺼진 구현이면 시나리오 대신 예외(종류·기능 이름)를 단언하고 참을 돌려준다 — 호출자는 시드 전에 바로 끝낸다(시드는 PG 전용 RETURNING·pgvector).
   */
  private boolean assertedOff(boolean supported, String feature, ThrowingCallable call) {
    if (supported) return false;
    assertThatThrownBy(call)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(feature);
    return true;
  }

  private boolean trigramOff() {
    return assertedOff(
        supportsTrigramSearch(),
        "TRIGRAM_SEARCH",
        () -> repository().keyword("화재", null, 10, ALL_VISIBLE));
  }

  private boolean vectorOff() {
    return assertedOff(
        supportsVectorSearch(),
        "VECTOR_SEARCH",
        () ->
            repository()
                .semantic(
                    new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3"),
                    vec(1f, 0f),
                    null,
                    10,
                    ALL_VISIBLE));
  }

  @Autowired protected DSLContext dsl;

  /** [a,b,0,0,...] 형태의 1024차원 쿼리 벡터. */
  private float[] vec(float a, float b) {
    float[] v = new float[1024];
    v[0] = a;
    v[1] = b;
    return v;
  }

  /** 테스트용 사용자 생성 후 id 반환. */
  private Long createUser(String username) {
    return dsl.fetchOne(
            "INSERT INTO \"user\"(username, password, name, email) VALUES (?, 'x', ?, ?) RETURNING id",
            username,
            username,
            username + "@example.com")
        .get(0, Long.class);
  }

  /** 데이터셋 생성 후 id 반환. */
  private Long createDataset(
      String name, String tableName, String storageType, String originType, Long userId) {
    return dsl.fetchOne(
            "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by)"
                + " VALUES (?, ?, ?, ?, ?) RETURNING id",
            name,
            tableName,
            storageType,
            originType,
            userId)
        .get(0, Long.class);
  }

  /** 시드 벡터의 공간 — 1024차원 bge-m3. */
  protected static final EmbeddingSpace SEED_SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");

  /**
   * source_text 를 dataset_embedding 에 넣고, 벡터(있으면)는 표시 열과 함께 구현의 저장소에 시드한다. vector 가 null 이면 벡터를 만들지
   * 않는다 (비동기 임베딩 대기 상태). 키워드 색인은 구현마다 다르다 — pgvector 는 원문 열 자체, 별도 저장소는 {@link #seedIndex} 가 쓴다.
   */
  private void seedEmbedding(Long datasetId, String sourceText, float[] vector) {
    dsl.execute(
        "INSERT INTO dataset_embedding(dataset_id, source_text) VALUES (?,?)",
        datasetId,
        sourceText);
    if (vector != null) {
      // 표시 열 — 별도 저장소 구현은 사후 확인에서 공간 일치를 이 열로 본다(WD-720).
      dsl.execute(
          "UPDATE dataset_embedding SET embedded_dim = ?, embedded_model = ? WHERE dataset_id = ?",
          SEED_SPACE.dimension().size(),
          SEED_SPACE.model(),
          datasetId);
    }
    seedIndex(datasetId, sourceText, vector);
  }

  /**
   * 구현 저장소에 색인을 시드한다. 기본(pgvector)은 벡터만 차원 테이블에 넣는다(#713 — 키워드는 원문 열 자체). 별도 저장소 구현의 하위 클래스가 재정의한다.
   */
  protected void seedIndex(long datasetId, String sourceText, float[] vector) {
    if (vector != null) {
      dsl.execute(
          "INSERT INTO dataset_embedding_vec_1024(dataset_id, embedding, embedding_model)"
              + " VALUES (?, ?::vector, 'bge-m3')",
          datasetId,
          com.smartfirehub.embedding.repository.VectorLiterals.toVectorLiteral(vector));
    }
  }

  // 저장·검색 인프라(벡터 차원·모델 필터·실행 계획)를 검증하는 테스트라 가시성은 항상 참인 SQL 을 넘긴다 — 가시성 자체는 securitylevel 패키지 테스트가
  // 고정한다.
  // 제한 없는 가시성(예전 문자열 "TRUE") — 이 테스트는 등급 가시성이 아니라 검색 자체를 본다(WD-485 T10: 인터페이스가 도메인 값을 받는다).
  private static final DatasetVisibility ALL_VISIBLE = DatasetVisibility.UNRESTRICTED;

  @Test
  void searchByTrigramFindsTermAndScoresPositive() {
    if (trigramOff()) return;
    Long userId = createUser("dssearch_trgm");
    Long matched = createDataset("화재 통계 데이터셋", "data.ds_trgm_a", "DOCUMENT", "SOURCE", userId);
    Long unrelated = createDataset("전혀 무관한 인사 자료", "data.ds_trgm_b", "DOCUMENT", "SOURCE", userId);
    seedEmbedding(matched, "연도별 화재 발생 건수 및 피해 통계", null);
    seedEmbedding(unrelated, "직원 인사 발령 일반 자료", null);

    var hits = repository().keyword("화재", null, 10, ALL_VISIBLE);

    // 시드한 '화재' 포함 데이터셋이 후보로 나오고 score > 0.
    assertThat(hits).isNotEmpty();
    assertThat(hits).anyMatch(h -> h.datasetId().equals(matched));
    var hit = hits.stream().filter(h -> h.datasetId().equals(matched)).findFirst().orElseThrow();
    assertThat(hit.score()).isGreaterThan(0.0);
    assertThat(hit.name()).isEqualTo("화재 통계 데이터셋");
  }

  @Test
  void searchByTrigramAppliesStorageTypeFilter() {
    if (trigramOff()) return;
    Long userId = createUser("dssearch_filter");
    Long doc = createDataset("화재 문서셋", "data.ds_f_doc", "DOCUMENT", "SOURCE", userId);
    Long table = createDataset("화재 테이블셋", "data.ds_f_tbl", "TABLE", "SOURCE", userId);
    seedEmbedding(doc, "화재 관련 비정형 문서 모음", null);
    seedEmbedding(table, "화재 관련 정형 테이블 데이터", null);

    var hits = repository().keyword("화재", "DOCUMENT", 10, ALL_VISIBLE);

    // storageType 필터 → 결과 전부 DOCUMENT.
    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(h -> h.storageType().equals("DOCUMENT"));
    assertThat(hits).anyMatch(h -> h.datasetId().equals(doc));
    assertThat(hits).noneMatch(h -> h.datasetId().equals(table));
  }

  @Test
  void searchByCosineRanksNearestFirstAndExcludesNullEmbedding() {
    if (vectorOff()) return;
    Long userId = createUser("dssearch_cos");
    Long near = createDataset("근접 데이터셋", "data.ds_cos_near", "TABLE", "SOURCE", userId);
    Long far = createDataset("원거리 데이터셋", "data.ds_cos_far", "TABLE", "DERIVED", userId);
    Long pending = createDataset("임베딩 대기 데이터셋", "data.ds_cos_pending", "TABLE", "SOURCE", userId);
    seedEmbedding(near, "근접 본문", vec(1f, 0f)); // 쿼리 [1,0,..] 과 동일 방향
    seedEmbedding(far, "원거리 본문", vec(0f, 1f)); // 직교
    seedEmbedding(pending, "임베딩 미생성 본문", null); // embedding NULL — 코사인 결과에서 제외되어야 함

    var hits =
        repository()
            .semantic(
                new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3"),
                vec(1f, 0f),
                null,
                10,
                ALL_VISIBLE);

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
    if (vectorOff()) return;
    Long userId = createUser("dssearch_cos_filter");
    Long doc = createDataset("코사인 문서셋", "data.ds_cf_doc", "DOCUMENT", "SOURCE", userId);
    Long table = createDataset("코사인 테이블셋", "data.ds_cf_tbl", "TABLE", "SOURCE", userId);
    seedEmbedding(doc, "문서 본문", vec(1f, 0f));
    seedEmbedding(table, "테이블 본문", vec(1f, 0f));

    var hits =
        repository()
            .semantic(
                new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3"),
                vec(1f, 0f),
                "DOCUMENT",
                10,
                ALL_VISIBLE);

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(h -> h.storageType().equals("DOCUMENT"));
    assertThat(hits).noneMatch(h -> h.datasetId().equals(table));
  }

  /**
   * 회귀 가드: FILE(오브젝트) 데이터셋도 storageType 필터 없이 검색되면 결과에 포함되어야 한다. 검색/색인 계층은 storage_type 을 배제하지
   * 않으므로(동적 필터만 존재) FILE 도 유효 후보다. 누군가 `AND storage_type IN ('TABLE','DOCUMENT')` 같은 제약을 추가하면 이
   * 테스트가 깨진다.
   */
  @Test
  void searchByTrigramIncludesFileStorageTypeWhenUnfiltered() {
    if (trigramOff()) return;
    Long userId = createUser("dssearch_file");
    Long file = createDataset("화재 현장 사진 모음", "data.ds_file_a", "FILE", "SOURCE", userId);
    seedEmbedding(file, "화재 현장 사진 및 첨부 파일 오브젝트", null);

    var hits = repository().keyword("화재", null, 10, ALL_VISIBLE);

    assertThat(hits).anyMatch(h -> h.datasetId().equals(file));
    var hit = hits.stream().filter(h -> h.datasetId().equals(file)).findFirst().orElseThrow();
    assertThat(hit.storageType()).isEqualTo("FILE");
  }

  /** FILE 로 storageType 필터를 걸면 FILE 데이터셋만 반환된다. */
  @Test
  void searchByTrigramAppliesFileStorageTypeFilter() {
    if (trigramOff()) return;
    Long userId = createUser("dssearch_file_filter");
    Long file = createDataset("화재 파일셋", "data.ds_ff_file", "FILE", "SOURCE", userId);
    Long doc = createDataset("화재 문서셋2", "data.ds_ff_doc", "DOCUMENT", "SOURCE", userId);
    seedEmbedding(file, "화재 관련 파일 오브젝트 모음", null);
    seedEmbedding(doc, "화재 관련 비정형 문서", null);

    var hits = repository().keyword("화재", "FILE", 10, ALL_VISIBLE);

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(h -> h.storageType().equals("FILE"));
    assertThat(hits).anyMatch(h -> h.datasetId().equals(file));
    assertThat(hits).noneMatch(h -> h.datasetId().equals(doc));
  }

  private static int indexOf(java.util.List<DatasetSearchHit> hits, Long datasetId) {
    for (int i = 0; i < hits.size(); i++) {
      if (hits.get(i).datasetId().equals(datasetId)) return i;
    }
    return -1;
  }

  /**
   * 「전부 지우기」(데이터셋 삭제의 명시 삭제, ADR 0002 결정 6)는 pgvector 에서는 CASCADE 가 맡아 할 일이 없고, 꺼짐 구현에서는 지울 것이 없다.
   * 두 구현 모두 예외 없이 끝나야 한다 — 삭제 경로는 기능 가드 밖이다.
   */
  @Test
  void deleteAllNeverThrows() {
    repository().deleteAll(1L, java.util.List.of(1L, 2L));
    repository().deleteAll(1L, java.util.List.of());
  }
}
