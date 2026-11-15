package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.SearchColumnRepository;
import com.smartfirehub.dataset.repository.SearchIndexStateRepository;
import com.smartfirehub.dataset.rowsearch.dto.RowSearchRequest;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.VectorStoreUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 별도 벡터 저장소(Qdrant)가 내려갔을 때 행 검색의 동작(WD-722, WD-724 와 같은 규칙).
 *
 * <ul>
 *   <li>동기화: 실패·백오프로 남기지 않고 건너뛴다(데이터셋 탓이 아니다).
 *   <li>스윕: 가용성이 거짓이면 그 주기를 쉰다.
 *   <li>검색: 500 이 아니라 결과 없이 색인 상태 {@code UNAVAILABLE} — 미리 알면 색인을 부르지 않고, 호출 중에 내려가도 같다.
 * </ul>
 */
class RowSearchOutageTest {

  private static final long ID = 42L;

  private final RowSearchIndex index = mock(RowSearchIndex.class);
  private final SearchIndexStateRepository states = mock(SearchIndexStateRepository.class);
  private final SearchColumnRepository searchColumns = mock(SearchColumnRepository.class);
  private final SearchSourceReader reader = mock(SearchSourceReader.class);
  private final DatasetRepository datasets = mock(DatasetRepository.class);
  private final DatasetColumnRepository columns = mock(DatasetColumnRepository.class);
  private final EmbeddingProviderFactory embeddings = mock(EmbeddingProviderFactory.class);

  /** 가용성을 바꿀 수 있는 기능 판정(행 검색은 지원). */
  private static final class Features implements VectorStoreFeatures {
    volatile boolean available = true;

    @Override
    public boolean supports(PlatformDbFeature feature) {
      return true;
    }

    @Override
    public boolean available() {
      return available;
    }
  }

  private final Features features = new Features();

  private static VectorStoreUnavailableException down() {
    return new VectorStoreUnavailableException("Qdrant 접속 불가", null);
  }

  private static SearchIndexState state() {
    return new SearchIndexState(
        ID, "IDLE", "h", "m", 8, 7L, null, null, null, 3, 3, 0, null, null, 1L);
  }

  @BeforeEach
  void tenant() {
    TenantContext.set(1L);
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
  }

  @Test
  void syncSkipsWithoutRecordingFailureWhenStoreIsDown() {
    RowSearchSyncService sync =
        new RowSearchSyncService(
            index,
            states,
            searchColumns,
            reader,
            datasets,
            embeddings,
            mock(EmbeddingAiGate.class),
            4,
            2);
    when(states.tryAcquireLease(eq(ID), any(Duration.class))).thenReturn(true);
    when(states.find(ID)).thenReturn(Optional.of(state()));
    when(datasets.findById(ID)).thenThrow(down());

    assertThat(sync.sync(ID)).isEqualTo(RowSearchSyncService.Outcome.SKIPPED);

    verify(states, never()).markFailed(anyLong(), anyString(), any());
    verify(states).releaseLease(ID);
  }

  @Test
  void sweepRestsWhileStoreIsUnavailable() {
    TenantScopedRunner runner = mock(TenantScopedRunner.class);
    RowSearchSyncService sync = mock(RowSearchSyncService.class);
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.backendFor(anyLong())).thenReturn(DataStoreBackend.POSTGRESQL);
    features.available = false;

    new RowSearchSweepScheduler(runner, states, sync, resolver, features, true, 60_000).sweep();

    verify(runner, never()).forEachActiveTenant(any());
    verify(sync, never()).sync(anyLong());
  }

  /** 검색 대상 데이터셋 하나가 정상 상태로 준비된 서비스. */
  private RowSearchService searchService() {
    DatasetResponse ds = mock(DatasetResponse.class);
    when(ds.storageType()).thenReturn("TABLE");
    when(ds.tableName()).thenReturn("src");
    when(datasets.findById(ID)).thenReturn(Optional.of(ds));
    when(states.find(ID)).thenReturn(Optional.of(state()));
    when(searchColumns.findConfig(ID))
        .thenReturn(new SearchConfig(List.of(new SearchConfig.Field("content", "내용"))));
    when(columns.findByDatasetId(ID)).thenReturn(List.of());
    when(reader.currentOid("src")).thenReturn(7L);
    return new RowSearchService(
        datasets, columns, states, searchColumns, reader, index, embeddings, features);
  }

  @Test
  void searchReportsUnavailableWithoutCallingIndexWhenStoreIsDown() {
    RowSearchService service = searchService();
    features.available = false;

    var res = service.search(ID, new RowSearchRequest("누수", "KEYWORD", 10, null, null));

    assertThat(res.indexStatus().status()).isEqualTo(RowSearchService.UNAVAILABLE);
    assertThat(res.hits()).isEmpty();
    verify(index, never()).keyword(any(), anyString(), any(), anyInt());
  }

  @Test
  void searchReportsUnavailableWhenStoreFailsMidCall() {
    RowSearchService service = searchService();
    when(index.keyword(any(), anyString(), any(), anyInt())).thenThrow(down());

    var res = service.search(ID, new RowSearchRequest("누수", "KEYWORD", 10, null, null));

    assertThat(res.indexStatus().status()).isEqualTo(RowSearchService.UNAVAILABLE);
    assertThat(res.hits()).isEmpty();
  }
}
