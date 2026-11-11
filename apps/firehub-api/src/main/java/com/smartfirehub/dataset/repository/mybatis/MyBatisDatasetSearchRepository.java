package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.repository.DatasetSearchRepository;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * DatasetSearchRepository 의 MyBatis 구현(WD-707) — 두 메서드 모두 꺼진 기능 경로다(스펙 D1).
 *
 * <p>코사인 검색은 벡터 테이블(pgvector)·HNSW, 키워드 검색은 pg_trgm {@code %>}·word_similarity 에 기댄다. Tibero 기준선에는
 * 둘 다 없고, {@code DatasetSearchService} 가 각 단계를 {@code
 * PlatformDbFeatures}(VECTOR_SEARCH·TRIGRAM_SEARCH) 가드 뒤에서만 부른다. 그래서 빈 목록 대신 {@link
 * UnsupportedOperationException} 을 던져 가드가 빠진 회귀를 드러낸다. PG 프로필의 이 빈도 같다 — PG 운영은 jOOQ 구현이다. 매퍼가
 * 없다(문장이 없다).
 *
 * <p>jOOQ 구현처럼 {@code @Transactional} 을 달지 않는다.
 */
@PlatformMyBatisRepository
public class MyBatisDatasetSearchRepository implements DatasetSearchRepository {

  /** 벡터 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public List<DatasetSearchHit> searchByCosine(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      DatasetVisibility visibility) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 트라이그램 검색이 꺼진 플랫폼 DB 라 지원하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다. */
  @Override
  public List<DatasetSearchHit> searchByTrigram(
      String query, String storageType, int topK, DatasetVisibility visibility) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.TRIGRAM_SEARCH);
  }
}
