package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 저장 쿼리 실행의 트랜잭션 구간 검증(WD-207). Tibero 실행(최대 35초)은 PG 트랜잭션 밖에서, RLS 가 걸린 저장 쿼리 조회는 트랜잭션 안에서 해야 한다.
 * 클래스에 @Transactional 을 붙이지 않고 실제 트랜잭션 매니저를 써서, mock 이 호출 시점의 트랜잭션 여부를 기록한다.
 */
class SavedQueryServiceTxTest extends IntegrationTestBase {

  @Autowired private PlatformTransactionManager transactionManager;

  private record Setup(SavedQueryService service, List<Boolean> readTx, List<Boolean> execTx) {}

  private Setup setup(boolean tibero) {
    List<Boolean> readTx = new ArrayList<>();
    List<Boolean> execTx = new ArrayList<>();
    SavedQueryRepository repo = mock(SavedQueryRepository.class);
    SavedQueryResponse saved =
        new SavedQueryResponse(
            1L,
            "q",
            null,
            "SELECT 1",
            null,
            null,
            null,
            false,
            "u",
            7L,
            LocalDateTime.now(),
            LocalDateTime.now(),
            0L);
    when(repo.findById(anyLong(), anyLong(), any()))
        .thenAnswer(
            inv -> {
              readTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of(saved);
            });
    Clearance clearance = mock(Clearance.class);
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.resolve(anyLong())).thenReturn(clearance);
    AnalyticsQueryResponse result =
        new AnalyticsQueryResponse("SELECT", List.of(), List.of(), 0, 0L, 0, false, null);
    GuardedSqlExecutor executor = mock(GuardedSqlExecutor.class);
    when(executor.executeAnalytics(any(), anyString(), anyInt(), anyBoolean()))
        .thenAnswer(
            inv -> {
              execTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              return result;
            });
    QueryResultExportService export = mock(QueryResultExportService.class);
    when(export.attachRun(any(), anyString(), anyInt(), any())).thenReturn(result);
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tibero);
    SavedQueryService service =
        new SavedQueryService(
            repo,
            executor,
            resolver,
            mock(DatasetAccessGuard.class),
            export,
            mock(ChartRepository.class),
            mock(AnalyticsDashboardRepository.class),
            new TransactionTemplate(transactionManager),
            schema,
            TransactionTemplateConfig.readOnly(transactionManager),
            mock(AnalyticsAuditRecorder.class));
    return new Setup(service, readTx, execTx);
  }

  /** Tibero: 저장 쿼리 조회는 트랜잭션 안, 실행은 트랜잭션 밖. */
  @Test
  void tibero_executesOutsideTx_readsInsideTx() {
    Setup s = setup(true);

    s.service().executeById(1L, 100, true, 7L);

    assertThat(s.readTx()).containsExactly(true);
    assertThat(s.execTx()).containsExactly(false);
  }

  /** PG: 조회와 실행이 기존대로 한 트랜잭션 안. */
  @Test
  void pg_executesInsideTx() {
    Setup s = setup(false);

    s.service().executeById(1L, 100, true, 7L);

    assertThat(s.readTx()).containsExactly(true);
    assertThat(s.execTx()).containsExactly(true);
  }
}
