package com.smartfirehub.dataset.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** DatasetEmbeddingService 단위 테스트: 동기 source_text / 비동기 embedding 경로 분리 검증. */
@ExtendWith(MockitoExtension.class)
class DatasetEmbeddingServiceTest {

  @Mock DatasetEmbeddingRepository embeddingRepo;
  @Mock DatasetMetaReader metaReader;
  @Mock EmbeddingProviderFactory embeddingFactory;
  @Mock EmbeddingProvider provider;
  @Mock EmbeddingAiGate aiGate;

  @Test
  void syncSourceText_임베딩없이_source_text만_동기_upsert한다() {
    when(metaReader.read(7L))
        .thenReturn(
            new DatasetSourceTextBuilder.Input(
                "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전"));
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory, aiGate)
        .syncSourceText(7L);
    // source_text 만 갱신하고, 외부 호출(임베딩 provider)은 절대 일어나지 않아야 한다.
    verify(embeddingRepo).upsertSourceText(eq(7L), any(String.class));
    verifyNoInteractions(embeddingFactory);
  }

  @Test
  void syncSourceText_삭제된_데이터셋이면_인덱스를_지운다() {
    when(metaReader.read(99L)).thenReturn(null);
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory, aiGate)
        .syncSourceText(99L);
    verify(embeddingRepo).delete(99L);
    verifyNoInteractions(embeddingFactory);
  }

  @Test
  void reindexEmbedding_메타를_임베딩해_embedding을_갱신한다() {
    when(metaReader.read(7L))
        .thenReturn(
            new DatasetSourceTextBuilder.Input(
                "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전"));
    when(aiGate.datasetAllowed(7L)).thenReturn(true);
    when(embeddingFactory.current()).thenReturn(provider);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.dimension()).thenReturn(1024);
    when(provider.embed(any())).thenReturn(List.of(new float[1024]));
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory, aiGate)
        .reindexEmbedding(7L, 1L);
    verify(embeddingRepo)
        .upsertEmbedding(
            eq(new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3")), eq(7L), any(float[].class));
  }

  @Test
  void reindexEmbedding_삭제된_데이터셋이면_no_op이다() {
    when(metaReader.read(99L)).thenReturn(null);
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory, aiGate)
        .reindexEmbedding(99L, 1L);
    // 메타가 없으면 임베딩 생성·갱신을 시도하지 않는다(동기 경로에서 이미 제거됨).
    verifyNoInteractions(embeddingFactory);
    verifyNoInteractions(embeddingRepo);
  }

  @Test
  void reindexEmbedding_임베딩_미설정이면_건너뛴다() {
    when(metaReader.read(7L))
        .thenReturn(
            new DatasetSourceTextBuilder.Input(
                "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전"));
    when(aiGate.datasetAllowed(7L)).thenReturn(true);
    when(embeddingFactory.current()).thenThrow(new EmbeddingNotConfiguredException());
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory, aiGate)
        .reindexEmbedding(7L, 1L);
    verifyNoInteractions(embeddingRepo);
  }

  @Test
  void reindexEmbedding_등급이_임베딩_공급자를_불허하면_공급자를_부르지_않고_남은_벡터를_지운다() {
    // S3 §4.3: 민감 등급 + 외부 임베딩 — 메타(이름·설명·컬럼)를 외부 공급자로 보내지 않는다.
    when(metaReader.read(7L))
        .thenReturn(
            new DatasetSourceTextBuilder.Input(
                "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전"));
    when(aiGate.datasetAllowed(7L)).thenReturn(false);
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory, aiGate)
        .reindexEmbedding(7L, 1L);
    verifyNoInteractions(embeddingFactory);
    verify(embeddingRepo).deleteVectors(7L);
    verify(embeddingRepo, never()).upsertEmbedding(any(), any(Long.class), any());
  }
}
