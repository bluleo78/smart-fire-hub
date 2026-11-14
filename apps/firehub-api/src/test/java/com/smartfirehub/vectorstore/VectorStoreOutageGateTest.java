package com.smartfirehub.vectorstore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.reembed.EmbeddingBacklogService;
import com.smartfirehub.embedding.reembed.EmbeddingBacklogSweepScheduler;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.vectorstore.qdrant.KeywordBacklogSweepScheduler;
import com.smartfirehub.vectorstore.qdrant.QdrantOutboxApplier;
import com.smartfirehub.vectorstore.qdrant.VectorOutboxWorker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 벡터 저장소가 내려가 있을 때 배경 작업·커밋 뒤 쓰기가 건너뛰는지(WD-724). 돌리면 테넌트·배치마다 제한 시간까지 기다리고 실패 기록만 쌓이므로, 가용성 캐시를 보고
 * 아무것도 하지 않아야 한다. 할 일은 표시·아웃박스 행으로 남아 복구 뒤 회차가 처리한다.
 */
class VectorStoreOutageGateTest {

  /** 지원은 하지만 지금은 응답하지 않는 저장소. */
  private static final VectorStoreFeatures DOWN =
      new VectorStoreFeatures() {
        @Override
        public boolean supports(PlatformDbFeature feature) {
          return true;
        }

        @Override
        public boolean available() {
          return false;
        }
      };

  @Test
  void outboxWorkerSkipsRound() {
    TenantScopedRunner runner = mock(TenantScopedRunner.class);
    new VectorOutboxWorker(
            runner, mock(VectorOutboxRepository.class), mock(QdrantOutboxApplier.class), DOWN, true)
        .poll();
    verifyNoInteractions(runner);
  }

  @Test
  void keywordSweepSkipsRound() {
    TenantScopedRunner runner = mock(TenantScopedRunner.class);
    new KeywordBacklogSweepScheduler(runner, mock(KeywordIndexService.class), DOWN, true).sweep();
    verifyNoInteractions(runner);
  }

  @Test
  void embeddingBacklogSweepDoesNotEnqueueJobs() {
    TenantScopedRunner runner = mock(TenantScopedRunner.class);
    TenantReembedJob job = mock(TenantReembedJob.class);
    new EmbeddingBacklogSweepScheduler(
            runner,
            mock(EmbeddingConfigService.class),
            mock(EmbeddingBacklogService.class),
            mock(EmbeddingReembedStateRepository.class),
            job,
            DOWN,
            true)
        .sweep();
    verifyNoInteractions(runner);
    verify(job, never()).enqueue(anyLong());
  }

  @Test
  void keywordWritesAreSkippedSoRequestsDoNotWaitForTimeouts() {
    // 커밋 뒤 요청 스레드에서 도는 쓰기 — 저장소가 패킷을 버리는 동안 응답을 제한 시간만큼 붙들면 안 된다.
    DatasetVectorIndex datasetIndex = mock(DatasetVectorIndex.class);
    ChunkVectorIndex chunkIndex = mock(ChunkVectorIndex.class);
    when(datasetIndex.separateKeywordIndex()).thenReturn(true);
    when(chunkIndex.separateKeywordIndex()).thenReturn(true);
    KeywordIndexService service =
        new KeywordIndexService(
            datasetIndex,
            chunkIndex,
            mock(DatasetEmbeddingRepository.class),
            mock(DocumentChunkRepository.class),
            DOWN,
            mock(PlatformTransactionManager.class));

    service.indexDatasetAfterCommit(1L);
    service.indexChunks(List.of(new DocumentChunkRepository.ChunkSource(1L, 2L, 3L, "본문")));

    verify(datasetIndex, never()).upsertKeyword(anyLong(), any());
    verify(chunkIndex, never()).upsertKeyword(anyLong(), any());
  }
}
