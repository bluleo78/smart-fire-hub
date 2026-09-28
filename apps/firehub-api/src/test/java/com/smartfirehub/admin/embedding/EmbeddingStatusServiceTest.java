package com.smartfirehub.admin.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.reembed.EmbeddingReembedStateRepository;
import com.smartfirehub.embedding.reembed.ReembedState;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 현황 집계: 현재 공간 기준 분자, 미설정이면 409 가 아니라 configured=false, 잡 FAILED 사유 노출. */
@ExtendWith(MockitoExtension.class)
class EmbeddingStatusServiceTest {

  @Mock private DatasetEmbeddingRepository datasetRepo;
  @Mock private DocumentChunkRepository chunkRepo;
  @Mock private EmbeddingConfigService configService;
  @Mock private EmbeddingReembedStateRepository stateRepo;

  private EmbeddingStatusService service() {
    return new EmbeddingStatusService(datasetRepo, chunkRepo, configService, stateRepo);
  }

  @Test
  void statusAggregatesCurrentSpaceCountsAndJob() {
    EmbeddingSpace space = new EmbeddingSpace(EmbeddingDimension.D1536, "text-embedding-3-small");
    when(configService.currentSpace()).thenReturn(Optional.of(space));
    when(datasetRepo.countAll()).thenReturn(28L);
    when(datasetRepo.countEmbedded(space)).thenReturn(20L);
    when(chunkRepo.countAllChunks()).thenReturn(500L);
    when(chunkRepo.countEmbedded(space)).thenReturn(340L);
    OffsetDateTime at = OffsetDateTime.parse("2026-09-28T10:00:00Z");
    when(stateRepo.find()).thenReturn(Optional.of(new ReembedState("FAILED", space.model(), 1536, "401 Unauthorized", at)));

    EmbeddingStatusResponse r = service().status();

    assertThat(r.configured()).isTrue();
    assertThat(r.model()).isEqualTo("text-embedding-3-small");
    assertThat(r.dimension()).isEqualTo(1536);
    assertThat(r.datasets()).isEqualTo(new EmbeddingStatusResponse.Counts(28, 20));
    assertThat(r.documentChunks()).isEqualTo(new EmbeddingStatusResponse.Counts(500, 340));
    assertThat(r.job().status()).isEqualTo("FAILED");
    assertThat(r.job().lastError()).isEqualTo("401 Unauthorized");
  }

  @Test
  void statusForUnconfiguredTenant() {
    // Review Focus: 배포 직후 모든 테넌트가 이 상태 — 탭이 그려져야 설정을 저장할 수 있다.
    when(configService.currentSpace()).thenReturn(Optional.empty());
    when(datasetRepo.countAll()).thenReturn(3L);
    when(chunkRepo.countAllChunks()).thenReturn(7L);
    when(stateRepo.find()).thenReturn(Optional.empty());

    EmbeddingStatusResponse r = service().status();

    assertThat(r.configured()).isFalse();
    assertThat(r.model()).isNull();
    assertThat(r.datasets()).isEqualTo(new EmbeddingStatusResponse.Counts(3, 0));
    assertThat(r.documentChunks()).isEqualTo(new EmbeddingStatusResponse.Counts(7, 0));
    assertThat(r.job()).isNull();
  }
}
