package com.smartfirehub.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import java.util.List;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** DocumentChunkReembedService 단위 테스트: 데이터셋별 잡 enqueue 와 청크 재임베딩 배치 갱신 검증. */
@ExtendWith(MockitoExtension.class)
class DocumentChunkReembedServiceTest {

  @Mock DocumentChunkRepository repository;
  @Mock EmbeddingProviderFactory embeddingFactory;
  @Mock JobScheduler jobScheduler;
  @Mock EmbeddingProvider provider;

  @Test
  void reembedAll_데이터셋마다_잡을_enqueue하고_개수를_반환한다() {
    when(repository.findDocumentDatasetIds()).thenReturn(List.of(1L, 2L, 3L));

    DocumentChunkReembedService service =
        new DocumentChunkReembedService(repository, embeddingFactory, jobScheduler);
    int scheduled = service.reembedAll();

    // 청크 보유 DOCUMENT 데이터셋 수만큼 잡이 예약되고 그 수가 반환된다.
    assertThat(scheduled).isEqualTo(3);
    verify(jobScheduler, times(3)).enqueue(any(JobLambda.class));
  }

  @Test
  void reembedDataset_청크를_현재모델로_재임베딩하고_배치_갱신한다() {
    when(repository.findChunkContentsByDataset(7L))
        .thenReturn(List.of(new ChunkContent(10L, "내용A"), new ChunkContent(11L, "내용B")));
    when(embeddingFactory.current()).thenReturn(provider);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.embed(List.of("내용A", "내용B")))
        .thenReturn(List.of(new float[1024], new float[1024]));

    DocumentChunkReembedService service =
        new DocumentChunkReembedService(repository, embeddingFactory, jobScheduler);
    service.reembedDataset(7L);

    // 청크 id 순서를 유지한 채 현재 모델 식별자로 임베딩 배치 갱신이 호출된다.
    verify(repository).updateEmbeddingBatch(eq(List.of(10L, 11L)), any(), eq("bge-m3"));
  }

  @Test
  void reembedDataset_청크가_없으면_갱신을_호출하지_않는다() {
    when(repository.findChunkContentsByDataset(7L)).thenReturn(List.of());

    DocumentChunkReembedService service =
        new DocumentChunkReembedService(repository, embeddingFactory, jobScheduler);
    service.reembedDataset(7L);

    // 빈 데이터셋은 provider 호출/배치 갱신 없이 조기 반환한다.
    verify(repository, never()).updateEmbeddingBatch(any(), any(), any());
  }
}
