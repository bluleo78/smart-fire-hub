package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 저장 쿼리·차트 삭제 영향 범위(WD-86) 통합 테스트.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 테스트 트랜잭션이 GUC 를 공급하면 "서비스가 스스로 트랜잭션을 열지 않아 RLS 가 전 행을
 * 막고 영향 0건(=연결 없음, 일반 확인 창)으로 위장되는" 배선 결함이 가려진다. 픽스처만 {@link #inTenantFixture} 로 심고, 검증 대상 호출은 그 블록
 * 밖에서 한다.
 */
class AnalyticsDeleteImpactTest extends IntegrationTestBase {

  @Autowired private SavedQueryService savedQueryService;
  @Autowired private ChartService chartService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  private final List<Long> users = new ArrayList<>();
  private Long owner;
  private Long other;
  private Long otherTenant;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd86_owner");
    other = TenantRlsTestSupport.insertUser(dsl, "wd86_other");
    users.add(owner);
    users.add(other);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 대시보드를 지우면 위젯이, 쿼리를 지우면 차트(와 그 위젯)가 CASCADE 로 함께 사라진다.
    inTenantFixture(
        () -> {
          for (Long u : users) {
            dsl.execute("DELETE FROM dashboard WHERE created_by = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          }
        });
    users.forEach(u -> TenantRlsTestSupport.deleteUser(dsl, u));
    if (otherTenant != null) {
      TenantRlsTestSupport.deleteTenants(dsl, otherTenant);
    }
  }

  // ── 픽스처(기본 테넌트, 트랜잭션 안) ─────────────────────────────────────────

  private Long query(String name, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO saved_query (name, sql_text, is_shared, created_by)"
                        + " VALUES (?, 'SELECT 1', ?, ?) RETURNING id",
                    name,
                    shared,
                    by)
                .get(0, Long.class));
  }

  private Long chart(String name, Long queryId, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO chart (name, saved_query_id, chart_type, is_shared, created_by)"
                        + " VALUES (?, ?, 'BAR', ?, ?) RETURNING id",
                    name,
                    queryId,
                    shared,
                    by)
                .get(0, Long.class));
  }

  private Long dashboard(String name, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO dashboard (name, is_shared, created_by) VALUES (?, ?, ?) RETURNING id",
                    name,
                    shared,
                    by)
                .get(0, Long.class));
  }

  private void widget(Long dashboardId, Long chartId) {
    inTenantFixture(
        () ->
            dsl.execute(
                "INSERT INTO dashboard_widget (dashboard_id, chart_id) VALUES (?, ?)",
                dashboardId,
                chartId));
  }

  private static List<String> names(List<DeleteImpactResponse.ItemRef> refs) {
    return refs.stream().map(DeleteImpactResponse.ItemRef::name).toList();
  }

  // ── 영향 범위 정확성 ─────────────────────────────────────────────────────

  @Test
  void queryImpact_countsChartsWidgetsAndDistinctDashboards() {
    Long q = query("월별 매출", owner, false);
    Long c1 = chart("지역별 매출", q, owner, false);
    Long c2 = chart("월 추이", q, owner, true);
    Long d1 = dashboard("영업 현황", owner, false);
    Long d2 = dashboard("경영 요약", owner, true);
    widget(d1, c1);
    widget(d1, c2);
    widget(d2, c1);
    // 소음: 다른 쿼리의 차트가 같은 대시보드에 있어도 세지 않아야 한다(saved_query_id 필터).
    Long qOther = query("다른 쿼리", owner, false);
    Long cNoise = chart("무관 차트", qOther, owner, false);
    widget(d1, cNoise);

    DeleteImpactResponse impact = savedQueryService.getDeleteImpact(q, owner);

    // 표시 순서는 DB 콜레이션을 따르므로 순서 무관으로 단언한다.
    assertThat(names(impact.charts())).containsExactlyInAnyOrder("월 추이", "지역별 매출");
    assertThat(impact.hiddenChartCount()).isZero();
    assertThat(impact.widgetCount()).isEqualTo(3);
    // d1 에 위젯이 2개 있어도 대시보드는 한 곳으로 센다.
    assertThat(names(impact.dashboards())).containsExactlyInAnyOrder("경영 요약", "영업 현황");
    assertThat(impact.hiddenDashboardCount()).isZero();
  }

  @Test
  void chartImpact_countsWidgetsAndDashboards_withoutCharts() {
    Long q = query("월별 매출", owner, false);
    Long c1 = chart("지역별 매출", q, owner, false);
    Long c2 = chart("월 추이", q, owner, false);
    Long d1 = dashboard("영업 현황", owner, false);
    Long d2 = dashboard("경영 요약", owner, false);
    widget(d1, c1);
    widget(d2, c1);
    widget(d1, c2);

    DeleteImpactResponse impact = chartService.getDeleteImpact(c1, owner);

    assertThat(impact.charts()).isEmpty();
    assertThat(impact.hiddenChartCount()).isZero();
    assertThat(impact.widgetCount()).isEqualTo(2);
    assertThat(names(impact.dashboards())).containsExactlyInAnyOrder("경영 요약", "영업 현황");
    assertThat(impact.hiddenDashboardCount()).isZero();
  }

  @Test
  void noLinks_returnsEmptyImpact() {
    Long q = query("홀로 쿼리", owner, false);
    Long c = chart("홀로 차트", query("다른 쿼리", owner, false), owner, false);

    DeleteImpactResponse queryImpact = savedQueryService.getDeleteImpact(q, owner);
    DeleteImpactResponse chartImpact = chartService.getDeleteImpact(c, owner);

    for (DeleteImpactResponse impact : List.of(queryImpact, chartImpact)) {
      assertThat(impact.charts()).isEmpty();
      assertThat(impact.hiddenChartCount()).isZero();
      assertThat(impact.widgetCount()).isZero();
      assertThat(impact.dashboards()).isEmpty();
      assertThat(impact.hiddenDashboardCount()).isZero();
    }
  }

  // ── 가시성: 볼 수 없는 항목은 이름 없이 개수만 ─────────────────────────────────

  @Test
  void queryImpact_hidesNamesOfItemsViewerCannotSee_butCountsThem() throws Exception {
    // 공유 쿼리에 다른 사용자가 차트를 만들었다 — CASCADE 는 그것까지 지운다.
    Long q = query("공유 쿼리", owner, true);
    Long privateChart = chart("남의 비공개 차트", q, other, false);
    Long sharedChart = chart("남의 공유 차트", q, other, true);
    Long privateDash = dashboard("남의 비공개 대시보드", other, false);
    Long sharedDash = dashboard("남의 공유 대시보드", other, true);
    widget(privateDash, privateChart);
    widget(sharedDash, sharedChart);

    DeleteImpactResponse impact = savedQueryService.getDeleteImpact(q, owner);

    assertThat(names(impact.charts())).containsExactly("남의 공유 차트");
    assertThat(impact.hiddenChartCount()).isEqualTo(1);
    assertThat(impact.widgetCount()).isEqualTo(2);
    assertThat(names(impact.dashboards())).containsExactly("남의 공유 대시보드");
    assertThat(impact.hiddenDashboardCount()).isEqualTo(1);
    // 응답 직렬화에도 볼 수 없는 이름·id 가 실리지 않는다.
    String json = objectMapper.writeValueAsString(impact);
    assertThat(json).doesNotContain("남의 비공개 차트", "남의 비공개 대시보드");
    // id 도 싣지 않는다 — 차트·대시보드 id 는 테이블마다 따로 매겨져 겹칠 수 있어 목록별로 본다.
    assertThat(impact.charts())
        .extracting(DeleteImpactResponse.ItemRef::id)
        .containsExactly(sharedChart);
    assertThat(impact.dashboards())
        .extracting(DeleteImpactResponse.ItemRef::id)
        .containsExactly(sharedDash);
  }

  @Test
  void chartImpact_hidesDashboardNameViewerCannotSee() {
    Long q = query("내 쿼리", owner, false);
    Long c = chart("내 공유 차트", q, owner, true);
    // 다른 사용자가 내 공유 차트를 자기 비공개 대시보드에 올렸다.
    Long privateDash = dashboard("남의 비공개 대시보드", other, false);
    widget(privateDash, c);

    DeleteImpactResponse impact = chartService.getDeleteImpact(c, owner);

    assertThat(impact.widgetCount()).isEqualTo(1);
    assertThat(impact.dashboards()).isEmpty();
    assertThat(impact.hiddenDashboardCount()).isEqualTo(1);
  }

  // ── 관문: 삭제와 같은 소유자 전용·같은 404 ────────────────────────────────────

  @Test
  void nonOwner_andMissingId_getSameNotFound() {
    Long q = query("공유 쿼리", owner, true);
    Long c = chart("공유 차트", q, owner, true);

    // 공유돼 있어 볼 수는 있어도 지울 수 없는 사람에게는 영향 범위를 주지 않는다(삭제와 같은 404).
    assertThatThrownBy(() -> savedQueryService.getDeleteImpact(q, other))
        .isInstanceOf(SavedQueryNotFoundException.class);
    assertThatThrownBy(() -> chartService.getDeleteImpact(c, other))
        .isInstanceOf(ChartNotFoundException.class);
    assertThatThrownBy(() -> savedQueryService.getDeleteImpact(Long.MAX_VALUE, owner))
        .isInstanceOf(SavedQueryNotFoundException.class);
    assertThatThrownBy(() -> chartService.getDeleteImpact(Long.MAX_VALUE, owner))
        .isInstanceOf(ChartNotFoundException.class);
  }

  @Test
  void otherTenantContext_cannotSeeImpact() {
    Long q = query("A 테넌트 쿼리", owner, false);
    Long c = chart("A 테넌트 차트", q, owner, false);
    widget(dashboard("A 테넌트 대시보드", owner, false), c);
    otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd86");

    // 소유자 id 그대로라도 다른 테넌트 컨텍스트에서는 RLS 가 행을 가려 404 다(소유자 판정만으로는 통과했을 것).
    TenantContext.runScoped(
        otherTenant,
        () -> {
          assertThatThrownBy(() -> savedQueryService.getDeleteImpact(q, owner))
              .isInstanceOf(SavedQueryNotFoundException.class);
          assertThatThrownBy(() -> chartService.getDeleteImpact(c, owner))
              .isInstanceOf(ChartNotFoundException.class);
        });
  }
}
