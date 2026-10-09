package com.smartfirehub.embedding.reembed;

import com.smartfirehub.dataset.rowsearch.SearchIndexStateRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.dto.EmbeddingImpact;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재임베딩 판정식의 <b>유일한 구현</b>(스펙 §3.3-4): "현재 차원 테이블에 현재 모델 벡터가 없는 청크·데이터셋이 하나라도 있으면 할 일이 있다". 이전 설정과
 * 비교하지 않는다 — 이 한 규칙이 첫 저장, 모델 '&lt;unknown&gt;' 벡터, 같은 모델명·다른 차원을 모두 덮고, provider·Base URL·키만 바뀐
 * 저장은 참이 되지 않는다. 영향도 API·저장 시 투입·잡이 모두 여기(와 같은 저장소 메서드)를 쓴다.
 *
 * <p>S3 §4.3: 등급이 임베딩 공급자를 허용하지 않는 데이터셋(카탈로그·문서 청크)은 판정식에서 빠진다 — 그래서 호스팅 선언·등급 하향으로 허용이 되면 같은 판정식이
 * 스스로 "할 일 있음"이 되어 벡터가 되살아난다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingBacklogService {

  private final DocumentChunkRepository chunkRepository;
  private final DatasetEmbeddingRepository datasetRepository;
  private final SearchIndexStateRepository searchIndexStates;
  private final EmbeddingAiGate aiGate;

  @Transactional(readOnly = true)
  public EmbeddingImpact impact(EmbeddingSpace space) {
    return new EmbeddingImpact(
        chunkRepository.countMissing(space, aiGate.allowedDatasetSql("c.dataset_id")),
        datasetRepository.countMissing(space, aiGate.allowedDatasetSql("de.dataset_id")),
        searchIndexStates.countStale(space.model(), space.dimension().size()));
  }

  @Transactional(readOnly = true)
  public boolean hasWork(EmbeddingSpace space) {
    return chunkRepository.countMissing(space, aiGate.allowedDatasetSql("c.dataset_id")) > 0
        || datasetRepository.countMissing(space, aiGate.allowedDatasetSql("de.dataset_id")) > 0;
  }
}
