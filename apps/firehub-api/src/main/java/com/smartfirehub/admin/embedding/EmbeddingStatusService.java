package com.smartfirehub.admin.embedding;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.reembed.EmbeddingReembedStateRepository;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 공간 기준 임베딩 진행 상태 + 마지막 재임베딩 잡 상태 집계. */
@RequiredArgsConstructor
@Service
public class EmbeddingStatusService {
  private final DatasetEmbeddingRepository datasetEmbeddingRepository;
  private final DocumentChunkRepository documentChunkRepository;
  private final EmbeddingConfigService configService;
  private final EmbeddingReembedStateRepository stateRepository;
  private final EmbeddingAiGate aiGate;

  /**
   * 현재 공간 기준 진행률 + 마지막 잡 상태. provider 를 만들지 않는다(설정 문서만 읽음) — 미설정이어도 409 로 죽지 않고 configured=false 로
   * 그려져야 사용자가 설정을 저장할 수 있다.
   */
  // RLS 테이블을 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public EmbeddingStatusResponse status() {
    // 분모는 공간과 무관하다. 데이터셋 분모는 dataset_embedding 행 수라 판정식과 모집단이 같다.
    // S3 §4.3: 등급이 임베딩 공급자를 허용하지 않는 데이터셋은 판정식에서 빠지므로 분모에서도 뺀다(아니면 100% 에 닿지 않는다).
    long datasetTotal =
        datasetEmbeddingRepository.countAll(aiGate.allowedDatasetSql("de.dataset_id"));
    long chunkTotal =
        documentChunkRepository.countAllChunks(aiGate.allowedDatasetSql("c.dataset_id"));
    EmbeddingStatusResponse.Job job =
        stateRepository
            .find()
            .map(s -> new EmbeddingStatusResponse.Job(s.status(), s.lastError(), s.updatedAt()))
            .orElse(null);
    Optional<EmbeddingSpace> space = configService.currentSpace();
    if (space.isEmpty()) {
      return new EmbeddingStatusResponse(
          false,
          null,
          null,
          new EmbeddingStatusResponse.Counts(datasetTotal, 0),
          new EmbeddingStatusResponse.Counts(chunkTotal, 0),
          job);
    }
    EmbeddingSpace s = space.get();
    return new EmbeddingStatusResponse(
        true,
        s.model(),
        s.dimension().size(),
        new EmbeddingStatusResponse.Counts(
            datasetTotal, datasetEmbeddingRepository.countEmbedded(s)),
        new EmbeddingStatusResponse.Counts(chunkTotal, documentChunkRepository.countEmbedded(s)),
        job);
  }
}
