package com.smartfirehub.analytics.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsDashboardService {

  private static final int MAX_WIDGETS_PER_DASHBOARD = 20;

  private final AnalyticsDashboardRepository dashboardRepository;
  private final DashboardWidgetRepository widgetRepository;
  private final ChartService chartService;
  private final ChartRepository chartRepository;

  // Caffeine cache: TTL 60s, max 200 entries, keyed by saved_query_id
  private final Cache<Long, AnalyticsQueryResponse> queryResultCache =
      Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(200).build();

  public AnalyticsDashboardService(
      AnalyticsDashboardRepository dashboardRepository,
      DashboardWidgetRepository widgetRepository,
      ChartService chartService,
      ChartRepository chartRepository) {
    this.dashboardRepository = dashboardRepository;
    this.widgetRepository = widgetRepository;
    this.chartService = chartService;
    this.chartRepository = chartRepository;
  }

  public com.smartfirehub.global.dto.PageResponse<DashboardResponse> list(
      String search, Long userId, int page, int size) {
    List<DashboardResponse> content = dashboardRepository.findAll(search, userId, page, size);
    long total = dashboardRepository.countAll(search, userId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new com.smartfirehub.global.dto.PageResponse<>(content, page, size, total, totalPages);
  }

  @Transactional
  public DashboardResponse create(CreateDashboardRequest req, Long userId) {
    Long id = dashboardRepository.insert(req, userId);
    List<DashboardResponse.DashboardWidgetResponse> widgets =
        widgetRepository.findByDashboardId(id);
    return dashboardRepository
        .findById(id, userId, widgets)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found after insert"));
  }

  public DashboardResponse getById(Long id, Long userId) {
    List<DashboardResponse.DashboardWidgetResponse> widgets =
        widgetRepository.findByDashboardId(id);
    return dashboardRepository
        .findById(id, userId, widgets)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + id));
  }

  @Transactional
  public DashboardResponse update(Long id, UpdateDashboardRequest req, Long userId) {
    dashboardRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + id));
    dashboardRepository.update(id, req, userId);
    List<DashboardResponse.DashboardWidgetResponse> widgets =
        widgetRepository.findByDashboardId(id);
    return dashboardRepository
        .findById(id, userId, widgets)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + id));
  }

  @Transactional
  public void delete(Long id, Long userId) {
    dashboardRepository
        .findByIdForOwner(id, userId)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + id));
    boolean deleted = dashboardRepository.deleteById(id, userId);
    if (!deleted) {
      throw new DashboardNotFoundException("Dashboard not found: " + id);
    }
  }

  public DashboardDataResponse getDashboardData(Long dashboardId, Long userId) {
    // 1. Load dashboard + widgets
    List<DashboardResponse.DashboardWidgetResponse> widgets =
        widgetRepository.findByDashboardId(dashboardId);
    DashboardResponse dashboard =
        dashboardRepository
            .findById(dashboardId, userId, widgets)
            .orElseThrow(
                () -> new DashboardNotFoundException("Dashboard not found: " + dashboardId));

    // 2. Enforce widget limit
    List<DashboardResponse.DashboardWidgetResponse> limitedWidgets =
        widgets.size() > MAX_WIDGETS_PER_DASHBOARD
            ? widgets.subList(0, MAX_WIDGETS_PER_DASHBOARD)
            : widgets;

    // 3. Deduplicate by saved_query_id, execute cache misses
    // Build map: savedQueryId -> sqlText (via chart->savedQuery join)
    Map<Long, Long> chartIdToSavedQueryId = new HashMap<>();
    for (DashboardResponse.DashboardWidgetResponse widget : limitedWidgets) {
      Long chartId = widget.chartId();
      if (!chartIdToSavedQueryId.containsKey(chartId)) {
        Long savedQueryId = chartRepository.findSavedQueryId(chartId);
        if (savedQueryId != null) {
          chartIdToSavedQueryId.put(chartId, savedQueryId);
        }
      }
    }

    // Execute each unique savedQueryId (cached)
    for (Long savedQueryId : new java.util.HashSet<>(chartIdToSavedQueryId.values())) {
      queryResultCache.get(
          savedQueryId,
          k -> {
            String sqlText = chartRepository.findSavedQuerySqlTextById(k).orElse("");
            return chartService.executeQueryForCache(sqlText);
          });
    }

    // 4. Build widget data list
    List<DashboardDataResponse.WidgetData> widgetDataList = new ArrayList<>();
    for (DashboardResponse.DashboardWidgetResponse widget : limitedWidgets) {
      Long savedQueryId = chartIdToSavedQueryId.get(widget.chartId());
      AnalyticsQueryResponse queryResult =
          savedQueryId != null ? queryResultCache.getIfPresent(savedQueryId) : null;
      if (queryResult == null) {
        queryResult = emptyQueryResponse();
      }
      ChartDataResponse chartData =
          chartService.getById(widget.chartId(), userId) != null
              ? new ChartDataResponse(chartService.getById(widget.chartId(), userId), queryResult)
              : null;
      if (chartData != null) {
        widgetDataList.add(new DashboardDataResponse.WidgetData(widget.id(), chartData));
      }
    }

    return new DashboardDataResponse(dashboard, widgetDataList);
  }

  @Transactional
  public DashboardResponse addWidget(Long dashboardId, AddWidgetRequest req, Long userId) {
    // Verify dashboard ownership
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + dashboardId));

    // Verify chart access
    chartService
        .getByIdOptional(req.chartId(), userId)
        .orElseThrow(() -> new ChartNotFoundException("Chart not found: " + req.chartId()));

    // Check widget limit
    int currentCount = widgetRepository.countByDashboardId(dashboardId);
    if (currentCount >= MAX_WIDGETS_PER_DASHBOARD) {
      throw new IllegalStateException(
          "Dashboard has reached the maximum of " + MAX_WIDGETS_PER_DASHBOARD + " widgets");
    }

    widgetRepository.insert(dashboardId, req);
    return getById(dashboardId, userId);
  }

  @Transactional
  public DashboardResponse updateWidget(
      Long dashboardId, Long widgetId, UpdateWidgetRequest req, Long userId) {
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + dashboardId));

    widgetRepository
        .findById(widgetId, dashboardId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Widget not found: " + widgetId + " in dashboard " + dashboardId));

    widgetRepository.update(widgetId, req);
    return getById(dashboardId, userId);
  }

  @Transactional
  public void removeWidget(Long dashboardId, Long widgetId, Long userId) {
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + dashboardId));

    boolean deleted = widgetRepository.deleteById(widgetId, dashboardId);
    if (!deleted) {
      throw new IllegalArgumentException(
          "Widget not found: " + widgetId + " in dashboard " + dashboardId);
    }
  }

  @Transactional
  public void updateWidgetLayout(Long dashboardId, UpdateWidgetLayoutRequest req, Long userId) {
    dashboardRepository
        .findByIdForOwner(dashboardId, userId)
        .orElseThrow(() -> new DashboardNotFoundException("Dashboard not found: " + dashboardId));

    widgetRepository.batchUpdateLayout(req.widgets());
  }

  private AnalyticsQueryResponse emptyQueryResponse() {
    return new AnalyticsQueryResponse("SELECT", List.of(), List.of(), 0, 0L, 0, false, null);
  }
}
