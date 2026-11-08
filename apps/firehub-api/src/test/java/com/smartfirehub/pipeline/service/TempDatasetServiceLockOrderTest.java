package com.smartfirehub.pipeline.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * WD-666 — 임시 데이터셋 일괄 삭제의 잠금 순서. 잠금 순서 규칙은 「데이터셋 행 → 테넌트 동기화 → 테이블」이다. 출력 맞바꿈(데이터셋 행 KEY SHARE →
 * 동기화 잠금)과 거꾸로 잡으면 교착하므로, 지울 행 전부를 id 오름차순으로 먼저 잠근 뒤에 동기화 잠금을 잡아야 한다.
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

  @Test
  void 지울_데이터셋_행을_id_순으로_먼저_잠근_뒤_동기화_잠금을_잡는다() {
    service.deleteTempDatasets(List.of(30L, 10L, 20L));

    InOrder order = inOrder(datasetRepository, dataTableService, datasetService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(datasetRepository).lockForDelete(30L);
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(datasetService).deleteDataset(30L);
    order.verify(datasetService).deleteDataset(10L);
    order.verify(datasetService).deleteDataset(20L);
  }
}
