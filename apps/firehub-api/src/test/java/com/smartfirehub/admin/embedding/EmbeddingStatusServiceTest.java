package com.smartfirehub.admin.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 순수 단위 테스트: 두 레포지토리/팩토리/프로바이더를 mock 하여 집계 로직만 검증. */
@ExtendWith(MockitoExtension.class)
class EmbeddingStatusServiceTest {

  @Mock private DatasetEmbeddingRepository datasetEmbeddingRepository;
  @Mock private DocumentChunkRepository documentChunkRepository;
  @Mock private EmbeddingProviderFactory embeddingFactory;
  @Mock private EmbeddingProvider provider;

  /** 현재 모델 기준으로 데이터셋/문서청크 총계·임베딩 완료 수를 올바르게 조립하는지 확인. */
  @Test
  void status_aggregatesCurrentModelCounts() {
    // 현재 활성 모델은 bge-m3
    when(embeddingFactory.current()).thenReturn(provider);
    when(provider.modelId()).thenReturn("bge-m3");
    // 두 레포지토리 모두 countEmbeddedByModel 를 갖고 있으므로 올바른 mock 인스턴스에 stub
    when(datasetEmbeddingRepository.countAllDatasets()).thenReturn(28L);
    when(datasetEmbeddingRepository.countEmbeddedByModel("bge-m3")).thenReturn(28L);
    when(documentChunkRepository.countAllChunks()).thenReturn(500L);
    when(documentChunkRepository.countEmbeddedByModel("bge-m3")).thenReturn(340L);

    EmbeddingStatusService service =
        new EmbeddingStatusService(
            datasetEmbeddingRepository, documentChunkRepository, embeddingFactory);

    EmbeddingStatusResponse response = service.status();

    assertThat(response.model()).isEqualTo("bge-m3");
    assertThat(response.datasets().total()).isEqualTo(28L);
    assertThat(response.datasets().embedded()).isEqualTo(28L);
    assertThat(response.documentChunks().total()).isEqualTo(500L);
    assertThat(response.documentChunks().embedded()).isEqualTo(340L);
  }
}
