package com.smartfirehub.analytics.repository;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 분석 대시보드 위젯(dashboard_widget) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDashboardWidgetRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DashboardWidgetRepository {

  /**
   * 대시보드의 위젯 전체(차트 이름·종류 포함, 조회자 가시성 미적용 원본).
   *
   * <p>주의(WD-195): 조회자에게 보이지 않는 차트의 이름도 들어 있다. 응답에 직접 쓰지 말고 서비스의 withholdInaccessible 을 거친다. 공유 처리
   * (shareOwnedWidgetResources)는 모든 위젯의 차트 id 가 필요해서 여기서 거르지 않는다.
   */
  List<DashboardResponse.DashboardWidgetResponse> findByDashboardId(Long dashboardId);

  Optional<DashboardResponse.DashboardWidgetResponse> findById(Long widgetId, Long dashboardId);

  int countByDashboardId(Long dashboardId);

  /** 이 대시보드에 같은 차트 위젯이 이미 있는지(WD-202 — 같은 차트 중복 추가 거부용). */
  boolean existsByDashboardIdAndChartId(Long dashboardId, Long chartId);

  Long insert(Long dashboardId, AddWidgetRequest req);

  void update(Long widgetId, UpdateWidgetRequest req);

  /**
   * 주어진 위젯 id 중 해당 대시보드에 속한 것만 돌려준다 (WD-193).
   *
   * <p>배치 저장 전에 요청 위젯이 모두 경로 대시보드 소속인지 확인하는 데 쓴다.
   */
  Set<Long> findIdsInDashboard(Long dashboardId, Collection<Long> widgetIds);

  /**
   * 위젯 배치를 일괄 갱신한다.
   *
   * <p>WD-193: 갱신 조건에 dashboard_id 를 함께 건다. 서비스가 먼저 소속을 확인하지만, 이 메서드를 다른 곳에서 불러도 다른 대시보드의 위젯은 절대
   * 바뀌지 않도록 하는 이중 방어다.
   */
  void batchUpdateLayout(
      Long dashboardId, List<UpdateWidgetLayoutRequest.WidgetPosition> positions);

  boolean deleteById(Long widgetId, Long dashboardId);
}
