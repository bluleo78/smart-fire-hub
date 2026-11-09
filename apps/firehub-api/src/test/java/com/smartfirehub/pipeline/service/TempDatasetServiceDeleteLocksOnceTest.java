package com.smartfirehub.pipeline.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetReferenceRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.rowsearch.RowSearchIndex;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * WD-674 — 임시 데이터셋 일괄 삭제는 데이터셋 행 잠금(FOR UPDATE)과 테넌트 동기화 잠금을 <b>한 번씩만</b> 잡는다. 예전에는 일괄 삭제가 행
 * 전부·동기화를 잡은 뒤 데이터셋마다 {@link DatasetService#deleteDataset} 을 불러, 같은 행 잠금과 동기화 잠금을 데이터셋마다 다시 잡았다(같은
 * 트랜잭션이라 막히지는 않지만 쓸데없는 왕복이고, 순서 규칙을 두 곳이 따로 지켜야 했다).
 *
 * <p>목 대신 실제 {@link DatasetService}(의존성은 목)를 꽂아, 일괄 삭제 → 데이터셋 삭제 본문까지 실제로 돌며 무엇을 몇 번 잠그는지와 테이블을 정말
 * 지우는지(삭제가 건너뛰어지면 실패) 본다. 순서 「데이터셋 행 → 동기화 → 테이블」도 함께 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TempDatasetServiceDeleteLocksOnceTest {

  private static final long TENANT_ID = 1L;

  @Mock private DatasetRepository datasetRepository;
  @Mock private DatasetColumnRepository columnRepository;
  @Mock private DataTableService dataTableService;
  @Mock private DatasetReferenceRepository datasetReferenceRepository;
  @Mock private RowSearchIndex rowSearchIndex;
  @Mock private TriggerRepository triggerRepository;

  @InjectMocks private DatasetService datasetService;

  private TempDatasetService tempDatasetService;

  @BeforeEach
  void setUp() {
    TenantContext.set(TENANT_ID);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    tempDatasetService =
        new TempDatasetService(
            datasetRepository,
            datasetService,
            columnRepository,
            dataTableService,
            mock(PipelineDataSchema.class),
            new DatasetDataWriteLock(datasetRepository, mock(PlatformTransactionManager.class)));
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    when(datasetRepository.findById(anyLong()))
        .thenAnswer(inv -> Optional.of(tempDataset(inv.getArgument(0))));
    when(datasetRepository.deleteById(anyLong())).thenReturn(1);
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TenantContext.clear();
  }

  @Test
  void 일괄_삭제는_행_잠금과_동기화_잠금을_한_번씩만_잡고_모든_테이블을_지운다() {
    tempDatasetService.deleteTempDatasets(List.of(20L, 10L));

    verify(datasetRepository, times(1)).lockForDelete(10L);
    verify(datasetRepository, times(1)).lockForDelete(20L);
    verify(dataTableService, times(1)).lockTenantGrantSyncForDdl();
    // 삭제가 실제로 일어난다 — 잠금 층이 지울 id 를 빠뜨리면 여기서 실패한다
    verify(dataTableService).dropTable(table(10L));
    verify(dataTableService).dropTable(table(20L));
    verify(datasetRepository).deleteById(10L);
    verify(datasetRepository).deleteById(20L);
    verify(rowSearchIndex, times(2)).drop(any());

    InOrder order = inOrder(datasetRepository, dataTableService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(dataTableService).dropTable(table(10L));
    order.verify(dataTableService).dropTable(table(20L));
  }

  @Test
  void 단건_삭제는_예전처럼_행_잠금_뒤에_동기화_잠금을_잡는다() {
    datasetService.deleteDataset(10L);

    InOrder order = inOrder(datasetRepository, dataTableService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(dataTableService).dropTable(table(10L));
    verify(datasetRepository).deleteById(10L);
  }

  private static String table(long id) {
    return "ptmp_" + id;
  }

  /** 러너가 만든 TEMP 데이터셋(테이블 저장형) 한 건. */
  private static DatasetResponse tempDataset(long id) {
    return new DatasetResponse(
        id,
        "tmp" + id,
        table(id),
        null,
        null,
        "TABLE",
        "TEMP",
        null,
        false,
        List.of(),
        "NONE",
        null,
        null,
        null,
        100L);
  }
}
