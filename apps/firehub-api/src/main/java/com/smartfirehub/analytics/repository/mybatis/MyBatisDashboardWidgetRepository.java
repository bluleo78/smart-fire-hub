package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.mybatis.DashboardWidgetMapper.WidgetInsert;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * DashboardWidgetRepository 의 MyBatis 구현(WD-710) — {@code JooqDashboardWidgetRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 트랜잭션이 없다(서비스 트랜잭션 안에서 불린다). 배치 일괄 저장도 jOOQ 처럼 위젯마다 UPDATE 한 번씩 이다.
 * 수정 시각은 jOOQ 와 같은 자바 시각을 바인딩한다. 차트·대시보드 삭제 때의 위젯 정리는 DB FK CASCADE 가 맡는다.
 */
@PlatformMyBatisRepository
public class MyBatisDashboardWidgetRepository implements DashboardWidgetRepository {

  private final DashboardWidgetMapper mapper;

  public MyBatisDashboardWidgetRepository(DashboardWidgetMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<DashboardResponse.DashboardWidgetResponse> findByDashboardId(Long dashboardId) {
    return mapper.selectByDashboardId(dashboardId);
  }

  @Override
  public Optional<DashboardResponse.DashboardWidgetResponse> findById(
      Long widgetId, Long dashboardId) {
    return Optional.ofNullable(mapper.selectById(widgetId, dashboardId));
  }

  @Override
  public int countByDashboardId(Long dashboardId) {
    return mapper.countByDashboardId(dashboardId);
  }

  @Override
  public boolean existsByDashboardIdAndChartId(Long dashboardId, Long chartId) {
    return mapper.countByDashboardIdAndChartId(dashboardId, chartId) > 0;
  }

  @Override
  public Long insert(Long dashboardId, AddWidgetRequest req) {
    WidgetInsert row =
        new WidgetInsert(
            dashboardId,
            req.chartId(),
            req.positionX(),
            req.positionY(),
            req.width(),
            req.height());
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public void update(Long widgetId, UpdateWidgetRequest req) {
    mapper.update(
        widgetId, req.positionX(), req.positionY(), req.width(), req.height(), LocalDateTime.now());
  }

  @Override
  public Set<Long> findIdsInDashboard(Long dashboardId, Collection<Long> widgetIds) {
    if (widgetIds.isEmpty()) return Set.of();
    return new HashSet<>(mapper.selectIdsInDashboard(dashboardId, InChunks.of(widgetIds)));
  }

  /**
   * 위젯마다 UPDATE 한 번 — 조건에 dashboard_id 를 함께 걸어 다른 대시보드 위젯은 바뀌지 않는다(WD-193). 수정 시각은 jOOQ 판과 같게 위젯마다
   * {@code LocalDateTime.now()} 를 새로 읽는다(한 번 저장에 한 값으로 끌어올리지 않는다 — jOOQ 가 행마다 now() 라 의미를 맞춘다,
   * WD-710 P4).
   */
  @Override
  public void batchUpdateLayout(
      Long dashboardId, List<UpdateWidgetLayoutRequest.WidgetPosition> positions) {
    for (UpdateWidgetLayoutRequest.WidgetPosition pos : positions) {
      mapper.updateLayout(
          dashboardId,
          pos.widgetId(),
          pos.positionX(),
          pos.positionY(),
          pos.width(),
          pos.height(),
          LocalDateTime.now());
    }
  }

  @Override
  public boolean deleteById(Long widgetId, Long dashboardId) {
    return mapper.deleteByIdAndDashboard(widgetId, dashboardId) > 0;
  }
}
