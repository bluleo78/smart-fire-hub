package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChartService {

  private final ChartRepository chartRepository;
  private final SavedQueryRepository savedQueryRepository;
  private final GuardedSqlExecutor guardedSqlExecutor;
  private final ClearanceResolver clearanceResolver;

  /** List charts with optional filters and pagination. */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public PageResponse<ChartResponse> list(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Long userId,
      int page,
      int size) {
    List<ChartResponse> content =
        chartRepository.findAll(search, chartType, savedQueryId, sharedOnly, userId, page, size);
    long total = chartRepository.countAll(search, chartType, savedQueryId, sharedOnly, userId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PageResponse<>(content, page, size, total, totalPages);
  }

  /** Create a new chart. Validates that the referenced saved query is accessible. */
  @Transactional
  public ChartResponse create(CreateChartRequest req, Long userId) {
    // 저장 쿼리 접근 확인만 한다(연결 데이터셋 이름은 쓰지 않아 가시성 조건이 필요 없다).
    savedQueryRepository
        .findById(req.savedQueryId(), userId, DSL.trueCondition())
        .orElseThrow(
            () -> new SavedQueryNotFoundException("Saved query not found: " + req.savedQueryId()));
    if ("MAP".equals(req.chartType())) {
      Object spatialColumn = req.config() != null ? req.config().get("spatialColumn") : null;
      if (spatialColumn == null || spatialColumn.toString().isBlank()) {
        throw new IllegalArgumentException("MAP 차트는 config에 spatialColumn이 필요합니다");
      }
    }
    Long id = chartRepository.insert(req, userId);
    return chartRepository
        .findById(id, userId)
        .orElseThrow(() -> new ChartNotFoundException("Chart not found after insert"));
  }

  /** Get a single chart — owner or any shared chart. */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  // 참고(자기호출): getChartData 가 이 메서드를 this. 로 직접 호출하지만, getChartData 도
  // 아래에서 같은 @Transactional(readOnly=true)로 열려 있어 프록시를 안 타도 무해하다.
  @Transactional(readOnly = true)
  public ChartResponse getById(Long id, Long userId) {
    return chartRepository
        .findById(id, userId)
        .orElseThrow(() -> new ChartNotFoundException("Chart not found: " + id));
  }

  /** Update a chart (owner only). */
  @Transactional
  public ChartResponse update(Long id, UpdateChartRequest req, Long userId) {
    ChartResponse existing =
        chartRepository
            .findByIdForOwner(id, userId)
            .orElseThrow(() -> new ChartNotFoundException("Chart not found: " + id));
    String effectiveType = req.chartType() != null ? req.chartType() : existing.chartType();
    java.util.Map<String, Object> effectiveConfig =
        req.config() != null ? req.config() : existing.config();
    if ("MAP".equals(effectiveType)) {
      Object spatialColumn = effectiveConfig != null ? effectiveConfig.get("spatialColumn") : null;
      if (spatialColumn == null || spatialColumn.toString().isBlank()) {
        throw new IllegalArgumentException("MAP 차트는 config에 spatialColumn이 필요합니다");
      }
    }
    chartRepository.update(id, req, userId);
    return chartRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> new ChartNotFoundException("Chart not found: " + id));
  }

  /** Delete a chart (owner only). */
  @Transactional
  public void delete(Long id, Long userId) {
    chartRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> new ChartNotFoundException("Chart not found: " + id));
    boolean deleted = chartRepository.deleteById(id, userId);
    if (!deleted) {
      throw new ChartNotFoundException("Chart not found: " + id);
    }
  }

  /** Get a single chart without throwing — used for dashboard data loading. */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public java.util.Optional<com.smartfirehub.analytics.dto.ChartResponse> getByIdOptional(
      Long id, Long userId) {
    return chartRepository.findById(id, userId);
  }

  /**
   * 조회자 자격으로 판정하고 실행한다(빈 SQL 은 빈 결과). readOnly=false 는 차트 데이터와 같은 기존 동작이다(쓰기 집합도 관문이 VIEW·하향 규칙으로
   * 판정한다 — 판단 사항 8).
   */
  public com.smartfirehub.analytics.dto.AnalyticsQueryResponse executeQueryForCache(
      Clearance viewer, String sql) {
    if (sql == null || sql.isBlank()) {
      return new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
          "SELECT", java.util.List.of(), java.util.List.of(), 0, 0L, 0, false, null);
    }
    return executeJudged(judge(viewer, sql));
  }

  /**
   * 조회자 기준 차트 SQL 판정(실행하지 않음) — 대시보드 일괄 경로가 캐시를 읽기 전에 매 요청 부른다. 거부는 예외가 아니라 {@link
   * GuardedSqlExecutor.AnalyticsJudgment#denied()} 값이다(위젯 하나 때문에 화면 전체가 403 이 되지 않게).
   */
  public GuardedSqlExecutor.AnalyticsJudgment judge(Clearance viewer, String sqlText) {
    return guardedSqlExecutor.judgeAnalytics(viewer, sqlText);
  }

  /**
   * 판정 토큰을 차트 데이터 조건(최대 1000행, readOnly=false)으로 실행한다 — 판정한 바로 그 정규화본이 실행되고 다시 판정하지 않는다. 대시보드 캐시
   * 채움(캐시 키는 saved_query_id + 판정한 SQL 원문)과 단건 차트 데이터가 같이 쓴다.
   */
  public com.smartfirehub.analytics.dto.AnalyticsQueryResponse executeJudged(
      GuardedSqlExecutor.AnalyticsJudgment judgment) {
    return guardedSqlExecutor.executeJudgedAnalytics(judgment, 1000, false);
  }

  /**
   * Execute the chart's linked saved query and return combined chart + query result. The user must
   * have access to the chart (owner or shared).
   */
  // RLS 가 걸린 chart 를 읽는다 — 트랜잭션이 없으면 GUC 미설정으로 조용히 0행이 된다.
  @Transactional(readOnly = true)
  public ChartDataResponse getChartData(Long id, Long userId) {
    ChartResponse chart = getById(id, userId);
    String sqlText =
        chartRepository
            .findSavedQuerySqlText(id, userId)
            .orElseThrow(
                () -> new SavedQueryNotFoundException("Saved query not found for chart: " + id));
    // 보안 등급(S2): 조회자 기준 판정 — 위반이면 실행하지 않고 200 + denied. 단건 위젯 경로(DashboardWidgetCard→useChartData)도
    // 대시보드 일괄 경로와 같은 계약이어야 위젯 하나가 화면 전체를 오류로 만들지 않는다(스펙 §4.2 4행).
    // 판정은 한 번 — 통과한 토큰의 정규화본을 그대로 실행하므로 판정과 실행 사이에 다른 판정이 끼지 않는다.
    GuardedSqlExecutor.AnalyticsJudgment judgment =
        judge(clearanceResolver.resolve(userId), sqlText);
    if (judgment.denied()) {
      return deniedData(chart);
    }
    // 내보내기 플래그는 조회자 판정 토큰에서 계산한다(설계 결정 5 — 결과 캐시와 무관하게 조회자별).
    return new ChartDataResponse(chart, executeJudged(judgment), false, exportAllowedFor(judgment));
  }

  /**
   * 조회자 기준 차트 데이터 내보내기 가능 — 판정 토큰(조회자 자격으로 방금 판정)에서 계산하므로 대시보드 공유 결과 캐시와 무관하다(Review Focus 2).
   * EXPORT 정책 AND data:export 권한.
   */
  public static boolean exportAllowedFor(GuardedSqlExecutor.AnalyticsJudgment judgment) {
    return judgment.exportAllowedFor();
  }

  /**
   * denied 위젯 응답 — queryResult 는 null 이 아닌 빈 결과(null 이면 차트 빌더 등 다른 소비자가 깨진다, 판단 사항 7). 거부 코드·원본 이름은
   * 싣지 않는다. chart 는 웹 잠금 상태가 쓰는 최소 메타만 남긴다 — config(원본 테이블의 컬럼명이 들어 있다)는 빈 맵, savedQueryName 은
   * null. 빈 맵인 이유: config 를 순회하는 소비자(웹 ChartRenderer·ai-agent)가 null 에서 깨지지 않게. GET /charts/{id} 의
   * config 는 소유자 재저장 덮어쓰기 위험 때문에 그대로 둔다(알려진 한계).
   */
  public static ChartDataResponse deniedData(ChartResponse chart) {
    ChartResponse minimal =
        new ChartResponse(
            chart.id(),
            chart.name(),
            chart.description(),
            chart.savedQueryId(),
            null,
            chart.chartType(),
            Map.of(),
            chart.isShared(),
            chart.createdByName(),
            chart.createdBy(),
            chart.createdAt(),
            chart.updatedAt(),
            chart.dashboardCount());
    return new ChartDataResponse(
        minimal,
        new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
            "SELECT", List.of(), List.of(), 0, 0L, 0, false, null),
        true);
  }
}
