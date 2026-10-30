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
import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 차트 데이터·대시보드 데이터의 트랜잭션 구간 검증(WD-207). Tibero 실행(최대 35초)은 PG 트랜잭션 밖에서, RLS 가 걸린 조회는 readOnly 트랜잭션
 * 안에서 해야 한다. 클래스에 @Transactional 을 붙이지 않고 실제 트랜잭션 매니저를 써서, mock 이 호출 시점의 트랜잭션 여부를 기록한다.
 */
class ChartDashboardTxTest extends IntegrationTestBase {

  @Autowired private PlatformTransactionManager transactionManager;

  private static final AnalyticsQueryResponse RESULT =
      new AnalyticsQueryResponse("SELECT", List.of(), List.of(), 0, 0L, 0, false, null);

  private static ChartResponse chart(long id, long savedQueryId) {
    return new ChartResponse(
        id,
        "c" + id,
        null,
        savedQueryId,
        "q",
        "BAR",
        Map.of(),
        false,
        "u",
        7L,
        LocalDateTime.now(),
        LocalDateTime.now(),
        0L,
        false);
  }

  /** 호출 시점의 (트랜잭션 활성, readOnly) 를 기록한다. readOnly 가 null 이면 활성 여부만 기록. */
  private static void record(List<Boolean> active, List<Boolean> readOnly) {
    active.add(TransactionSynchronizationManager.isActualTransactionActive());
    if (readOnly != null) {
      readOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }
  }

  private static GuardedSqlExecutor.AnalyticsJudgment judgment(boolean denied) {
    GuardedSqlExecutor.AnalyticsJudgment j = mock(GuardedSqlExecutor.AnalyticsJudgment.class);
    when(j.denied()).thenReturn(denied);
    return j;
  }

