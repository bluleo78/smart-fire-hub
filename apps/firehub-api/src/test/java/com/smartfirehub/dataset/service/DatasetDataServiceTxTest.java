package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데이터셋 화면 SQL 실행의 트랜잭션 구간 검증(WD-207). Tibero 실행은 PG 트랜잭션 밖, 이력 저장은 안. PG 는 기존대로 한 트랜잭션. 실제 트랜잭션
 * 매니저를 쓰고 mock 이 호출 시점의 트랜잭션 여부를 기록한다.
 */
class DatasetDataServiceTxTest extends IntegrationTestBase {

  private static final SqlQueryRequest REQ = new SqlQueryRequest("SELECT 1", 100);

  @Autowired private PlatformTransactionManager transactionManager;

  private record Setup(
      DatasetDataService service,
      GuardedSqlExecutor executor,
      QueryHistoryRepository history,
      List<Boolean> execTx,
      List<Boolean> saveTx) {}

  private Setup setup(boolean tibero) {
    List<Boolean> execTx = new ArrayList<>();
    List<Boolean> saveTx = new ArrayList<>();
    DatasetRepository datasets = mock(DatasetRepository.class);
    when(datasets.findById(anyLong())).thenReturn(Optional.of(mock(DatasetResponse.class)));
    GuardedSqlExecutor executor = mock(GuardedSqlExecutor.class);
    SqlQueryResponse ok = new SqlQueryResponse("SELECT", List.of(), List.of(), 0, 1L, null);
    when(executor.executeDatasetQuery(any(), anyString(), anyInt()))
        .thenAnswer(
            inv -> {
              execTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              return ok;
            });
    QueryHistoryRepository history = mock(QueryHistoryRepository.class);
    doAnswer(
            inv -> {
              saveTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(history)
        .save(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong(), anyBoolean(), any());
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tibero);
    DatasetDataService service =
        new DatasetDataService(
            datasets,
            mock(DatasetColumnRepository.class),
            mock(DataTableService.class),
            mock(DataTableRowService.class),
            executor,
            mock(ClearanceResolver.class),
            history,
            new TransactionTemplate(transactionManager),
            schema,
            // 이 테스트는 SQL 실행 구간만 본다 — 행 변경 감사(WD-463)는 호출되지 않는다.
            mock(DatasetAuditRecorder.class));
    return new Setup(service, executor, history, execTx, saveTx);
  }

  /** Tibero: 실행은 트랜잭션 밖, 이력 저장은 트랜잭션 안. */
  @Test
  void tibero_executesOutsideTx_historyInsideTx() {
    Setup s = setup(true);

    s.service().executeQuery(1L, REQ, 7L);

    assertThat(s.execTx()).containsExactly(false);
    assertThat(s.saveTx()).containsExactly(true);
  }

  /** PG: 실행과 이력 저장이 같은 트랜잭션 안(기존 동작). */
  @Test
  void pg_executesAndSavesInSameTx() {
    Setup s = setup(false);

    s.service().executeQuery(1L, REQ, 7L);

    assertThat(s.execTx()).containsExactly(true);
    assertThat(s.saveTx()).containsExactly(true);
  }

  /** Tibero 실행이 예외(400/403)면 기록 없이 그대로 올라간다. */
  @Test
  void tibero_executionThrows_noHistory() {
    Setup s = setup(true);
    when(s.executor().executeDatasetQuery(any(), anyString(), anyInt()))
        .thenThrow(new SqlQueryException("거부"));

    assertThatThrownBy(() -> s.service().executeQuery(1L, REQ, 7L))
        .isInstanceOf(SqlQueryException.class);

    verify(s.history(), never())
        .save(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong(), anyBoolean(), any());
  }
}
