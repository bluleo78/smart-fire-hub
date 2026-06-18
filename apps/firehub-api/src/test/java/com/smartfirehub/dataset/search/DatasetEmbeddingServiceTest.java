package com.smartfirehub.dataset.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
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

  @Test
  void syncSourceText_임베딩없이_source_text만_동기_upsert한다() {
    when(metaReader.read(7L))
        .thenReturn(
            new DatasetSourceTextBuilder.Input(
                "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전"));
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory).syncSourceText(7L);
    // source_text 만 갱신하고, 외부 호출(임베딩 provider)은 절대 일어나지 않아야 한다.
    verify(embeddingRepo).upsertSourceText(eq(7L), any(String.class));
    verifyNoInteractions(embeddingFactory);
  }

  @Test
  void syncSourceText_삭제된_데이터셋이면_인덱스를_지운다() {
    when(metaReader.read(99L)).thenReturn(null);
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory).syncSourceText(99L);
    verify(embeddingRepo).delete(99L);
    verifyNoInteractions(embeddingFactory);
  }

  @Test
  void reindexEmbedding_메타를_임베딩해_embedding을_갱신한다() {
    when(metaReader.read(7L))
        .thenReturn(
            new DatasetSourceTextBuilder.Input(
                "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전"));
    when(embeddingFactory.current()).thenReturn(provider);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.embed(any())).thenReturn(List.of(new float[1024]));
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory).reindexEmbedding(7L);
    verify(embeddingRepo).updateEmbedding(eq(7L), any(float[].class), eq("bge-m3"));
  }

  @Test
  void reindexEmbedding_삭제된_데이터셋이면_no_op이다() {
    when(metaReader.read(99L)).thenReturn(null);
    new DatasetEmbeddingService(embeddingRepo, metaReader, embeddingFactory).reindexEmbedding(99L);
    // 메타가 없으면 임베딩 생성·갱신을 시도하지 않는다(동기 경로에서 이미 제거됨).
    verifyNoInteractions(embeddingFactory);
    verifyNoInteractions(embeddingRepo);
  }
}
