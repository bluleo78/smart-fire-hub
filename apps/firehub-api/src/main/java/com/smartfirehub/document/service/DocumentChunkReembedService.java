package com.smartfirehub.document.service;

import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;

/**
 * 기존 문서 청크를 현재 모델로 전체 재임베딩 (재청킹 없이 embedding 만 갱신).
 *
 * <p>모델 교체/차원 변경 시 누적된 문서 청크의 벡터를 일괄 재생성하기 위한 오케스트레이터. 데이터셋별 잡으로 분산 enqueue 하여 비용이 큰 임베딩 외부 호출을
 * 백그라운드로 이전한다 (DatasetEmbeddingBackfillService 와 동일 메커니즘).
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DocumentChunkReembedService {

  // 임베딩 외부 호출/배치 갱신 단위 (DocumentChunkRepository.BATCH_SIZE 와 동일).
  private static final int EMBED_BATCH = 200;

  private final DocumentChunkRepository repository;
  private final EmbeddingProviderFactory embeddingFactory;
  private final JobScheduler jobScheduler;

  /**
   * 청크 보유 DOCUMENT 데이터셋마다 재임베딩 잡을 enqueue 한다.
   *
   * @return 예약된 데이터셋 수
   */
  public int reembedAll() {
    List<Long> datasetIds = repository.findDocumentDatasetIds();
    for (Long id : datasetIds) {
      long datasetId = id; // 람다 캡처를 위한 final 지역 복사
      jobScheduler.enqueue(() -> reembedDataset(datasetId));
    }
    log.info("Document chunk reembedding scheduled: count={}", datasetIds.size());
    return datasetIds.size();
  }

  /** 데이터셋의 전체 청크를 현재 모델로 재임베딩한다 (배치 단위로 임베딩→갱신). */
  @Job(name = "Document chunk reembedding: dataset %0")
  public void reembedDataset(long datasetId) {
    List<ChunkContent> chunks = repository.findChunkContentsByDataset(datasetId);
    if (chunks.isEmpty()) {
      return;
    }
    EmbeddingProvider provider = embeddingFactory.current();
    String model = provider.modelId();
    for (int from = 0; from < chunks.size(); from += EMBED_BATCH) {
      List<ChunkContent> batch = chunks.subList(from, Math.min(from + EMBED_BATCH, chunks.size()));
      List<Long> ids = new ArrayList<>(batch.size());
      List<String> contents = new ArrayList<>(batch.size());
      for (ChunkContent c : batch) {
        ids.add(c.chunkId());
        contents.add(c.content());
      }
      List<float[]> embeddings = provider.embed(contents);
      repository.updateEmbeddingBatch(ids, embeddings, model);
    }
    log.info("Document chunk reembedding done: datasetId={}, chunks={}", datasetId, chunks.size());
  }
}
