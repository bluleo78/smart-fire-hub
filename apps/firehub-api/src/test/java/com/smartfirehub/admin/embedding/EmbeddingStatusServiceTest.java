package com.smartfirehub.admin.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.reembed.ReembedState;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
  @Mock private EmbeddingAiGate aiGate;
  // 벡터 기능이 켜진 플랫폼 DB(PG) — 꺼진 경로는 PlatformDbFeaturesOffPathTest 가 맡는다.
  private final com.smartfirehub.global.platformdb.PlatformDbFeatures features = f -> true;

  /**
   * 게이트가 캡처한 허용 값 — 분모 조회가 이 값을 그대로 받는지 스텁 일치로 확인한다. 별칭(de./c.) 렌더는 리포지토리 골든 테스트가 고정한다(WD-485 T10).
   */
  private static final EmbeddingAllowance ALLOWANCE =
      new EmbeddingAllowance(ProviderHosting.EXTERNAL);

  @BeforeEach
  void stubGate() {
    when(aiGate.allowance()).thenReturn(ALLOWANCE);
  }

  private EmbeddingStatusService service() {
    return new EmbeddingStatusService(
        datasetRepo, chunkRepo, configService, stateRepo, aiGate, features);
  }

  @Test
  void statusAggregatesCurrentSpaceCountsAndJob() {
    EmbeddingSpace space = new EmbeddingSpace(EmbeddingDimension.D1536, "text-embedding-3-small");
    when(configService.currentSpace()).thenReturn(Optional.of(space));
    when(datasetRepo.countAll(ALLOWANCE)).thenReturn(28L);
    when(datasetRepo.countEmbedded(space)).thenReturn(20L);
    when(chunkRepo.countAllChunks(ALLOWANCE)).thenReturn(500L);
    when(chunkRepo.countEmbedded(space)).thenReturn(340L);
    OffsetDateTime at = OffsetDateTime.parse("2026-09-28T10:00:00Z");
    when(stateRepo.find())
        .thenReturn(
            Optional.of(new ReembedState("FAILED", space.model(), 1536, "401 Unauthorized", at)));

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
    when(datasetRepo.countAll(ALLOWANCE)).thenReturn(3L);
    when(chunkRepo.countAllChunks(ALLOWANCE)).thenReturn(7L);
    when(stateRepo.find()).thenReturn(Optional.empty());

    EmbeddingStatusResponse r = service().status();

    assertThat(r.configured()).isFalse();
    assertThat(r.model()).isNull();
    assertThat(r.datasets()).isEqualTo(new EmbeddingStatusResponse.Counts(3, 0));
    assertThat(r.documentChunks()).isEqualTo(new EmbeddingStatusResponse.Counts(7, 0));
    assertThat(r.job()).isNull();
  }
}
