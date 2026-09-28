package com.smartfirehub.embedding.reembed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 잡 실패 경로(D1): 실패 기록·임대 해제가 또 던져도 원래 예외(JobRunr 재시도 대상)가 가려지지 않고, 뒤의 예외는
 * suppressed 로 남는다. 저장소 실패를 일부러 일으켜야 하므로 모의 객체로 조립한다(성공 경로는 TenantReembedJobTest).
 */
@ExtendWith(MockitoExtension.class)
class TenantReembedJobFailurePathTest {

  @Mock EmbeddingProviderFactory providerFactory;
  @Mock EmbeddingConfigService configService;
  @Mock DocumentChunkRepository chunkRepository;
  @Mock DatasetEmbeddingRepository datasetRepository;
  @Mock EmbeddingReembedStateRepository stateRepository;
  @Mock EmbeddingBacklogService backlogService;
  @Mock DatasetEmbeddingBackfillService backfillService;
  @Mock JobScheduler jobScheduler;

  private TenantReembedJob job;

  @BeforeEach
  void setUp() {
    job =
        new TenantReembedJob(
            providerFactory,
            configService,
            chunkRepository,
            datasetRepository,
            stateRepository,
            backlogService,
            backfillService,
            jobScheduler);
    when(stateRepository.tryAcquire(any())).thenReturn(true);
  }

  @Test
  void originalFailureSurvivesWhenMarkFailedAndReleaseAlsoThrow() {
    EmbeddingException boom = new EmbeddingException("공급자 500");
    when(providerFactory.current()).thenThrow(boom);
    IllegalStateException markFailed = new IllegalStateException("기록 실패");
    IllegalStateException release = new IllegalStateException("해제 실패");
    doThrow(markFailed).when(stateRepository).markFailed(anyString());
    doThrow(release).when(stateRepository).release();

    assertThatThrownBy(() -> job.run(7L)).isSameAs(boom);
    assertThat(boom.getSuppressed()).containsExactly(markFailed, release);
  }

  @Test
  void releaseFailureWithoutPrimaryIsThrown() {
    // 원래 예외가 없으면(미설정은 삼킨다) 해제 실패는 그대로 올라가야 한다 — 조용히 먹으면 임대가 남는다.
    when(providerFactory.current()).thenThrow(new EmbeddingNotConfiguredException());
    IllegalStateException release = new IllegalStateException("해제 실패");
    doThrow(release).when(stateRepository).release();

    assertThatThrownBy(() -> job.run(7L)).isSameAs(release);
  }
}
