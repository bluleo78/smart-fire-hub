package com.smartfirehub.dataset.search;

import static org.mockito.Mockito.*;

import com.smartfirehub.global.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 커밋 후 이벤트 → 비동기 임베딩 재생성 호출 위임을 검증하는 단위 테스트. */
class DatasetReindexListenerTest {

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void 커밋후_이벤트가_비동기_임베딩_재생성을_호출한다() {
    // @Async 경로는 TenantContextTaskDecorator 가 이미 컨텍스트를 세워 둔 상태를 가정한다.
    TenantContext.set(42L);
    DatasetEmbeddingService service = mock(DatasetEmbeddingService.class);
    new DatasetReindexListener(service).onDatasetChanged(new DatasetChangedEvent(7L));
    verify(service).reindexEmbedding(7L, 42L);
  }
}
