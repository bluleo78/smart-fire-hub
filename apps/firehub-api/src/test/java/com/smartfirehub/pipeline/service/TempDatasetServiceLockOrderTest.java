package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * WD-666 — 임시 데이터셋 일괄 삭제의 잠금 순서. 잠금 순서 규칙은 「데이터셋 행 → 테넌트 동기화 → 테이블」이다. 출력 맞바꿈(데이터셋 행 KEY SHARE →
 * 동기화 잠금)과 거꾸로 잡으면 교착하므로, 지울 행 전부를 id 오름차순으로 먼저 잠근 뒤에 동기화 잠금을 잡아야 한다.
 *
 * <p>WD-672 — 행 잠금은 공용 층({@link DatasetDataWriteLock})을 거친다. 트랜잭션 밖에서 부르면 잠금이 문장 끝에서 바로 풀려 아무것도 막지
 * 못하므로 거부해야 하고, 잠그려는 사이 이미 지워진 행은 건너뛴다(그 id 로 deleteDataset 을 부르면 404 가 호출자 트랜잭션을 롤백 전용으로 만든다).
 */
class TempDatasetServiceLockOrderTest {

  private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
  private final DatasetService datasetService = mock(DatasetService.class);
  private final DataTableService dataTableService = mock(DataTableService.class);
  private final TempDatasetService service =
      new TempDatasetService(
          datasetRepository,
          datasetService,
          mock(DatasetColumnRepository.class),
          dataTableService,
          mock(PipelineDataSchema.class),
          new DatasetDataWriteLock(datasetRepository, mock(PlatformTransactionManager.class)));

  /** 트랜잭션 안에서 부르는 경우를 흉내 낸다 — 공용 층의 트랜잭션 확인은 이 표시만 본다. */
  private static void simulateActiveTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void clearTransactionFlag() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void 지울_데이터셋_행을_id_순으로_먼저_잠근_뒤_동기화_잠금을_잡는다() {
    simulateActiveTransaction();
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);

    service.deleteTempDatasets(List.of(30L, 10L, 20L));

    InOrder order = inOrder(datasetRepository, dataTableService, datasetService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(datasetRepository).lockForDelete(30L);
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(datasetService).deleteDataset(10L);
    order.verify(datasetService).deleteDataset(20L);
    order.verify(datasetService).deleteDataset(30L);
  }

  @Test
  void 트랜잭션_밖에서_부르면_아무것도_잠그거나_지우지_않고_거부한다() {
    assertThatThrownBy(() -> service.deleteTempDatasets(List.of(10L, 20L)))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(datasetRepository, dataTableService, datasetService);
  }

  @Test
  void 잠그려는_사이_이미_지워진_행은_건너뛴다() {
    simulateActiveTransaction();
    when(datasetRepository.lockForDelete(10L)).thenReturn(true);
    when(datasetRepository.lockForDelete(20L)).thenReturn(false);

    service.deleteTempDatasets(List.of(20L, 10L));

    verify(datasetService).deleteDataset(10L);
    verify(datasetService, never()).deleteDataset(20L);
  }
}
