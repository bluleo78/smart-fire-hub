package com.smartfirehub.admin.embedding;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 모델 기준 임베딩 진행 상태 집계. */
@RequiredArgsConstructor
@Service
public class EmbeddingStatusService {
  private final DatasetEmbeddingRepository datasetEmbeddingRepository;
  private final DocumentChunkRepository documentChunkRepository;
  private final EmbeddingProviderFactory embeddingFactory;

  /** 데이터셋 카탈로그와 문서 청크의 총계 및 현재 모델 임베딩 완료 수를 집계해 반환. */
  // RLS 가 걸린 dataset_embedding/document_chunk 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public EmbeddingStatusResponse status() {
    // 분모·분자 모두 현재 공간(차원 테이블 + 모델) 기준. 데이터셋 분모는 dataset_embedding 행 수라 판정식과 모집단이 같다.
    EmbeddingSpace space = EmbeddingSpace.of(embeddingFactory.current());
    return new EmbeddingStatusResponse(
        space.model(),
        new EmbeddingStatusResponse.Counts(
            datasetEmbeddingRepository.countAll(), datasetEmbeddingRepository.countEmbedded(space)),
        new EmbeddingStatusResponse.Counts(
            documentChunkRepository.countAllChunks(), documentChunkRepository.countEmbedded(space)));
  }
}
