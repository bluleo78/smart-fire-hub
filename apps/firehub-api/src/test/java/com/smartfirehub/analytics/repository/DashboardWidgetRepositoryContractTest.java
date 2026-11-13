package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.DashboardResponse.DashboardWidgetResponse;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest.WidgetPosition;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DashboardWidgetRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-710).
 *
 * <p>dashboard_widget 은 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트로 감싼다. 위젯 목록은 조회자 가시성을 적용하지 않은 원본(차트 이름·종류
 * 포함, accessible=true)이고 행·열(position_y, position_x) 순서다. 배치 일괄 저장·삭제는 경로 대시보드의 위젯만 건드린다(WD-193).
 */
public abstract class DashboardWidgetRepositoryContractTest extends TenantPairContractBase {

  protected abstract DashboardWidgetRepository repository();

  private long me;
  private long dash;
  private long otherDash;
  private long chartA;
  private long chartB;

  @BeforeEach
  void createDashboards() {
    me = fx.createUser(prefix + "-me", null);
    long other = fx.createUser(prefix + "-other", null);
    long query = fx.createSavedQuery(tenantA, me, prefix + "-sq", false, null);
    chartA = fx.createChart(tenantA, me, query, false);
    // 남의 비공유 차트 — 원본 목록에는 이름·종류가 그대로 실린다(가림은 서비스 몫).
    chartB = fx.createChart(tenantA, other, query, false);
    dash = fx.createDashboard(tenantA, me, false);
    otherDash = fx.createDashboard(tenantA, me, true);
  }

  private Long insertA(long dashboardId, long chartId, int x, int y, int w, int h) {
    return inA(() -> repository().insert(dashboardId, new AddWidgetRequest(chartId, x, y, w, h)));
  }

  private DashboardWidgetResponse get(Long widgetId) {
    return inA(() -> repository().findById(widgetId, dash)).orElseThrow();
  }

  @Test
  @DisplayName("insert·findById — 위치·크기·차트 id 가 왕복하고 차트 이름·종류(fx-c, BAR)와 accessible=true 가 실린다")
  void insert_roundTrips() {
    Long id = insertA(dash, chartB, 3, 5, 7, 2);

    assertThat(get(id))
        .isEqualTo(new DashboardWidgetResponse(id, chartB, "fx-c", "BAR", 3, 5, 7, 2, true));
    assertThat(inA(() -> repository().findById(id, otherDash))).as("다른 대시보드 경로로는 안 보인다").isEmpty();
    assertThat(inA(() -> repository().findById(-1L, dash))).isEmpty();
    assertThat(inB(() -> repository().findById(id, dash))).isEmpty();
  }

  @Test
  @DisplayName("findByDashboardId — 그 대시보드 위젯만 position_y, position_x 오름차순으로, 다른 테넌트는 빈 목록")
  void findByDashboardId_ordered() {
    Long w1 = insertA(dash, chartA, 6, 0, 6, 4);
    Long w2 = insertA(dash, chartB, 0, 4, 6, 4);
    Long w3 = insertA(dash, chartA, 0, 0, 6, 4);
    insertA(otherDash, chartA, 0, 0, 6, 4);

    assertThat(inA(() -> repository().findByDashboardId(dash)))
        .extracting(DashboardWidgetResponse::id)
        .containsExactly(w3, w1, w2);
    assertThat(inA(() -> repository().findByDashboardId(dash)))
        .allMatch(DashboardWidgetResponse::accessible);
    assertThat(inA(() -> repository().findByDashboardId(-1L))).isEmpty();
    assertThat(inB(() -> repository().findByDashboardId(dash))).isEmpty();
  }

  @Test
  @DisplayName("countByDashboardId·existsByDashboardIdAndChartId — 그 대시보드의 위젯 수와 같은 차트 위젯 유무")
  void countAndExists() {
    insertA(dash, chartA, 0, 0, 6, 4);
    insertA(dash, chartB, 6, 0, 6, 4);
    insertA(otherDash, chartA, 0, 0, 6, 4);

    assertThat(inA(() -> repository().countByDashboardId(dash))).isEqualTo(2);
    assertThat(inA(() -> repository().countByDashboardId(otherDash))).isEqualTo(1);
    assertThat(inA(() -> repository().countByDashboardId(-1L))).isZero();
    assertThat(inB(() -> repository().countByDashboardId(dash))).isZero();

    assertThat(inA(() -> repository().existsByDashboardIdAndChartId(dash, chartA))).isTrue();
    assertThat(inA(() -> repository().existsByDashboardIdAndChartId(otherDash, chartB))).isFalse();
    assertThat(inA(() -> repository().existsByDashboardIdAndChartId(dash, -1L))).isFalse();
    assertThat(inB(() -> repository().existsByDashboardIdAndChartId(dash, chartA))).isFalse();
  }

  @Test
  @DisplayName("update — 넘긴 값만 바꾸고 null 은 유지한다, 다른 테넌트에서는 바뀌지 않는다")
  void update_partial() {
    Long id = insertA(dash, chartA, 1, 2, 3, 4);

    runA(() -> repository().update(id, new UpdateWidgetRequest(5, null, 7, null)));
    assertThat(get(id))
        .extracting(
            DashboardWidgetResponse::positionX,
            DashboardWidgetResponse::positionY,
            DashboardWidgetResponse::width,
            DashboardWidgetResponse::height)
        .containsExactly(5, 2, 7, 4);

    runA(() -> repository().update(id, new UpdateWidgetRequest(null, 9, null, 1)));
    assertThat(get(id))
        .extracting(
            DashboardWidgetResponse::positionX,
            DashboardWidgetResponse::positionY,
            DashboardWidgetResponse::width,
            DashboardWidgetResponse::height)
        .containsExactly(5, 9, 7, 1);

    runA(() -> repository().update(id, new UpdateWidgetRequest(null, null, null, null)));
    assertThat(get(id).positionY()).isEqualTo(9);

    inB(
        () -> {
          repository().update(id, new UpdateWidgetRequest(0, 0, 1, 1));
          return null;
        });
    assertThat(get(id).positionX()).isEqualTo(5);
  }

