package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardWidgetRepository {

  private final DSLContext dsl;

  // dashboard_widget table
  private static final Table<?> DW = table(name("dashboard_widget"));
  private static final Field<Long> DW_ID = field(name("dashboard_widget", "id"), Long.class);
  private static final Field<Long> DW_DASHBOARD_ID =
      field(name("dashboard_widget", "dashboard_id"), Long.class);
  private static final Field<Long> DW_CHART_ID =
      field(name("dashboard_widget", "chart_id"), Long.class);
  private static final Field<Integer> DW_POSITION_X =
      field(name("dashboard_widget", "position_x"), Integer.class);
  private static final Field<Integer> DW_POSITION_Y =
      field(name("dashboard_widget", "position_y"), Integer.class);
  private static final Field<Integer> DW_WIDTH =
      field(name("dashboard_widget", "width"), Integer.class);
  private static final Field<Integer> DW_HEIGHT =
      field(name("dashboard_widget", "height"), Integer.class);
  private static final Field<LocalDateTime> DW_CREATED_AT =
      field(name("dashboard_widget", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> DW_UPDATED_AT =
      field(name("dashboard_widget", "updated_at"), LocalDateTime.class);

  // chart table (join for name/type)
  private static final Table<?> C = table(name("chart"));
  private static final Field<Long> C_ID = field(name("chart", "id"), Long.class);
  private static final Field<String> C_NAME =
      field(name("chart", "name"), String.class).as("chart_name");
  private static final Field<String> C_CHART_TYPE =
      field(name("chart", "chart_type"), String.class).as("chart_type");

  public DashboardWidgetRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<DashboardResponse.DashboardWidgetResponse> findByDashboardId(Long dashboardId) {
    var records =
        dsl.select(
                DW_ID,
                DW_CHART_ID,
                C_NAME,
                C_CHART_TYPE,
                DW_POSITION_X,
                DW_POSITION_Y,
                DW_WIDTH,
                DW_HEIGHT)
            .from(DW)
            .join(C)
            .on(DW_CHART_ID.eq(C_ID))
            .where(DW_DASHBOARD_ID.eq(dashboardId))
            .orderBy(DW_POSITION_Y.asc(), DW_POSITION_X.asc())
            .fetch();

    List<DashboardResponse.DashboardWidgetResponse> result = new ArrayList<>();
    for (Record r : records) {
      result.add(mapToWidgetResponse(r));
    }
    return result;
  }

  public Optional<DashboardResponse.DashboardWidgetResponse> findById(
      Long widgetId, Long dashboardId) {
    Record r =
        dsl.select(
                DW_ID,
                DW_CHART_ID,
                C_NAME,
                C_CHART_TYPE,
                DW_POSITION_X,
                DW_POSITION_Y,
                DW_WIDTH,
                DW_HEIGHT)
            .from(DW)
            .join(C)
            .on(DW_CHART_ID.eq(C_ID))
            .where(DW_ID.eq(widgetId).and(DW_DASHBOARD_ID.eq(dashboardId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToWidgetResponse(r));
  }

  public int countByDashboardId(Long dashboardId) {
    return dsl.selectCount()
        .from(DW)
        .where(DW_DASHBOARD_ID.eq(dashboardId))
        .fetchOne(0, Integer.class);
  }

  public Long insert(Long dashboardId, AddWidgetRequest req) {
    return dsl.insertInto(DW)
        .set(DW_DASHBOARD_ID, dashboardId)
        .set(DW_CHART_ID, req.chartId())
        .set(DW_POSITION_X, req.positionX())
        .set(DW_POSITION_Y, req.positionY())
        .set(DW_WIDTH, req.width())
        .set(DW_HEIGHT, req.height())
        .returning(DW_ID)
        .fetchOne()
        .get(DW_ID);
  }

  public void update(Long widgetId, UpdateWidgetRequest req) {
    var update = dsl.update(DW).set(DW_UPDATED_AT, LocalDateTime.now());

    if (req.positionX() != null) update = update.set(DW_POSITION_X, req.positionX());
    if (req.positionY() != null) update = update.set(DW_POSITION_Y, req.positionY());
    if (req.width() != null) update = update.set(DW_WIDTH, req.width());
    if (req.height() != null) update = update.set(DW_HEIGHT, req.height());

    update.where(DW_ID.eq(widgetId)).execute();
  }

  public void batchUpdateLayout(List<UpdateWidgetLayoutRequest.WidgetPosition> positions) {
    for (UpdateWidgetLayoutRequest.WidgetPosition pos : positions) {
      dsl.update(DW)
          .set(DW_POSITION_X, pos.positionX())
          .set(DW_POSITION_Y, pos.positionY())
          .set(DW_WIDTH, pos.width())
          .set(DW_HEIGHT, pos.height())
          .set(DW_UPDATED_AT, LocalDateTime.now())
          .where(DW_ID.eq(pos.widgetId()))
          .execute();
    }
  }

  public boolean deleteById(Long widgetId, Long dashboardId) {
    int deleted =
        dsl.deleteFrom(DW).where(DW_ID.eq(widgetId).and(DW_DASHBOARD_ID.eq(dashboardId))).execute();
    return deleted > 0;
  }

  private DashboardResponse.DashboardWidgetResponse mapToWidgetResponse(Record r) {
    return new DashboardResponse.DashboardWidgetResponse(
        r.get(DW_ID),
        r.get(DW_CHART_ID),
        r.get("chart_name", String.class),
        r.get("chart_type", String.class),
        r.get(DW_POSITION_X),
        r.get(DW_POSITION_Y),
        r.get(DW_WIDTH),
        r.get(DW_HEIGHT));
  }
}
