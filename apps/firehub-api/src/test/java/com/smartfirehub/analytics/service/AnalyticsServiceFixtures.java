package com.smartfirehub.analytics.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 차트 데이터·대시보드 데이터 서비스 테스트 공용 고정물 — 트랜잭션 구간 테스트(ChartDashboardTxTest)와 허가 위치 테스트
 * (InteractiveGatePermitPlacementTest)가 같은 mock 구성을 쓴다. 각 테스트는 조회·실행 시점에 부를 훅({@code onRead}·{@code
 * onExecute})으로 그 순간의 트랜잭션 상태 등을 기록한다.
 */
final class AnalyticsServiceFixtures {

  private AnalyticsServiceFixtures() {}

  /** 조회자(차트·대시보드 소유자). */
  static final long USER = 7L;

  /** 실행 결과(빈 SELECT). */
  static final AnalyticsQueryResponse RESULT =
      new AnalyticsQueryResponse("SELECT", List.of(), List.of(), 0, 0L, 0, false, null);

  /** 아무것도 하지 않는 훅. */
  static final Runnable NONE = () -> {};

  /** 차트 응답 — config 를 비우지 않아 denied 응답이 config 를 지우는지 볼 수 있다. */
  static ChartResponse chart(long id, long savedQueryId) {
    return new ChartResponse(
        id,
        "c" + id,
        null,
        savedQueryId,
        "q",
        "BAR",
        Map.of("x", "col"),
        false,
        "u",
        USER,
        LocalDateTime.now(),
        LocalDateTime.now(),
        0L,
        false);
  }

  /** 판정 토큰 mock — denied 만 정한다. */
  static GuardedSqlExecutor.AnalyticsJudgment judgment(boolean denied) {
    GuardedSqlExecutor.AnalyticsJudgment j = mock(GuardedSqlExecutor.AnalyticsJudgment.class);
    when(j.denied()).thenReturn(denied);
    return j;
  }

  /** 테넌트 저장소 판정 mock. */
  static PipelineDataSchema schema(boolean tibero) {
    PipelineDataSchema schema = mock(PipelineDataSchema.class);
    when(schema.isTibero()).thenReturn(tibero);
    return schema;
  }

  // ---------------------------------------------------------------- 차트

  record ChartFixture(ChartService service, ChartRepository repo, GuardedSqlExecutor executor) {}

