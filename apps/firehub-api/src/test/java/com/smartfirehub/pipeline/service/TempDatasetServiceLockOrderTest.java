package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.smartfirehub.dataset.service.DatasetDeleteLock;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.FakeTransaction;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * WD-666 — 임시 데이터셋 일괄 삭제의 잠금 순서. 잠금 순서 규칙은 「데이터셋 행 → 테넌트 동기화 → 테이블」이다. 출력 맞바꿈(데이터셋 행 KEY SHARE →
 * 동기화 잠금)과 거꾸로 잡으면 교착하므로, 지울 행 전부를 id 오름차순으로 먼저 잠근 뒤에 동기화 잠금을 잡아야 한다.
 *
 * <p>WD-672 — 행 잠금은 공용 층(WD-675 부터 삭제 잠금 증표 {@link DatasetDeleteLock})을 거친다. 트랜잭션 밖에서 부르면 잠금이 문장
 * 끝에서 바로 풀려 아무것도 막지 못하므로 거부해야 하고, 잠그려는 사이 이미 지워진 행은 건너뛴다(그 id 로 deleteDataset 을 부르면 404 가 호출자 트랜잭션을
 * 롤백 전용으로 만든다).
 *
 * <p>WD-675 — 행 잠금 결과는 증표로 삭제 본문({@code deleteLockedDatasets})에 넘어간다. 동기화 잠금은 삭제 본문 몫이라(규칙은 {@link
 * DatasetDeleteLock}) 여기(DatasetService 목)에서는 잡히지 않는다 — 실제 순서는
 * TempDatasetServiceDeleteLocksOnceTest 가 본다.
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
          mock(PipelineDataSchema.class));

  @AfterEach
  void clearTransactionFlag() {
    FakeTransaction.end();
  }

  @Test
  void 지울_데이터셋_행을_id_순으로_먼저_잠근_뒤_증표로_지운다() {
    FakeTransaction.begin();
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);

    service.deleteTempDatasets(List.of(30L, 10L, 20L));

    InOrder order = inOrder(datasetRepository, datasetService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(datasetRepository).lockForDelete(30L);
    // WD-674: 행 잠금을 쥐었다고 가정하는 삭제 본문을 부른다 — 잠금을 다시 잡는 deleteDataset 은 쓰지 않는다.
    // WD-675: 행을 잠근 증표를 함께 넘긴다 — 증표에는 실제로 잠근 id 만 들어 있다.
    ArgumentCaptor<DatasetDeleteLock> lock = ArgumentCaptor.forClass(DatasetDeleteLock.class);
    order.verify(datasetService).deleteLockedDatasets(lock.capture());
    assertThat(lock.getValue().lockedIds()).containsExactly(10L, 20L, 30L);
    verify(datasetService, never()).deleteDataset(anyLong());
    // WD-675: 동기화 잠금은 TempDatasetService 가 직접 잡지 않는다 — 삭제 본문이 저장 형태를 보고
    // 잡는다(TempDatasetServiceDeleteLocksOnceTest)
    verify(dataTableService, never()).lockTenantGrantSyncForDdl();
  }

  @Test
  void 트랜잭션_밖에서_부르면_아무것도_잠그거나_지우지_않고_거부한다() {
    assertThatThrownBy(() -> service.deleteTempDatasets(List.of(10L, 20L)))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(datasetRepository, dataTableService, datasetService);
  }

  @Test
  void 잠그려는_사이_이미_지워진_행은_건너뛴다() {
    FakeTransaction.begin();
    when(datasetRepository.lockForDelete(10L)).thenReturn(true);
    when(datasetRepository.lockForDelete(20L)).thenReturn(false);

    service.deleteTempDatasets(List.of(20L, 10L));

    ArgumentCaptor<DatasetDeleteLock> lock = ArgumentCaptor.forClass(DatasetDeleteLock.class);
    verify(datasetService).deleteLockedDatasets(lock.capture());
    assertThat(lock.getValue().lockedIds()).containsExactly(10L);
  }

  @Test
  void 잠근_행이_하나도_없으면_삭제_본문을_부르지_않는다() {
    FakeTransaction.begin();
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(false);

    service.deleteTempDatasets(List.of(20L, 10L));

    verify(datasetService, never()).deleteLockedDatasets(any(DatasetDeleteLock.class));
  }
}
