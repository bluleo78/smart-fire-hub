package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.pipeline.service.IncrementalCursorService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 동시 실행 레이스 회귀: 상태는 반드시 임대를 잡은 <b>뒤</b> 읽어야 한다.
 *
 * <p>임대 전에 읽은 스냅샷은 다른 워커가 그 사이 패스를 끝냈을 때 낡은 resume_after_id 로 이어 처리하게 만들어, 새
 * 책갈피로 확정하면서 id≤resume 구간의 변경을 놓친다. 실 DB 로는 두 워커의 타이밍을 끼워 넣기 어려워 협력자를 mock 으로
 * 두고 호출 순서와 "임대 후 읽은 상태를 쓰는지"를 단언한다.
 */
@ExtendWith(MockitoExtension.class)
class RowSearchSyncServiceLeaseOrderTest {

  private static final long ID = 42L;

  @Mock private RowSearchIndex index;
  @Mock private SearchIndexStateRepository states;
  @Mock private SearchColumnRepository searchColumns;
  @Mock private SearchSourceReader reader;
  @Mock private DatasetRepository datasetRepository;
  @Mock private EmbeddingProviderFactory embeddingFactory;
  @Mock private IncrementalCursorService cursorService;

  private RowSearchSyncService sync;

  @BeforeEach
  void setUp() {
    sync =
        new RowSearchSyncService(
            index, states, searchColumns, reader, datasetRepository, embeddingFactory, cursorService, 4, 2);
  }

  private static SearchIndexState state(int consecutiveFailures) {
    return new SearchIndexState(
        ID, "ERROR", "h", "m", 1024, 1L, null, null, null, 0, 0, consecutiveFailures, null, null);
  }

  @Test
  void leaseNotAcquired_stateIsNeverRead() {
    when(states.tryAcquireLease(eq(ID), any(Duration.class))).thenReturn(false);

    assertThat(sync.sync(ID)).isEqualTo(RowSearchSyncService.Outcome.SKIPPED);

    verify(states, never()).find(anyLong());
    verify(states, never()).releaseLease(anyLong()); // 잡지 않은 임대는 풀지 않는다
  }

  @Test
  void stateIsReadAfterLease_andItsFailureCountDrivesBackoff() {
    when(states.tryAcquireLease(eq(ID), any(Duration.class))).thenReturn(true);
    when(states.find(ID)).thenReturn(Optional.of(state(3)));
    when(datasetRepository.findById(ID)).thenThrow(new RuntimeException()); // 메시지 없는 예외

    OffsetDateTime before = OffsetDateTime.now();
    assertThat(sync.sync(ID)).isEqualTo(RowSearchSyncService.Outcome.FAILED);

    InOrder order = inOrder(states, datasetRepository);
    order.verify(states).tryAcquireLease(eq(ID), any(Duration.class));
    order.verify(states).find(ID);
    order.verify(datasetRepository).findById(ID);
    ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
    // 메시지가 null 이면 예외 클래스 이름을 남긴다
    order.verify(states).markFailed(eq(ID), eq("java.lang.RuntimeException"), next.capture());
    order.verify(states).releaseLease(ID);
    // 임대 후 읽은 상태의 연속 실패 3회 → 이번이 4회째 → 8분 백오프
    assertThat(next.getValue()).isBetween(before.plusMinutes(8), OffsetDateTime.now().plusMinutes(8));
  }

  @Test
  void stateGoneAfterLease_skipsAndReleases() {
    when(states.tryAcquireLease(eq(ID), any(Duration.class))).thenReturn(true);
    when(states.find(ID)).thenReturn(Optional.empty());

    assertThat(sync.sync(ID)).isEqualTo(RowSearchSyncService.Outcome.SKIPPED);

    verify(states).releaseLease(ID);
    verifyNoInteractions(datasetRepository, index, reader, embeddingFactory, cursorService);
  }
}