  /**
   * 차트 1(저장 쿼리 300, SQL "SELECT 1"). {@code onRead} 는 차트·SQL 조회마다, {@code onExecute} 는 실행마다 불린다.
   * denied 면 판정이 거부다.
   */
  static ChartFixture chartFixture(
      TransactionTemplate tx,
      PipelineDataSchema schema,
      InteractiveQueryConcurrencyGate gate,
      boolean denied,
      Runnable onRead,
      Runnable onExecute) {
    ChartRepository repo = mock(ChartRepository.class);
    when(repo.findById(anyLong(), anyLong()))
        .thenAnswer(
            inv -> {
              onRead.run();
              return Optional.of(chart(1L, 300L));
            });
    when(repo.findSavedQuerySqlText(anyLong(), anyLong()))
        .thenAnswer(
            inv -> {
              onRead.run();
              return Optional.of("SELECT 1");
            });
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.resolve(anyLong())).thenReturn(mock(Clearance.class));
    GuardedSqlExecutor executor = mock(GuardedSqlExecutor.class);
    GuardedSqlExecutor.AnalyticsJudgment judgment = judgment(denied);
    when(executor.judgeAnalytics(any(), anyString())).thenReturn(judgment);
    when(executor.executeJudgedAnalytics(any(), anyInt(), anyBoolean()))
        .thenAnswer(
            inv -> {
              onExecute.run();
              return RESULT;
            });
    ChartService service =
        new ChartService(
            repo,
            mock(SavedQueryRepository.class),
            executor,
            resolver,
            mock(AnalyticsDashboardRepository.class),
            schema,
            tx,
            mock(AnalyticsAuditRecorder.class),
            gate,
            new PermitWaitRejudger(schema, resolver, executor));
    return new ChartFixture(service, repo, executor);
  }

  // ------------------------------------------------------------- 대시보드

  record DashFixture(
      AnalyticsDashboardService service,
      AnalyticsDashboardRepository dashRepo,
      ChartRepository chartRepo,
      ChartService chartService,
      GuardedSqlExecutor guarded,
      GuardedSqlExecutor.AnalyticsJudgment j300,
      GuardedSqlExecutor.AnalyticsJudgment j301) {}

  /**
   * 대시보드 1, 위젯 2개(차트 100→저장 쿼리 300 "SELECT 300", 차트 101→저장 쿼리 301 "SELECT 301"). {@code denied} 에 든
   * 저장 쿼리는 거부 판정이다. 같은 SQL 에는 같은 판정 토큰(j300·j301)을 돌려준다(감사 대상 확인용). {@code onMetaRead} 는 RLS
   * 조회(위젯·대시보드·가시성·저장 쿼리 id·SQL·위젯별 차트)마다, {@code onExecute} 는 실행마다 불린다.
   */
  static DashFixture dashFixture(
      TransactionTemplate tx,
      PipelineDataSchema schema,
      InteractiveQueryConcurrencyGate gate,
      Set<Long> denied,
      Runnable onMetaRead,
      Runnable onExecute) {
    return dashFixture(tx, schema, gate, denied, onMetaRead, onExecute, 2);
  }

  /**
   * 위젯 수를 정하는 판(WD-676) — 위젯 i(0부터)는 차트 100+i → 저장 쿼리 300+i("SELECT 300+i"). 판정 토큰은 300 이면 j300,
   * 나머지는 모두 j301 이다(301 이후 쿼리는 같은 토큰을 공유한다).
   */
  static DashFixture dashFixture(
      TransactionTemplate tx,
      PipelineDataSchema schema,
      InteractiveQueryConcurrencyGate gate,
      Set<Long> denied,
      Runnable onMetaRead,
      Runnable onExecute,
      int widgetCount) {
    List<DashboardResponse.DashboardWidgetResponse> widgets = new java.util.ArrayList<>();
    Set<Long> chartIds = new java.util.HashSet<>();
    for (int i = 0; i < widgetCount; i++) {
      long chartId = 100L + i;
      widgets.add(
          new DashboardResponse.DashboardWidgetResponse(
              10L + i, chartId, "c" + chartId, "BAR", (i % 2) * 6, (i / 2) * 4, 6, 4, true));
      chartIds.add(chartId);
    }
    DashboardResponse dash =
        new DashboardResponse(
            1L,
            "d",
            null,
            false,
            null,
            widgets,
            widgetCount,
            "u",
            USER,
            LocalDateTime.now(),
            LocalDateTime.now());
    DashboardWidgetRepository widgetRepo = mock(DashboardWidgetRepository.class);
    when(widgetRepo.findByDashboardId(anyLong()))
        .thenAnswer(
            inv -> {
              onMetaRead.run();
              return widgets;
            });
    AnalyticsDashboardRepository dashRepo = mock(AnalyticsDashboardRepository.class);
    when(dashRepo.findById(anyLong(), anyLong(), any()))
        .thenAnswer(
            inv -> {
              onMetaRead.run();
              return Optional.of(dash);
            });
    ChartRepository chartRepo = mock(ChartRepository.class);
    // WD-195: 위젯 목록은 조회자 기준 차트 가시성을 판정한다(RLS — 메타데이터 읽기 구간). 두 차트 모두 보인다.
    when(chartRepo.findVisibleIds(any(), anyLong()))
        .thenAnswer(
            inv -> {
              onMetaRead.run();
              return chartIds;
            });
    when(chartRepo.findSavedQueryId(anyLong()))
        .thenAnswer(
            inv -> {
              onMetaRead.run();
              return (Long) inv.getArgument(0) + 200L;
            });
    when(chartRepo.findSavedQuerySqlTextById(anyLong()))
        .thenAnswer(
            inv -> {
              onMetaRead.run();
              return Optional.of("SELECT " + inv.getArgument(0));
            });
    ChartService chartService = mock(ChartService.class);
    when(chartService.getByIdOptional(anyLong(), anyLong()))
        .thenAnswer(
            inv -> {
              onMetaRead.run();
              long chartId = inv.getArgument(0);
              return Optional.of(chart(chartId, chartId + 200L));
            });
    GuardedSqlExecutor.AnalyticsJudgment j300 = judgment(denied.contains(300L));
    GuardedSqlExecutor.AnalyticsJudgment j301 = judgment(denied.contains(301L));
    when(chartService.judge(any(), anyString()))
        .thenAnswer(inv -> "SELECT 300".equals(inv.getArgument(1)) ? j300 : j301);
    when(chartService.executeJudged(any()))
        .thenAnswer(
            inv -> {
              onExecute.run();
              return RESULT;
            });
    ClearanceResolver resolver = mock(ClearanceResolver.class);
    when(resolver.resolve(anyLong())).thenReturn(mock(Clearance.class));
    GuardedSqlExecutor guarded = mock(GuardedSqlExecutor.class);
    // 허가 뒤 재판정(PermitWaitRejudger)은 관문을 직접 부른다 — chartService.judge 와 같은 토큰을 낸다.
    when(guarded.judgeAnalytics(any(), anyString()))
        .thenAnswer(inv -> "SELECT 300".equals(inv.getArgument(1)) ? j300 : j301);
    AnalyticsDashboardService service =
        new AnalyticsDashboardService(
            dashRepo,
            widgetRepo,
            chartService,
            chartRepo,
            mock(SavedQueryRepository.class),
            resolver,
            guarded,
            schema,
            tx,
            mock(AnalyticsAuditRecorder.class),
            gate,
            new PermitWaitRejudger(schema, resolver, guarded));
    return new DashFixture(service, dashRepo, chartRepo, chartService, guarded, j300, j301);
  }
}
