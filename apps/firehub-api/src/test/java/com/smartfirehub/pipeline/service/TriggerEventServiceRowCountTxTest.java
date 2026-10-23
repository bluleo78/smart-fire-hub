package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.repository.TriggerEventRepository;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DATASET_CHANGE 폴러의 행 수 세기 트랜잭션 구간 검증(WD-80). Tibero 는 네트워크 너머의 {@code COUNT(*)} 라 PG 트랜잭션(=커넥션)을
 * 쥔 채 세면 안 되고, 테이블 이름 조회(RLS 가 걸린 dataset)는 트랜잭션 안이어야 한다. PG 는 기존처럼 둘 다 한 트랜잭션 안이다.
 *
 * <p>클래스에 @Transactional 을 붙이지 않고 실제 트랜잭션 매니저로 템플릿을 만들어, mock 이 호출 시점의 트랜잭션 여부를 기록한다.
 */
class TriggerEventServiceRowCountTxTest extends IntegrationTestBase {

  @Autowired private PlatformTransactionManager transactionManager;

  /** 호출 시점의 트랜잭션 활성 여부 기록. */
  private record Recorded(List<Boolean> nameReadTx, List<Boolean> countTx) {}

  @SuppressWarnings("unchecked")
  private Recorded runOnce(DataStoreBackend backend) {
    List<Boolean> nameReadTx = new ArrayList<>();
    List<Boolean> countTx = new ArrayList<>();

    DatasetRepository datasetRepository = mock(DatasetRepository.class);
    when(datasetRepository.findTableNameById(anyLong()))
        .thenAnswer(
            inv -> {
              nameReadTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of("t1");
            });
    DataRowStore rowStore = mock(DataRowStore.class);
    when(rowStore.rowCounts(any()))
        .thenAnswer(
            inv -> {
              countTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              return Map.of("t1", 3L);
            });
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.currentBackend()).thenReturn(backend);
    when(resolver.rowStore()).thenReturn(rowStore);

    TriggerRepository triggerRepository = mock(TriggerRepository.class);
    when(triggerRepository.findEnabledByType("DATASET_CHANGE"))
        .thenReturn(
            List.of(
                new TriggerResponse(
                    10L,
                    20L,
                    "DATASET_CHANGE",
                    "변경 감지",
                    null,
                    true,
                    Map.of("datasetIds", List.of(1), "pollingIntervalSeconds", 0),
                    Map.of(),
                    null,
                    1L,
                    LocalDateTime.now())));
    TenantScopedRunner runner = mock(TenantScopedRunner.class);
    doAnswer(
            inv -> {
              ((Consumer<Long>) inv.getArgument(0)).accept(DEFAULT_TEST_TENANT_ID);
              return null;
            })
        .when(runner)
        .forEachActiveTenant(any());

    TriggerEventService service =
        new TriggerEventService(
            triggerRepository,
            mock(TriggerEventRepository.class),
            mock(TriggerService.class),
            datasetRepository,
            mock(NotificationService.class),
            runner,
            new TransactionTemplate(transactionManager),
            resolver,
            new PipelineDataSchema(resolver, mock(SqlValidator.class)));
    service.pollDatasetChanges();
    return new Recorded(nameReadTx, countTx);
  }

  /** Tibero: 테이블 이름은 트랜잭션 안에서 읽고, 행 수는 트랜잭션 밖에서 센다. */
  @Test
  void tibero_countsOutsideTx() {
    Recorded r = runOnce(DataStoreBackend.TIBERO);

    assertThat(r.nameReadTx()).containsExactly(true);
    assertThat(r.countTx()).containsExactly(false);
  }

  /** PG: 기존처럼 이름 조회와 행 수 세기가 한 트랜잭션 안이다. */
  @Test
  void pg_countsInsideTx() {
    Recorded r = runOnce(DataStoreBackend.POSTGRESQL);

    assertThat(r.nameReadTx()).containsExactly(true);
    assertThat(r.countTx()).containsExactly(true);
  }
}
