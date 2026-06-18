package com.smartfirehub.admin.embedding;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 현재 모델 기준 임베딩 진행 상태 집계. */
@RequiredArgsConstructor
@Service
public class EmbeddingStatusService {
  private final DatasetEmbeddingRepository datasetEmbeddingRepository;
  private final DocumentChunkRepository documentChunkRepository;
  private final EmbeddingProviderFactory embeddingFactory;

  /** 데이터셋 카탈로그와 문서 청크의 총계 및 현재 모델 임베딩 완료 수를 집계해 반환. */
  public EmbeddingStatusResponse status() {
    String model = embeddingFactory.current().modelId();
    return new EmbeddingStatusResponse(
        model,
        new EmbeddingStatusResponse.Counts(
            datasetEmbeddingRepository.countAllDatasets(),
            datasetEmbeddingRepository.countEmbeddedByModel(model)),
        new EmbeddingStatusResponse.Counts(
            documentChunkRepository.countAllChunks(),
            documentChunkRepository.countEmbeddedByModel(model)));
  }
}
