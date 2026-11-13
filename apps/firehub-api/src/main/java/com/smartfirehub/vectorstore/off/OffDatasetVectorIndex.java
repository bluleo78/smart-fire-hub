package com.smartfirehub.vectorstore.off;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tibero+platform 조합의 {@link DatasetVectorIndex} 구현. 벡터·키워드 검색이 꺼져 있어 모든 메서드가 예외를 던진다.
 *
 * <p>예전 {@code MyBatisDatasetSearchRepository} 와 {@code MyBatisDatasetEmbeddingRepository} 의 벡터
 * 메서드가 하던 역할이다. 서비스는 {@code VectorStoreFeatures} 가드 뒤에서만 이 포트를 부른다. 빈 결과 대신 예외를 던지는 이유는 가드가 빠진 회귀가
 * 조용히 숨지 않게 하려는 것이다. 예외 종류와 문구는 예전과 같다({@link PlatformFeatureOff}).
 */
@Component
@ConditionalOnTiberoPlatformDb
@ConditionalOnPlatformVectorStore
public class OffDatasetVectorIndex implements DatasetVectorIndex {

  @Override
  public List<DatasetSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      DatasetVisibility visibility) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  @Override
  public List<DatasetSearchHit> keyword(
      String query, String storageType, int topK, DatasetVisibility visibility) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.TRIGRAM_SEARCH);
  }

  @Override
  public void upsertDense(EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  @Override
  public int deleteDense(long tenantId, List<Long> datasetIds) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  @Override
  public int deleteOtherDimensions(long tenantId, EmbeddingDimension keep) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  /** 꺼진 조합에는 지울 색인이 없다. 삭제 경로는 기능 가드 밖이라 예외를 던지지 않는다. */
  @Override
  public void deleteAll(long tenantId, List<Long> datasetIds) {}
}