  @Test
  @DisplayName("findIdsInDashboard — 주어진 id 중 그 대시보드 소속만, 빈 입력은 빈 집합")
  void findIdsInDashboard_filters() {
    Long w1 = insertA(dash, chartA, 0, 0, 6, 4);
    Long w2 = insertA(dash, chartB, 6, 0, 6, 4);
    Long foreign = insertA(otherDash, chartA, 0, 0, 6, 4);

    assertThat(inA(() -> repository().findIdsInDashboard(dash, List.of(w1, w2, foreign, -1L))))
        .containsExactlyInAnyOrder(w1, w2);
    assertThat(inA(() -> repository().findIdsInDashboard(dash, List.of()))).isEmpty();
    assertThat(inB(() -> repository().findIdsInDashboard(dash, List.of(w1, w2)))).isEmpty();
  }

  @Test
  @DisplayName("batchUpdateLayout — 그 대시보드 위젯만 행마다 바꾸고 다른 대시보드 위젯은 그대로 둔다(WD-193)")
  void batchUpdateLayout_scopedToDashboard() {
    Long w1 = insertA(dash, chartA, 0, 0, 6, 4);
    Long w2 = insertA(dash, chartB, 6, 0, 6, 4);
    Long foreign = insertA(otherDash, chartA, 0, 0, 6, 4);

    runA(
        () ->
            repository()
                .batchUpdateLayout(
                    dash,
                    List.of(
                        new WidgetPosition(w1, 1, 2, 3, 4),
                        new WidgetPosition(w2, 5, 6, 7, 8),
                        new WidgetPosition(foreign, 9, 9, 9, 9))));

    assertThat(get(w1))
        .extracting(
            DashboardWidgetResponse::positionX,
            DashboardWidgetResponse::positionY,
            DashboardWidgetResponse::width,
            DashboardWidgetResponse::height)
        .containsExactly(1, 2, 3, 4);
    assertThat(get(w2))
        .extracting(
            DashboardWidgetResponse::positionX,
            DashboardWidgetResponse::positionY,
            DashboardWidgetResponse::width,
            DashboardWidgetResponse::height)
        .containsExactly(5, 6, 7, 8);
    assertThat(inA(() -> repository().findById(foreign, otherDash)).orElseThrow())
        .extracting(DashboardWidgetResponse::positionX, DashboardWidgetResponse::width)
        .as("다른 대시보드 위젯은 그대로")
        .containsExactly(0, 6);

    runA(() -> repository().batchUpdateLayout(dash, List.of()));
  }

  @Test
  @DisplayName("update·batchUpdateLayout — 수정 시각을 새로 쓰고, 다른 대시보드 위젯의 수정 시각은 그대로 둔다(WD-710 P3)")
  void update_and_batchUpdateLayout_moveUpdatedAt() {
    // 시계·시간대 차이에 흔들리지 않게 「지금」과 비교하지 않고 먼 옛 값으로 고정한 뒤 그보다 뒤인지만 본다.
    LocalDateTime old = LocalDateTime.of(2000, 1, 1, 0, 0);
    Long w1 = insertA(dash, chartA, 0, 0, 6, 4);
    Long w2 = insertA(dash, chartB, 6, 0, 6, 4);
    Long foreign = insertA(otherDash, chartA, 0, 0, 6, 4);
    for (Long w : List.of(w1, w2, foreign)) fx.setWidgetUpdatedAt(w, old);

    runA(() -> repository().update(w1, new UpdateWidgetRequest(1, null, null, null)));
    assertThat(fx.widgetUpdatedAt(w1)).as("update 가 수정 시각을 옮긴다").isAfter(old);
    assertThat(fx.widgetUpdatedAt(w2)).isEqualTo(old);

    fx.setWidgetUpdatedAt(w1, old);
    runA(
        () ->
            repository()
                .batchUpdateLayout(
                    dash,
                    List.of(
                        new WidgetPosition(w1, 1, 2, 3, 4),
                        new WidgetPosition(w2, 5, 6, 7, 8),
                        new WidgetPosition(foreign, 9, 9, 9, 9))));
    assertThat(fx.widgetUpdatedAt(w1)).as("batchUpdateLayout 이 행마다 수정 시각을 옮긴다").isAfter(old);
    assertThat(fx.widgetUpdatedAt(w2)).isAfter(old);
    assertThat(fx.widgetUpdatedAt(foreign)).as("다른 대시보드 위젯은 그대로").isEqualTo(old);
  }

  @Test
  @DisplayName("deleteById — 그 대시보드 위젯만 지우고 지웠는지 돌려준다, 다른 대시보드·다른 테넌트·없는 id 는 false")
  void deleteById_scoped() {
    Long id = insertA(dash, chartA, 0, 0, 6, 4);

    assertThat(inA(() -> repository().deleteById(id, otherDash))).isFalse();
    assertThat(inB(() -> repository().deleteById(id, dash))).isFalse();
    assertThat(fx.widgetExists(id)).isTrue();

    assertThat(inA(() -> repository().deleteById(id, dash))).isTrue();
    assertThat(fx.widgetExists(id)).isFalse();
    assertThat(inA(() -> repository().deleteById(id, dash))).isFalse();
  }
}
