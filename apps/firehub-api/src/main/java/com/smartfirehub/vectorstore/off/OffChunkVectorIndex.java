package com.smartfirehub.vectorstore.off;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.vectorstore.ChunkRef;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tibero+platform 조합의 {@link ChunkVectorIndex} 구현. 모든 메서드가 예외를 던진다.
 *
 * <p>예전 {@code MyBatisDocumentChunkRepository} 의 벡터·트라이그램 메서드가 하던 역할이다. 서비스는 {@code
 * VectorStoreFeatures} 가드 뒤에서만 부른다. 예외 종류와 문구는 예전과 같다({@link PlatformFeatureOff}).
 */
@Component
@ConditionalOnTiberoPlatformDb
@ConditionalOnPlatformVectorStore
public class OffChunkVectorIndex implements ChunkVectorIndex {

  @Override
  public List<DocumentSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      List<Long> datasetIds,
      int topK,
      DatasetVisibility visibility) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  @Override
  public List<DocumentSearchHit> keyword(
      String query, List<Long> datasetIds, int topK, DatasetVisibility visibility) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.TRIGRAM_SEARCH);
  }

  @Override
  public void upsertDense(EmbeddingSpace space, List<ChunkRef> chunks, List<float[]> embeddings) {
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
  public void deleteAllOfDocument(long tenantId, long documentFileId) {}

  /** 꺼진 조합에는 지울 색인이 없다. */
  @Override
  public void deleteAllOfDatasets(long tenantId, List<Long> datasetIds) {}
}
