package com.smartfirehub.dataset.search;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

/** 커밋 후 이벤트 → 비동기 임베딩 재생성 호출 위임을 검증하는 단위 테스트. */
class DatasetReindexListenerTest {

  @Test
  void 커밋후_이벤트가_비동기_임베딩_재생성을_호출한다() {
    DatasetEmbeddingService service = mock(DatasetEmbeddingService.class);
    new DatasetReindexListener(service).onDatasetChanged(new DatasetChangedEvent(7L));
    verify(service).reindexEmbedding(7L);
  }
}
