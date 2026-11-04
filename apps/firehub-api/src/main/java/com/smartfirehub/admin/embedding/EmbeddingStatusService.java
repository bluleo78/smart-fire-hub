package com.smartfirehub.admin.embedding;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.PlatformDbFeatures;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
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
  // 벡터 읽기 가드(WD-486 패턴) — 꺼진 플랫폼 DB 에서는 벡터 테이블을 읽는 분자(countEmbedded)만 0 으로 둔다.
  private final PlatformDbFeatures features;

  /**
   * 현재 공간 기준 진행률 + 마지막 잡 상태. provider 를 만들지 않는다(설정 문서만 읽음) — 미설정이어도 409 로 죽지 않고 configured=false 로
   * 그려져야 사용자가 설정을 저장할 수 있다.
   */
  // RLS 테이블을 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public EmbeddingStatusResponse status() {
    // 분모는 공간과 무관하다. 데이터셋 분모는 dataset_embedding 행 수라 판정식과 모집단이 같다.
    // S3 §4.3: 등급이 임베딩 공급자를 허용하지 않는 데이터셋은 판정식에서 빠지므로 분모에서도 뺀다(아니면 100% 에 닿지 않는다).
    // 허용값은 한 번만 캡처한다 — 두 분모가 같은 호스팅 스냅샷을 쓰고 설정을 한 번만 읽는다.
    EmbeddingAllowance a = aiGate.allowance();
    long datasetTotal = datasetEmbeddingRepository.countAll(a);
    long chunkTotal = documentChunkRepository.countAllChunks(a);
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
    // 분모(기반 테이블)는 그대로 두고, 벡터 테이블을 읽는 분자만 기능이 꺼지면 0 으로 돌려 분모가 정직하게 남게 한다.
    boolean vector = features.supports(PlatformDbFeature.VECTOR_SEARCH);
    return new EmbeddingStatusResponse(
        true,
        s.model(),
        s.dimension().size(),
        new EmbeddingStatusResponse.Counts(
            datasetTotal, vector ? datasetEmbeddingRepository.countEmbedded(s) : 0),
        new EmbeddingStatusResponse.Counts(
            chunkTotal, vector ? documentChunkRepository.countEmbedded(s) : 0),
        job);
  }
}
