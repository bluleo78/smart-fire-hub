package com.smartfirehub.pipeline.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.smartfirehub.dataset.service.DatasetDeleteLock;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.support.FakeTransaction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * WD-674 — 임시 데이터셋 일괄 삭제는 데이터셋 행 잠금(FOR UPDATE)과 테넌트 동기화 잠금을 <b>한 번씩만</b> 잡는다. 예전에는 일괄 삭제가 행
 * 전부·동기화를 잡은 뒤 데이터셋마다 {@link DatasetService#deleteDataset} 을 불러, 같은 행 잠금과 동기화 잠금을 데이터셋마다 다시 잡았다(같은
 * 트랜잭션이라 막히지는 않지만 쓸데없는 왕복이고, 순서 규칙을 두 곳이 따로 지켜야 했다).
 *
 * <p>목 대신 실제 {@link DatasetService}(의존성은 목)를 꽂아, 일괄 삭제 → 데이터셋 삭제 본문까지 실제로 돌며 무엇을 몇 번 잠그는지와 테이블을 정말
 * 지우는지(삭제가 건너뛰어지면 실패) 본다. 순서 「데이터셋 행 → 동기화 → 테이블」도 함께 확인한다.
 *
 * <p>WD-675 — 동기화 잠금 시점(일괄은 테이블 있는 데이터셋이 있을 때만 첫 삭제 전, 단건은 DROP 직전)이 {@link DatasetDeleteLock} 문서의
 * 규칙대로인지 본다.
 *
 * <p>WD-670 — 구조 잠금({@link DatasetDataWriteLock#lockStructure})은 테이블마다 한 번, 데이터셋 행 잠금 뒤·동기화 잠금 앞에서
 * 잡는다(일괄 삭제는 동기화 잠금을 첫 삭제 전에 잡으므로 구조 잠금도 모두 그 앞에서).
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

  /** 구조 잠금(WD-670) 호출 순서를 보려는 목 — 실제 잠금은 하지 않는다. */
  @Mock private DatasetDataWriteLock writeLock;

  @InjectMocks private DatasetService datasetService;

  private TempDatasetService tempDatasetService;

  /** 데이터셋 id → 저장 형태(기본 TABLE). 테이블 없는 DOCUMENT/FILE 을 섞어 동기화 잠금 생략을 본다(WD-675). */
  private final Map<Long, String> storageTypes = new HashMap<>();

  @BeforeEach
  void setUp() {
    TenantContext.set(TENANT_ID);
    FakeTransaction.begin();
    tempDatasetService =
        new TempDatasetService(
            datasetRepository,
            datasetService,
            columnRepository,
            dataTableService,
            mock(PipelineDataSchema.class));
    when(datasetRepository.lockForDelete(anyLong())).thenReturn(true);
    when(datasetRepository.findById(anyLong()))
        .thenAnswer(
            inv -> {
              long id = inv.getArgument(0);
              return Optional.of(tempDataset(id, storageTypes.getOrDefault(id, "TABLE")));
            });
    when(datasetRepository.deleteById(anyLong())).thenReturn(1);
  }

  @AfterEach
  void tearDown() {
    FakeTransaction.end();
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

    // WD-670: 행 → 구조(테이블마다 한 번, id 순) → 동기화 → 테이블. 본문이 구조 잠금을 다시 잡지 않는다.
    verify(writeLock, times(1)).lockStructure(table(10L));
    verify(writeLock, times(1)).lockStructure(table(20L));
    InOrder order = inOrder(datasetRepository, writeLock, dataTableService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(writeLock).lockStructure(table(10L));
    order.verify(writeLock).lockStructure(table(20L));
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(dataTableService).dropTable(table(10L));
    order.verify(dataTableService).dropTable(table(20L));
  }

  @Test
  void 단건_삭제는_예전처럼_행_잠금_뒤에_동기화_잠금을_잡는다() {
    datasetService.deleteDataset(10L);

    // WD-670: 단건도 행 → 구조 → 동기화 → 테이블, 구조 잠금은 한 번
    verify(writeLock, times(1)).lockStructure(table(10L));
    InOrder order = inOrder(datasetRepository, writeLock, dataTableService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(writeLock).lockStructure(table(10L));
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(dataTableService).dropTable(table(10L));
    verify(datasetRepository).deleteById(10L);
  }

  @Test
  void 문서_파일_데이터셋만_지우면_동기화_잠금을_잡지_않는다() {
    // WD-675: 테넌트 동기화 잠금은 기존 테이블 DROP 앞에서만 필요하다 — 테이블 없는 데이터셋만 지우면 잡지 않아 동기화를 기다리지 않는다
    storageTypes.put(10L, "DOCUMENT");
    storageTypes.put(20L, "FILE");

    tempDatasetService.deleteTempDatasets(List.of(20L, 10L));

    verify(dataTableService, never()).lockTenantGrantSyncForDdl();
    verify(dataTableService, never()).dropTable(anyString());
    // WD-670: 테이블이 없으면 구조 잠금도 잡지 않는다
    verify(writeLock, never()).lockStructure(any());
    verify(datasetRepository).deleteById(10L);
    verify(datasetRepository).deleteById(20L);
  }

  @Test
  void 테이블_있는_데이터셋이_섞이면_행_잠금_직후_첫_삭제_전에_동기화_잠금을_한_번_잡는다() {
    // WD-675: 혼합 목록 — 테이블 있는 데이터셋이 하나라도 있으면 예전처럼 행 잠금 직후·어떤 삭제보다 먼저 잡는다(「행 → 동기화 → 테이블」)
    storageTypes.put(10L, "DOCUMENT");
    storageTypes.put(30L, "FILE");

    tempDatasetService.deleteTempDatasets(List.of(40L, 30L, 20L, 10L));

    verify(dataTableService, times(1)).lockTenantGrantSyncForDdl();
    verify(dataTableService, times(2)).dropTable(anyString());
    InOrder order = inOrder(datasetRepository, writeLock, dataTableService);
    order.verify(datasetRepository).lockForDelete(10L);
    order.verify(datasetRepository).lockForDelete(20L);
    order.verify(datasetRepository).lockForDelete(30L);
    order.verify(datasetRepository).lockForDelete(40L);
    // WD-670: 테이블 있는 데이터셋(20·40)만 구조 잠금 — 동기화 잠금 앞
    order.verify(writeLock).lockStructure(table(20L));
    order.verify(writeLock).lockStructure(table(40L));
    order.verify(dataTableService).lockTenantGrantSyncForDdl();
    order.verify(datasetRepository).deleteById(10L);
    order.verify(dataTableService).dropTable(table(20L));
    order.verify(datasetRepository).deleteById(30L);
    order.verify(dataTableService).dropTable(table(40L));
  }

  @Test
  void 단건_삭제도_문서_데이터셋이면_동기화_잠금을_잡지_않는다() {
    // WD-675: 단건 삭제와 일괄 삭제가 같은 증표 규칙을 쓴다
    storageTypes.put(10L, "DOCUMENT");

    datasetService.deleteDataset(10L);

    verify(dataTableService, never()).lockTenantGrantSyncForDdl();
    verify(datasetRepository).deleteById(10L);
  }

  @Test
  void 같은_id_가_두_번_와도_한_번만_지운다() {
    // WD-680: 증표가 중복 id 를 걸러 내지 않으면 같은 데이터셋의 DROP·행 삭제가 두 번 돈다(두 번째는 404 로 트랜잭션 전체 롤백)
    tempDatasetService.deleteTempDatasets(List.of(10L, 10L));

    verify(datasetRepository, times(1)).lockForDelete(10L);
    verify(dataTableService, times(1)).dropTable(table(10L));
    verify(datasetRepository, times(1)).deleteById(10L);
  }

  private static String table(long id) {
    return "ptmp_" + id;
  }

  /** 러너가 만든 TEMP 데이터셋 한 건 — 저장 형태(TABLE·DOCUMENT·FILE)를 고른다. */
  private static DatasetResponse tempDataset(long id, String storageType) {
    return new DatasetResponse(
        id,
        "tmp" + id,
        table(id),
        null,
        null,
        storageType,
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
