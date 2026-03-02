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
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChartService {

  private final ChartRepository chartRepository;
  private final SavedQueryRepository savedQueryRepository;
  private final AnalyticsQueryExecutionService executionService;

  public ChartService(
      ChartRepository chartRepository,
      SavedQueryRepository savedQueryRepository,
      AnalyticsQueryExecutionService executionService) {
    this.chartRepository = chartRepository;
    this.savedQueryRepository = savedQueryRepository;
    this.executionService = executionService;
  }

  /** List charts with optional filters and pagination. */
  public PageResponse<ChartResponse> list(
      String search, String chartType, Long savedQueryId, Long userId, int page, int size) {
    List<ChartResponse> content =
        chartRepository.findAll(search, chartType, savedQueryId, userId, page, size);
    long total = chartRepository.countAll(search, chartType, savedQueryId, userId);
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
  public java.util.Optional<com.smartfirehub.analytics.dto.ChartResponse> getByIdOptional(
      Long id, Long userId) {
    return chartRepository.findById(id, userId);
  }

  /**
   * Execute SQL for cache population (no user context — internal use by dashboard service). Uses
   * readOnly=false to match chart data behavior; cache key is saved_query_id.
   */
  public com.smartfirehub.analytics.dto.AnalyticsQueryResponse executeQueryForCache(String sql) {
    if (sql == null || sql.isBlank()) {
      return new com.smartfirehub.analytics.dto.AnalyticsQueryResponse(
          "SELECT", java.util.List.of(), java.util.List.of(), 0, 0L, 0, false, null);
    }
    return executionService.execute(sql, 1000, false);
  }

  /**
   * Execute the chart's linked saved query and return combined chart + query result. The user must
   * have access to the chart (owner or shared).
   */
  public ChartDataResponse getChartData(Long id, Long userId) {
    ChartResponse chart = getById(id, userId);
    String sqlText =
        chartRepository
            .findSavedQuerySqlText(id, userId)
            .orElseThrow(
                () -> new SavedQueryNotFoundException("Saved query not found for chart: " + id));
    var queryResult = executionService.execute(sqlText, 1000, false);
    return new ChartDataResponse(chart, queryResult);
  }
}
