package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.analytics.dto.DashboardResponse.DashboardWidgetResponse;
import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 분석 대시보드 위젯(dashboard_widget) MyBatis 매퍼(WD-710). SQL 은 {@code
 * mybatis/analytics/DashboardWidgetMapper.xml}. 테넌트는 정책(RLS)이 맡는다.
 *
 * <p>위젯 응답은 조회자 가시성 미적용 원본이라 accessible 은 늘 참이다(가림은 서비스의 withholdInaccessible, WD-195).
 */
@PlatformMapper
public interface DashboardWidgetMapper {

  /** 대시보드의 위젯 전체 — position_y, position_x 오름차순(jOOQ 그대로). */
  List<DashboardWidgetResponse> selectByDashboardId(@Param("dashboardId") Long dashboardId);

  /** 그 대시보드의 위젯 한 건 — 없으면 null. */
  DashboardWidgetResponse selectById(
      @Param("widgetId") Long widgetId, @Param("dashboardId") Long dashboardId);

  /** 대시보드의 위젯 수. */
  int countByDashboardId(@Param("dashboardId") Long dashboardId);

  /** 대시보드에 같은 차트를 쓰는 위젯 수(있는지 판정용). */
  int countByDashboardIdAndChartId(
      @Param("dashboardId") Long dashboardId, @Param("chartId") Long chartId);

  /** 한 건을 넣고 생성 키를 홀더에 돌려준다. */
  int insert(WidgetInsert row);

  /** 넘긴 값만 고친다(null 은 유지) — 수정 시각은 늘 쓴다. 조건은 jOOQ 그대로 id 만. */
  int update(
      @Param("widgetId") Long widgetId,
      @Param("positionX") Integer positionX,
      @Param("positionY") Integer positionY,
      @Param("width") Integer width,
      @Param("height") Integer height,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** id 조각 중 그 대시보드 소속 위젯 id. */
  List<Long> selectIdsInDashboard(
      @Param("dashboardId") Long dashboardId, @Param("chunks") List<List<Long>> chunks);

  /** 배치 한 건 — 그 대시보드 소속 위젯일 때만 바꾼다(WD-193 이중 방어). 바꾼 행 수. */
  int updateLayout(
      @Param("dashboardId") Long dashboardId,
      @Param("widgetId") Long widgetId,
      @Param("positionX") int positionX,
      @Param("positionY") int positionY,
      @Param("width") int width,
      @Param("height") int height,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 그 대시보드의 위젯 한 건을 지운다. 지운 행 수. */
  int deleteByIdAndDashboard(
      @Param("widgetId") Long widgetId, @Param("dashboardId") Long dashboardId);

  /**
   * 위젯 INSERT 파라미터 겸 생성 키 홀더. Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키가
   * {@code id} 에 써 넣는다.
   */
  class WidgetInsert extends GeneratedId {
    private final Long dashboardId;
    private final Long chartId;
    private final int positionX;
    private final int positionY;
    private final int width;
    private final int height;

    public WidgetInsert(
        Long dashboardId, Long chartId, int positionX, int positionY, int width, int height) {
      this.dashboardId = dashboardId;
      this.chartId = chartId;
      this.positionX = positionX;
      this.positionY = positionY;
      this.width = width;
      this.height = height;
    }

    public Long getDashboardId() {
      return dashboardId;
    }

    public Long getChartId() {
      return chartId;
    }

    public int getPositionX() {
      return positionX;
    }

    public int getPositionY() {
      return positionY;
    }

    public int getWidth() {
      return width;
    }

    public int getHeight() {
      return height;
    }
  }
}
