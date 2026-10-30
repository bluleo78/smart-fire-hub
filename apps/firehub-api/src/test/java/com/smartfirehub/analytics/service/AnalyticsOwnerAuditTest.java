package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작성자 본인의 저장 쿼리·차트·대시보드 변경 감사(WD-464) 통합 테스트.
 *
 * <p>검증: 생성·수정·삭제·공유 켜기/끄기(대시보드 공유에 딸린 차트·쿼리 공유 포함)·복제가 audit_log 에 한 행씩 남고, 변경 없는 저장과 거부된
 * 요청(404·409 편집 충돌·400)은 아무것도 남기지 않으며, SQL·설명·차트 설정·폴더 값이 설명·metadata 에 새지 않는다. 대시보드 생성 행이 홈 활동 피드에
 * 실리지 않는 것도 확인한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 서비스가 스스로 연 트랜잭션에서 감사가 커밋되는지, 거부된 요청의 롤백으로 감사가 남지 않는지를
 * 실제 커밋으로 본다. 감사 행 조회·정리는 RLS 테이블이라 {@link #inTenantFixture} 안에서 한다.
 */
class AnalyticsOwnerAuditTest extends IntegrationTestBase {

  /** 값 누출 검사용 표식 — SQL·설명·설정·폴더에 넣고 감사 설명·metadata 에 없어야 한다. */
  private static final String MARKER = "MARKER_WD464_SECRET";

  @Autowired private SavedQueryService savedQueryService;
  @Autowired private ChartService chartService;
  @Autowired private AnalyticsDashboardService dashboardService;
  @Autowired private DashboardService homeDashboardService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  private final List<Long> users = new ArrayList<>();
  private Long owner;
  private Long other;

  /** 감사 행 한 줄(검증용 투영). */
  private record Audit(
      String action, String resource, String resourceId, String description, JsonNode metadata) {}

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd464_owner");
    other = TenantRlsTestSupport.insertUser(dsl, "wd464_other");
    users.add(owner);
    users.add(other);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 감사 행은 user_id FK 라 사용자보다 먼저 지운다. 대시보드·쿼리를 지우면 위젯·차트가 CASCADE 로 함께 사라진다.
    inTenantFixture(
        () -> {
          for (Long u : users) {
            dsl.execute("DELETE FROM audit_log WHERE user_id = ?", u);
            dsl.execute("DELETE FROM dashboard WHERE created_by = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          }
        });
    users.forEach(u -> TenantRlsTestSupport.deleteUser(dsl, u));
    users.clear();
  }

  // ── 픽스처·조회 도우미 ────────────────────────────────────────────────────────

  /** 행위자의 감사 행(분석 자산 resource 만, 기록 순). */
  private List<Audit> audits(Long userId) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                    "SELECT action_type, resource, resource_id, description, metadata::text AS m"
                        + " FROM audit_log WHERE user_id = ?"
                        + " AND resource IN ('saved_query','chart','dashboard') ORDER BY id",
                    userId)
                .map(
                    r ->
                        new Audit(
                            r.get(0, String.class),
                            r.get(1, String.class),
                            r.get(2, String.class),
                            r.get(3, String.class),
                            readJson(r.get(4, String.class)))));
  }

  private JsonNode readJson(String json) {
    try {
      return json == null ? null : objectMapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 감사 행 어디에도(설명·metadata) 표식 값이 실리지 않았는지. */
  private void assertNoLeak(List<Audit> rows) {
    for (Audit a : rows) {
      assertThat(a.description()).doesNotContain(MARKER);
      assertThat(String.valueOf(a.metadata())).doesNotContain(MARKER);
    }
  }

  private static List<String> changedFields(Audit a) {
    List<String> out = new ArrayList<>();
    a.metadata().get("changedFields").forEach(n -> out.add(n.asText()));
    return out;
  }

  private SavedQueryResponse createQuery(String name, boolean shared) {
    return savedQueryService.create(
        new CreateSavedQueryRequest(
            name, MARKER + " 설명", "SELECT '" + MARKER + "' AS x", null, MARKER, shared),
        owner);
  }

  private ChartResponse createChart(String name, Long queryId, boolean shared) {
    return chartService.create(
        new CreateChartRequest(
            name, MARKER + " 설명", queryId, "BAR", Map.of("xAxis", MARKER), shared),
        owner);
  }

  // ── 저장 쿼리 ─────────────────────────────────────────────────────────────

  @Test
  void savedQuery_createUpdateDelete_recordsOneRowEach_withoutValues() {
    SavedQueryResponse q = createQuery("감사 쿼리", false);
    savedQueryService.update(
        q.id(),
        new UpdateSavedQueryRequest(
            "감사 쿼리2", MARKER + " 새 설명", "SELECT '" + MARKER + "2' AS x", null, null, null),
        owner);
    savedQueryService.delete(q.id(), owner);

    List<Audit> rows = audits(owner);
    assertThat(rows).extracting(Audit::action).containsExactly("CREATE", "UPDATE", "DELETE");
    assertThat(rows).extracting(Audit::resource).containsOnly("saved_query");
    assertThat(rows).extracting(Audit::resourceId).containsOnly(String.valueOf(q.id()));
    assertThat(rows.get(0).description()).isEqualTo("저장 쿼리 생성: 감사 쿼리");
    assertThat(rows.get(0).metadata().get("name").asText()).isEqualTo("감사 쿼리");
    assertThat(rows.get(0).metadata().get("isShared").asBoolean()).isFalse();
    assertThat(rows.get(1).description()).isEqualTo("저장 쿼리 수정: 감사 쿼리2 (바뀐 항목: 이름·설명·SQL)");
    assertThat(changedFields(rows.get(1))).containsExactly("name", "description", "sqlText");
    assertThat(rows.get(2).description()).isEqualTo("저장 쿼리 삭제: 감사 쿼리2");
    assertThat(rows.get(2).metadata().get("wasShared").asBoolean()).isFalse();
    assertNoLeak(rows);
  }

  @Test
  void savedQuery_saveWithSameValues_writesNothing() {
    SavedQueryResponse q = createQuery("그대로", false);
    // 웹 편집기처럼 모든 항목을 기존 값 그대로 보내는 저장
    savedQueryService.update(
        q.id(),
        new UpdateSavedQueryRequest(
            q.name(), q.description(), q.sqlText(), q.datasetId(), q.folder(), q.isShared()),
        owner);

    assertThat(audits(owner)).extracting(Audit::action).containsExactly("CREATE");
  }

  @Test
  void savedQuery_shareToggle_recordsStatusChange_andMixedSaveSplitsIntoTwoRows() {
    SavedQueryResponse q = createQuery("공유 쿼리", false);
    savedQueryService.update(
        q.id(), new UpdateSavedQueryRequest(null, null, null, null, null, true), owner);
    savedQueryService.update(
        q.id(), new UpdateSavedQueryRequest(null, null, null, null, null, false), owner);
    // 이름과 공유를 한 번에 — UPDATE(이름) + STATUS_CHANGE(공유) 두 행
    savedQueryService.update(
        q.id(), new UpdateSavedQueryRequest("공유 쿼리2", null, null, null, null, true), owner);

    List<Audit> rows = audits(owner);
    assertThat(rows)
        .extracting(Audit::action)
        .containsExactly("CREATE", "STATUS_CHANGE", "STATUS_CHANGE", "UPDATE", "STATUS_CHANGE");
    assertThat(rows.get(1).description()).isEqualTo("저장 쿼리 공유 켜기: 공유 쿼리");
    assertThat(changedFields(rows.get(1))).containsExactly("isShared");
    assertThat(rows.get(1).metadata().get("isShared").asBoolean()).isTrue();
    assertThat(rows.get(2).description()).isEqualTo("저장 쿼리 공유 끄기: 공유 쿼리");
    assertThat(rows.get(3).description()).isEqualTo("저장 쿼리 수정: 공유 쿼리2 (바뀐 항목: 이름)");
    assertThat(changedFields(rows.get(3))).containsExactly("name");
    assertThat(rows.get(4).description()).isEqualTo("저장 쿼리 공유 켜기: 공유 쿼리2");
    assertNoLeak(rows);
  }

  @Test
  void savedQuery_rejectedRequests_writeNothing() {
    SavedQueryResponse q = createQuery("거부 쿼리", false);

    // 409 편집 충돌(WD-364) — 낡은 기준 버전
    assertThatThrownBy(
            () ->
                savedQueryService.update(
                    q.id(),
                    new UpdateSavedQueryRequest(
                        "바뀌면 안 됨",
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.of(2000, 1, 1, 0, 0)),
                    owner))
        .isInstanceOf(EditConflictException.class);
    // 404 — 남의 쿼리 수정·삭제
    assertThatThrownBy(
            () ->
                savedQueryService.update(
                    q.id(), new UpdateSavedQueryRequest("남", null, null, null, null, null), other))
        .isInstanceOf(SavedQueryNotFoundException.class);
    assertThatThrownBy(() -> savedQueryService.delete(q.id(), other))
        .isInstanceOf(SavedQueryNotFoundException.class);

    assertThat(audits(owner)).extracting(Audit::action).containsExactly("CREATE");
    assertThat(audits(other)).isEmpty();
  }

  @Test
  void savedQuery_clone_recordsCreateWithSource() {
    SavedQueryResponse q = createQuery("원본", true);
    SavedQueryResponse copy = savedQueryService.clone(q.id(), other);

    List<Audit> rows = audits(other);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).action()).isEqualTo("CREATE");
    assertThat(rows.get(0).resourceId()).isEqualTo(String.valueOf(copy.id()));
    assertThat(rows.get(0).description()).isEqualTo("저장 쿼리 생성: 원본 (복사본)");
    assertThat(rows.get(0).metadata().get("clonedFrom").asLong()).isEqualTo(q.id());
    assertNoLeak(rows);
  }

  // ── 차트 ──────────────────────────────────────────────────────────────────

  @Test
  void chart_createUpdateShareDelete_recorded_withoutConfigValues() {
    SavedQueryResponse q = createQuery("차트용 쿼리", false);
    ChartResponse c = createChart("감사 차트", q.id(), false);
    chartService.update(
        c.id(),
        new UpdateChartRequest(null, null, null, Map.of("xAxis", MARKER + "_changed"), null),
        owner);
    // 같은 설정을 다시 보내는 저장 — 남지 않는다
    chartService.update(
        c.id(),
        new UpdateChartRequest(
            "감사 차트", c.description(), "BAR", Map.of("xAxis", MARKER + "_changed"), false),
        owner);
    chartService.update(c.id(), new UpdateChartRequest(null, null, null, null, true), owner);
    chartService.delete(c.id(), owner);

    List<Audit> rows = audits(owner).stream().filter(a -> a.resource().equals("chart")).toList();
    assertThat(rows)
        .extracting(Audit::action)
        .containsExactly("CREATE", "UPDATE", "STATUS_CHANGE", "DELETE");
    assertThat(rows.get(0).description()).isEqualTo("차트 생성: 감사 차트");
    assertThat(rows.get(1).description()).isEqualTo("차트 수정: 감사 차트 (바뀐 항목: 차트 설정)");
    assertThat(changedFields(rows.get(1))).containsExactly("config");
    assertThat(rows.get(2).description()).isEqualTo("차트 공유 켜기: 감사 차트");
    assertThat(rows.get(3).description()).isEqualTo("차트 삭제: 감사 차트");
    assertThat(rows.get(3).metadata().get("wasShared").asBoolean()).isTrue();
    assertNoLeak(audits(owner));
  }

  @Test
  void chart_rejectedRequests_writeNothing() {
    SavedQueryResponse q = createQuery("차트 거부 쿼리", false);
    ChartResponse c = createChart("거부 차트", q.id(), false);

    // 400 — MAP 차트에 공간 컬럼 없음
    assertThatThrownBy(
            () ->
                chartService.create(
                    new CreateChartRequest("지도", null, q.id(), "MAP", Map.of(), false), owner))
        .isInstanceOf(IllegalArgumentException.class);
    // 409 편집 충돌
    assertThatThrownBy(
            () ->
                chartService.update(
                    c.id(),
                    new UpdateChartRequest(
                        "바뀌면 안 됨", null, null, null, null, LocalDateTime.of(2000, 1, 1, 0, 0)),
                    owner))
        .isInstanceOf(EditConflictException.class);
    // 404 — 남의 차트
    assertThatThrownBy(() -> chartService.delete(c.id(), other))
        .isInstanceOf(ChartNotFoundException.class);

    assertThat(audits(owner).stream().filter(a -> a.resource().equals("chart")))
        .extracting(Audit::action)
        .containsExactly("CREATE");
    assertThat(audits(other)).isEmpty();
  }

  // ── 대시보드 ──────────────────────────────────────────────────────────────

  @Test
  void dashboard_createUpdateDelete_recorded_andNoopSaveWritesNothing() {
    DashboardResponse d =
        dashboardService.create(
            new CreateDashboardRequest("감사 대시보드", MARKER + " 설명", false, null), owner);
    dashboardService.update(d.id(), new UpdateDashboardRequest(null, null, null, 30, null), owner);
    // 웹 설정 창처럼 모든 항목을 같은 값으로 보내는 저장 — 남지 않는다
    dashboardService.update(
        d.id(), new UpdateDashboardRequest("감사 대시보드", MARKER + " 설명", false, 30, null), owner);
    dashboardService.delete(d.id(), owner);

    List<Audit> rows = audits(owner);
    assertThat(rows).extracting(Audit::action).containsExactly("CREATE", "UPDATE", "DELETE");
    assertThat(rows).extracting(Audit::resource).containsOnly("dashboard");
    assertThat(rows.get(0).description()).isEqualTo("대시보드 생성: 감사 대시보드");
    assertThat(rows.get(1).description()).isEqualTo("대시보드 수정: 감사 대시보드 (바뀐 항목: 자동 새로고침)");
    assertThat(changedFields(rows.get(1))).containsExactly("autoRefreshSeconds");
    assertThat(rows.get(2).description()).isEqualTo("대시보드 삭제: 감사 대시보드");
    assertNoLeak(rows);
  }

  /** 대시보드 공유는 작성자 본인 위젯 차트·쿼리도 함께 공유한다(WD-194) — 그 공유도 각각 감사에 남고, 재저장은 다시 남기지 않는다. */
  @Test
  void dashboard_share_recordsDashboardAndCascadedChartQueryShares_once() {
    SavedQueryResponse q = createQuery("위젯 쿼리", false);
    ChartResponse c = createChart("위젯 차트", q.id(), false);
    DashboardResponse d =
        dashboardService.create(new CreateDashboardRequest("공유 대시보드", null, false, null), owner);
    dashboardService.addWidget(d.id(), new AddWidgetRequest(c.id(), 0, 0, 6, 4), owner);

    dashboardService.update(
        d.id(), new UpdateDashboardRequest(null, null, true, null, null), owner);
    // 웹 설정 창은 이름만 바꿔도 isShared=true 를 늘 보낸다 — 이미 공유된 차트·쿼리는 다시 남지 않는다
    dashboardService.update(
        d.id(), new UpdateDashboardRequest("공유 대시보드2", null, true, null, null), owner);
    dashboardService.update(
        d.id(), new UpdateDashboardRequest(null, null, false, null, null), owner);

    List<Audit> shareRows =
        audits(owner).stream().filter(a -> a.action().equals("STATUS_CHANGE")).toList();
    assertThat(shareRows)
        .extracting(Audit::description)
        .containsExactly(
            "대시보드 공유 켜기: 공유 대시보드",
            "차트 공유 켜기: 위젯 차트 (대시보드 공유에 따라: 공유 대시보드)",
            "저장 쿼리 공유 켜기: 위젯 쿼리 (대시보드 공유에 따라: 공유 대시보드)",
            "대시보드 공유 끄기: 공유 대시보드2");
    assertThat(shareRows.get(1).resource()).isEqualTo("chart");
    assertThat(shareRows.get(1).resourceId()).isEqualTo(String.valueOf(c.id()));
    assertThat(shareRows.get(1).metadata().get("viaDashboardId").asLong()).isEqualTo(d.id());
    assertThat(shareRows.get(2).resource()).isEqualTo("saved_query");
    assertThat(shareRows.get(2).resourceId()).isEqualTo(String.valueOf(q.id()));
    assertThat(
            audits(owner).stream()
                .filter(a -> a.action().equals("UPDATE") && a.resource().equals("dashboard")))
        .extracting(Audit::description)
        .containsExactly("대시보드 수정: 공유 대시보드2 (바뀐 항목: 이름)");
    assertNoLeak(audits(owner));
  }

  /** 이미 공유된 대시보드에 위젯을 넣으면 작성자 본인 차트·쿼리가 함께 공유된다(WD-195) — 그 공유도 각각 감사에 남는다. */
  @Test
  void addWidget_onSharedDashboard_recordsCascadedChartQueryShares() {
    SavedQueryResponse q = createQuery("추가 위젯 쿼리", false);
    ChartResponse c = createChart("추가 위젯 차트", q.id(), false);
    DashboardResponse d =
        dashboardService.create(new CreateDashboardRequest("이미 공유", null, true, null), owner);

    dashboardService.addWidget(d.id(), new AddWidgetRequest(c.id(), 0, 0, 6, 4), owner);

    List<Audit> shareRows =
        audits(owner).stream().filter(a -> a.action().equals("STATUS_CHANGE")).toList();
    assertThat(shareRows)
        .extracting(Audit::description)
        .containsExactly(
            "차트 공유 켜기: 추가 위젯 차트 (대시보드 공유에 따라: 이미 공유)",
            "저장 쿼리 공유 켜기: 추가 위젯 쿼리 (대시보드 공유에 따라: 이미 공유)");
    assertThat(shareRows).extracting(Audit::resource).containsExactly("chart", "saved_query");
    assertThat(shareRows)
        .extracting(a -> a.metadata().get("viaDashboardId").asLong())
        .containsOnly(d.id());
    assertNoLeak(audits(owner));
  }

  @Test
  void dashboard_rejectedRequests_writeNothing() {
    DashboardResponse d =
        dashboardService.create(new CreateDashboardRequest("거부 대시보드", null, false, null), owner);

    assertThatThrownBy(
            () ->
                dashboardService.update(
                    d.id(),
                    new UpdateDashboardRequest(
                        "바뀌면 안 됨", null, true, null, null, LocalDateTime.of(2000, 1, 1, 0, 0)),
                    owner))
        .isInstanceOf(EditConflictException.class);
    assertThatThrownBy(() -> dashboardService.delete(d.id(), other))
        .isInstanceOf(DashboardNotFoundException.class);

    assertThat(audits(owner)).extracting(Audit::action).containsExactly("CREATE");
    assertThat(audits(other)).isEmpty();
  }

  /**
   * 대시보드 생성 감사(CREATE·dashboard)는 홈 활동 피드에 실리지 않는다 — 피드는 조회자 가시성을 보지 않아, 실리면 비공개 대시보드 생성이 모든 사람 피드에
   * 「대시보드 생성됨」으로 보인다(WD-464).
   */
  @Test
  void dashboardCreateAudit_isNotShownInHomeActivityFeed() {
    dashboardService.create(new CreateDashboardRequest("비공개 대시보드", null, false, null), owner);
    assertThat(audits(owner)).extracting(Audit::action).containsExactly("CREATE");

    ActivityFeedResponse feed = homeDashboardService.getActivityFeed(null, null, 0, 500);

    assertThat(feed.items())
        .noneMatch(
            i -> "DASHBOARD_CREATED".equals(i.eventType()) || "DASHBOARD".equals(i.entityType()));
  }
}
