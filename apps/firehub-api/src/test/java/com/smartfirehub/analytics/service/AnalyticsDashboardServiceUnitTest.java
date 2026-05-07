package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AnalyticsDashboardService 순수 단위 테스트.
 * getDashboardData()에서 chartService.getByIdOptional()이 위젯당 1회만 호출되는지 검증 (이슈 #148).
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsDashboardServiceUnitTest {

  @Mock private AnalyticsDashboardRepository dashboardRepository;
  @Mock private DashboardWidgetRepository widgetRepository;
  @Mock private ChartService chartService;
  @Mock private ChartRepository chartRepository;
  @Mock private SavedQueryRepository savedQueryRepository;

  @InjectMocks private AnalyticsDashboardService dashboardService;

  /**
   * getDashboardData() 위젯 루프에서 동일한 chartId에 대해
   * chartService.getByIdOptional()이 정확히 1회만 호출되는지 검증.
   * 이전 코드는 getById()를 2회 호출(null 체크 + 생성자 인수)하여 불필요한 DB 쿼리가 발생했음.
   */
  @Test
  void getDashboardData_chartServiceGetByIdOptional_calledOncePerWidget() {
    // Given
    Long dashboardId = 1L;
    Long userId = 10L;
    Long chartId = 100L;
    Long widgetId = 200L;
    Long savedQueryId = 300L;

    // 위젯 1개 설정
    DashboardResponse.DashboardWidgetResponse widget =
        new DashboardResponse.DashboardWidgetResponse(
            widgetId, chartId, "Test Chart", "BAR", 0, 0, 6, 4);

    DashboardResponse dashboardResponse =
        new DashboardResponse(
            dashboardId, "Test Dash", null, false, null,
            List.of(widget), 1, "Owner", userId,
            LocalDateTime.now(), LocalDateTime.now());

    ChartResponse chartResponse =
        new ChartResponse(
            chartId, "Test Chart", null, savedQueryId, "Test Query", "BAR",
            Map.of(), false, "Owner", userId, LocalDateTime.now(), LocalDateTime.now(), 0L);

    AnalyticsQueryResponse queryResponse =
        new AnalyticsQueryResponse("SELECT 1", List.of(), List.of(), 0, 0L, 0, false, null);

    when(widgetRepository.findByDashboardId(dashboardId)).thenReturn(List.of(widget));
    when(dashboardRepository.findById(eq(dashboardId), eq(userId), anyList()))
        .thenReturn(Optional.of(dashboardResponse));
    when(chartRepository.findSavedQueryId(chartId)).thenReturn(savedQueryId);
    when(chartRepository.findSavedQuerySqlTextById(savedQueryId)).thenReturn(Optional.of("SELECT 1"));
    when(chartService.executeQueryForCache(anyString())).thenReturn(queryResponse);
    // getByIdOptional이 호출될 때 chartResponse 반환
    when(chartService.getByIdOptional(chartId, userId)).thenReturn(Optional.of(chartResponse));

    // When
    DashboardDataResponse result = dashboardService.getDashboardData(dashboardId, userId);

    // Then: 결과 검증
    assertThat(result).isNotNull();
    assertThat(result.widgetData()).hasSize(1);

    // 핵심 검증: chartId당 getByIdOptional 정확히 1회 호출 (이슈 #148)
    verify(chartService, times(1)).getByIdOptional(chartId, userId);
    // 이전 코드에서 사용하던 getById()는 이 루프에서 호출되면 안 됨
    verify(chartService, never()).getById(chartId, userId);
  }

  /**
   * 위젯이 2개이고 서로 다른 chartId를 가질 때
   * 각 chartId에 대해 getByIdOptional이 각각 1회씩만 호출되는지 검증.
   */
  @Test
  void getDashboardData_twoWidgetsDifferentCharts_eachCalledOnce() {
    // Given
    Long dashboardId = 1L;
    Long userId = 10L;
    Long chartId1 = 100L;
    Long chartId2 = 101L;
    Long savedQueryId = 300L;

    DashboardResponse.DashboardWidgetResponse widget1 =
        new DashboardResponse.DashboardWidgetResponse(
            201L, chartId1, "Chart 1", "BAR", 0, 0, 6, 4);
    DashboardResponse.DashboardWidgetResponse widget2 =
        new DashboardResponse.DashboardWidgetResponse(
            202L, chartId2, "Chart 2", "LINE", 6, 0, 6, 4);

    DashboardResponse dashboardResponse =
        new DashboardResponse(
            dashboardId, "Two Widget Dash", null, false, null,
            List.of(widget1, widget2), 2, "Owner", userId,
            LocalDateTime.now(), LocalDateTime.now());

    ChartResponse chartResponse1 =
        new ChartResponse(
            chartId1, "Chart 1", null, savedQueryId, "Test Query", "BAR",
            Map.of(), false, "Owner", userId, LocalDateTime.now(), LocalDateTime.now(), 0L);
    ChartResponse chartResponse2 =
        new ChartResponse(
            chartId2, "Chart 2", null, savedQueryId, "Test Query", "LINE",
            Map.of(), false, "Owner", userId, LocalDateTime.now(), LocalDateTime.now(), 0L);

    AnalyticsQueryResponse queryResponse =
        new AnalyticsQueryResponse("SELECT 1", List.of(), List.of(), 0, 0L, 0, false, null);

    when(widgetRepository.findByDashboardId(dashboardId)).thenReturn(List.of(widget1, widget2));
    when(dashboardRepository.findById(eq(dashboardId), eq(userId), anyList()))
        .thenReturn(Optional.of(dashboardResponse));
    when(chartRepository.findSavedQueryId(anyLong())).thenReturn(savedQueryId);
    when(chartRepository.findSavedQuerySqlTextById(savedQueryId)).thenReturn(Optional.of("SELECT 1"));
    when(chartService.executeQueryForCache(anyString())).thenReturn(queryResponse);
    when(chartService.getByIdOptional(chartId1, userId)).thenReturn(Optional.of(chartResponse1));
    when(chartService.getByIdOptional(chartId2, userId)).thenReturn(Optional.of(chartResponse2));

    // When
    DashboardDataResponse result = dashboardService.getDashboardData(dashboardId, userId);

    // Then
    assertThat(result.widgetData()).hasSize(2);

    // 각 chartId마다 정확히 1회씩만 호출
    verify(chartService, times(1)).getByIdOptional(chartId1, userId);
    verify(chartService, times(1)).getByIdOptional(chartId2, userId);
    // getById는 절대 호출되면 안 됨
    verify(chartService, never()).getById(anyLong(), anyLong());
  }
}