  private static PipelineDataSchema schema(boolean tibero) {
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tibero);
    return schema;
  }

  // ---------------------------------------------------------------- 차트

  private record ChartSetup(
      ChartService service, List<Boolean> readTx, List<Boolean> readOnly, List<Boolean> execTx) {}

  private ChartSetup chartSetup(boolean tibero) {
    List<Boolean> readTx = new ArrayList<>();
    List<Boolean> readOnly = new ArrayList<>();
    List<Boolean> execTx = new ArrayList<>();
    ChartRepository repo = mock(ChartRepository.class);
    when(repo.findById(anyLong(), anyLong()))
        .thenAnswer(
            inv -> {
              record(readTx, readOnly);
              return Optional.of(chart(1L, 300L));
            });
    when(repo.findSavedQuerySqlText(anyLong(), anyLong()))
        .thenAnswer(
            inv -> {
              record(readTx, readOnly);
              return Optional.of("SELECT 1");
            });
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.resolve(anyLong())).thenReturn(mock(Clearance.class));
    GuardedSqlExecutor executor = mock(GuardedSqlExecutor.class);
    GuardedSqlExecutor.AnalyticsJudgment allowed = judgment(false);
    when(executor.judgeAnalytics(any(), anyString())).thenReturn(allowed);
    when(executor.executeJudgedAnalytics(any(), anyInt(), anyBoolean()))
        .thenAnswer(
            inv -> {
              record(execTx, null);
              return RESULT;
            });
    ChartService service =
        new ChartService(
            repo,
            mock(SavedQueryRepository.class),
            executor,
            resolver,
            mock(AnalyticsDashboardRepository.class),
            schema(tibero),
            TransactionTemplateConfig.readOnly(transactionManager),
            mock(AnalyticsAuditRecorder.class));
    return new ChartSetup(service, readTx, readOnly, execTx);
  }

  /** Tibero: 차트·SQL 조회는 readOnly 트랜잭션 안, 실행은 트랜잭션 밖. */
  @Test
  void chart_tibero_executesOutsideTx() {
    ChartSetup s = chartSetup(true);

    ChartDataResponse r = s.service().getChartData(1L, 7L);

    assertThat(r.queryResult()).isEqualTo(RESULT);
    assertThat(s.readTx()).containsExactly(true, true);
    assertThat(s.readOnly()).containsExactly(true, true);
    assertThat(s.execTx()).containsExactly(false);
  }

  /** PG: 조회와 실행이 기존대로 readOnly 트랜잭션 하나 안. */
  @Test
  void chart_pg_executesInsideTx() {
    ChartSetup s = chartSetup(false);

    s.service().getChartData(1L, 7L);

    assertThat(s.readTx()).containsExactly(true, true);
    assertThat(s.readOnly()).containsExactly(true, true);
    assertThat(s.execTx()).containsExactly(true);
  }

  // ------------------------------------------------------------- 대시보드

  private record DashSetup(
      AnalyticsDashboardService service,
      List<Boolean> metaTx,
      List<Boolean> metaReadOnly,
      List<Boolean> execTx) {}

  /** 위젯 2개(차트 100→저장 쿼리 300, 차트 101→저장 쿼리 301). deniedQuery 가 null 이 아니면 그 저장 쿼리는 거부 판정. */
  private DashSetup dashSetup(boolean tibero, Long deniedQuery) {
    List<Boolean> metaTx = new ArrayList<>();
    List<Boolean> metaReadOnly = new ArrayList<>();
    List<Boolean> execTx = new ArrayList<>();
    var w1 = new DashboardResponse.DashboardWidgetResponse(10L, 100L, "c100", "BAR", 0, 0, 6, 4, true);
    var w2 = new DashboardResponse.DashboardWidgetResponse(11L, 101L, "c101", "BAR", 6, 0, 6, 4, true);
    DashboardResponse dash =
        new DashboardResponse(
            1L,
            "d",
            null,
            false,
            null,
            List.of(w1, w2),
            2,
            "u",
            7L,
            LocalDateTime.now(),
            LocalDateTime.now());

    DashboardWidgetRepository widgetRepo = mock(DashboardWidgetRepository.class);
    when(widgetRepo.findByDashboardId(anyLong()))
        .thenAnswer(
            inv -> {
              record(metaTx, metaReadOnly);
              return List.of(w1, w2);
            });
    AnalyticsDashboardRepository dashRepo = mock(AnalyticsDashboardRepository.class);
    when(dashRepo.findById(anyLong(), anyLong(), any()))
        .thenAnswer(
            inv -> {
              record(metaTx, metaReadOnly);
              return Optional.of(dash);
            });
    ChartRepository chartRepo = mock(ChartRepository.class);
    // WD-195: 위젯 목록은 조회자 기준 차트 가시성을 판정한다(RLS — 메타데이터 읽기 구간). 두 차트 모두 보인다.
    when(chartRepo.findVisibleIds(any(), anyLong()))
        .thenAnswer(
            inv -> {
              record(metaTx, metaReadOnly);
              return java.util.Set.of(100L, 101L);
            });
    when(chartRepo.findSavedQueryId(anyLong()))
        .thenAnswer(
            inv -> {
              record(metaTx, metaReadOnly);
              return (Long) inv.getArgument(0) + 200L;
            });
    when(chartRepo.findSavedQuerySqlTextById(anyLong()))
        .thenAnswer(
            inv -> {
              record(metaTx, metaReadOnly);
              return Optional.of("SELECT " + inv.getArgument(0));
            });
    ChartService chartService = mock(ChartService.class);
    when(chartService.getByIdOptional(anyLong(), anyLong()))
        .thenAnswer(
            inv -> {
              record(metaTx, metaReadOnly);
              long chartId = inv.getArgument(0);
              return Optional.of(chart(chartId, chartId + 200L));
            });
    when(chartService.judge(any(), anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(1);
              return judgment(deniedQuery != null && sql.equals("SELECT " + deniedQuery));
            });
    when(chartService.executeJudged(any()))
        .thenAnswer(
            inv -> {
              record(execTx, null);
              return RESULT;
            });
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.resolve(anyLong())).thenReturn(mock(Clearance.class));
    AnalyticsDashboardService service =
        new AnalyticsDashboardService(
            dashRepo,
            widgetRepo,
            chartService,
            chartRepo,
            mock(SavedQueryRepository.class),
            resolver,
            mock(GuardedSqlExecutor.class),
            schema(tibero),
            TransactionTemplateConfig.readOnly(transactionManager),
            mock(AnalyticsAuditRecorder.class));
    return new DashSetup(service, metaTx, metaReadOnly, execTx);
  }

  /** Tibero: 저장 쿼리 2개 실행이 모두 트랜잭션 밖, 모든 조회는 readOnly 트랜잭션 안. */
  @Test
  void dashboard_tibero_twoWidgets_allExecutionsOutsideTx() {
    DashSetup s = dashSetup(true, null);

    DashboardDataResponse r = s.service().getDashboardData(1L, 7L);

    assertThat(r.widgetData()).hasSize(2);
    assertThat(s.execTx()).containsExactly(false, false);
    assertThat(s.metaTx()).isNotEmpty().containsOnly(true);
    assertThat(s.metaReadOnly()).containsOnly(true);
  }

  /** Tibero: 거부된 저장 쿼리의 위젯만 denied 이고 다른 위젯은 결과를 가진다(기존 계약). */
  @Test
  void dashboard_tibero_deniedWidget_stillReturns() {
    DashSetup s = dashSetup(true, 300L);

    DashboardDataResponse r = s.service().getDashboardData(1L, 7L);

    assertThat(r.widgetData()).hasSize(2);
    ChartDataResponse denied = r.widgetData().get(0).chartData();
    ChartDataResponse ok = r.widgetData().get(1).chartData();
    assertThat(denied.denied()).isTrue();
    assertThat(ok.denied()).isFalse();
    assertThat(ok.queryResult()).isEqualTo(RESULT);
    assertThat(s.execTx()).containsExactly(false);
  }

  /** PG: 조회와 실행이 기존대로 readOnly 트랜잭션 하나 안(저장 쿼리 2개 → 실행 2회, 모두 트랜잭션 안). */
  @Test
  void dashboard_pg_executesInsideTx() {
    DashSetup s = dashSetup(false, null);

    s.service().getDashboardData(1L, 7L);

    assertThat(s.execTx()).containsExactly(true, true);
    assertThat(s.metaTx()).isNotEmpty().containsOnly(true);
  }
}
