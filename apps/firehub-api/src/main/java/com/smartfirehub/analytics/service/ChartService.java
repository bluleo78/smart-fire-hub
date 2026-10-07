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
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
    savedQueryRepository
        .findById(req.savedQueryId(), userId)
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
   * 대시보드 캐시 채움용 — 조회자 자격으로 실행한다(판정은 호출자가 먼저 한다 — Task 16). readOnly=false 는 차트 데이터와 같은 기존 동작이다(쓰기
   * 집합도 관문이 VIEW·하향 규칙으로 판정한다 — 판단 사항 8). 캐시 키는 saved_query_id.
   */
  public com.smartfirehub.analytics.dto.AnalyticsQueryResponse executeQueryForCache(
      Clearance viewer, String sql) {
    if (sql == null || sql.isBlank()) {
      return new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
          "SELECT", java.util.List.of(), java.util.List.of(), 0, 0L, 0, false, null);
    }
    return guardedSqlExecutor.executeAnalytics(viewer, sql, 1000, false);
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
    Clearance viewer = clearanceResolver.resolve(userId);
    if (guardedSqlExecutor.isAnalyticsDenied(viewer, sqlText)) {
      return deniedData(chart);
    }
    try {
      return new ChartDataResponse(
          chart, guardedSqlExecutor.executeAnalytics(viewer, sqlText, 1000, false));
    } catch (CodedApiException e) {
      // 사전 판정과 실행 판정 사이 경합(등급·자격 변경)으로 실행 관문이 거부한 경우도 같은 denied 로 — 403 으로 새지 않게.
      if (GuardedSqlExecutor.isSqlAccessDenial(e)) {
        return deniedData(chart);
      }
      throw e;
    }
  }

  /** 조회자가 차트 SQL 을 볼 수 없는지 판정한다(대시보드 일괄 경로용 — 캐시를 읽기 전에 매 요청 호출한다). 실행 문자열과 같은 정규화본을 판정한다. */
  public boolean isDeniedFor(Clearance viewer, String sqlText) {
    return guardedSqlExecutor.isAnalyticsDenied(viewer, sqlText);
  }

  /**
   * denied 위젯 응답 — queryResult 는 null 이 아닌 빈 결과(null 이면 차트 빌더 등 다른 소비자가 깨진다, 판단 사항 7). 거부 코드·원본 이름은
   * 싣지 않는다. chart 메타데이터는 같은 조회자가 GET /charts/{id} 로 이미 받는 값과 같다(새로 드러나는 것 없음).
   */
  public static ChartDataResponse deniedData(ChartResponse chart) {
    return new ChartDataResponse(
        chart,
        new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
            "SELECT", List.of(), List.of(), 0, 0L, 0, false, null),
        true);
  }
}
