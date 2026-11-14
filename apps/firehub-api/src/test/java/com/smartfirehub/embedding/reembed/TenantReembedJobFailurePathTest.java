package com.smartfirehub.embedding.reembed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import com.smartfirehub.vectorstore.VectorStoreUnavailableException;
import java.util.List;
import java.util.Optional;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 잡 실패 경로(D1): 실패 기록·임대 해제가 또 던져도 원래 예외(JobRunr 재시도 대상)가 가려지지 않고, 뒤의 예외는 suppressed 로 남는다. 저장소 실패를
 * 일부러 일으켜야 하므로 모의 객체로 조립한다(성공 경로는 TenantReembedJobTest).
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
  @Mock EmbeddingAiGate aiGate;

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
            jobScheduler,
            aiGate,
            f -> true);
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

  @Test
  void vectorStoreOutageIsNotRecordedAsFailureAndNotRetried() {
    // WD-724: 저장소 일시 중단을 FAILED 로 남기면 백로그 스윕이 「설정 재저장 전까지」 재투입을 막아 복구 뒤에도 멈춘다.
    // 기록 없이 임대만 풀고 삼킨다 — 저장소가 돌아오면 백로그 스윕이 다시 넣는다.
    // 실제 경로대로 배치 벡터 쓰기(upsertEmbeddings)에서 저장소 예외를 낸다 — 중간에 다른 예외로 감싸지면 이 시험이 잡는다.
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.dimension()).thenReturn(1024);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.embed(any())).thenReturn(List.of(new float[1024]));
    when(providerFactory.current()).thenReturn(provider);
    EmbeddingSpace space = EmbeddingSpace.of(provider);
    when(configService.currentSpace()).thenReturn(Optional.of(space));
    when(chunkRepository.findMissing(eq(space), eq(0L), anyInt(), any()))
        .thenReturn(List.of(new DocumentChunkRepository.ChunkContent(11L, "본문")));
    doThrow(new VectorStoreUnavailableException("Qdrant 점 쓰기 실패: UNAVAILABLE", null))
        .when(chunkRepository)
        .upsertEmbeddings(eq(space), eq(List.of(11L)), any());

    job.run(7L); // 던지지 않는다 — JobRunr 재시도 없음

    verify(stateRepository, never()).markFailed(anyString());
    verify(stateRepository).release();
  }
}
