package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.DashboardResponse.DashboardWidgetResponse;
import com.smartfirehub.analytics.dto.DeleteImpactResponse.DashboardRawRef;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AnalyticsDashboardRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-710).
 *
 * <p>dashboard 는 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트로 감싼다. 공유 가시성(Review Focus 4): 남의 비공유 대시보드는
 * 목록·건수·단건에 안 나오고 공유된 것은 나온다, 삭제 영향 조회는 모두 싣되 visible 로 가린다. 목록 정렬은 수정 시각 내림차순, 같으면 id 내림차순(두 구현에
 * 같은 tiebreak). 대시보드를 지우면 위젯은 DB FK CASCADE 로 사라진다.
 */
public abstract class AnalyticsDashboardRepositoryContractTest extends TenantPairContractBase {

  protected abstract AnalyticsDashboardRepository repository();

  /** 조회자 겸 주인. */
  private long me;

  /** 다른 사용자(같은 테넌트). */
  private long other;

  /** 테넌트 A 의 저장 쿼리 — 위젯용 차트가 참조한다. */
  private long query;

  @BeforeEach
  void createUsers() {
    me = fx.createUser(prefix + "-me", null);
    fx.setUserName(me, prefix + "-me-name");
    other = fx.createUser(prefix + "-other", null);
    fx.setUserName(other, prefix + "-other-name");
    query = fx.createSavedQuery(tenantA, me, prefix + "-sq", true, null);
  }

  private CreateDashboardRequest req(String name, boolean shared) {
    return new CreateDashboardRequest(name, null, shared, null);
  }

  private Long insertA(long userId, CreateDashboardRequest req) {
    return inA(() -> repository().insert(req, userId));
  }

  private long chartA() {
    return fx.createChart(tenantA, me, query, true);
  }

  private List<Long> listIds(
      String search, Boolean sharedOnly, Boolean mineOnly, int page, int size) {
    return inA(() -> repository().findAll(search, sharedOnly, mineOnly, me, page, size)).stream()
        .map(DashboardResponse::id)
        .toList();
  }

  private long count(String search, Boolean sharedOnly, Boolean mineOnly) {
    return inA(() -> repository().countAll(search, sharedOnly, mineOnly, me));
  }

  private DashboardResponse owned(Long id) {
    return inA(() -> repository().findByIdForOwner(id, me)).orElseThrow();
  }

  // ---- insert·단건 ----

  @Test
  @DisplayName("insert·findById — 모든 필드가 왕복하고 만든이 이름·DB 시계 시각이 실리며, 위젯은 호출자가 넘긴 목록·개수 그대로다")
  void insert_roundTripsAllFields() {
    Long id = insertA(me, new CreateDashboardRequest(prefix + "-d1", "설명 한글", true, 60));
    List<DashboardWidgetResponse> widgets =
        List.of(
            new DashboardWidgetResponse(1L, 2L, "c", "BAR", 0, 0, 4, 3, true),
            new DashboardWidgetResponse(3L, 4L, "d", "PIE", 4, 0, 4, 3, true));

    DashboardResponse r = inA(() -> repository().findById(id, me, widgets)).orElseThrow();
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.name()).isEqualTo(prefix + "-d1");
    assertThat(r.description()).isEqualTo("설명 한글");
    assertThat(r.isShared()).isTrue();
    assertThat(r.autoRefreshSeconds()).isEqualTo(60);
    assertThat(r.widgets()).isEqualTo(widgets);
    assertThat(r.widgetCount()).isEqualTo(2);
    assertThat(r.createdByName()).isEqualTo(prefix + "-me-name");
    assertThat(r.createdBy()).isEqualTo(me);
    assertThat(Duration.between(r.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
    assertThat(r.updatedAt()).isNotNull();
    assertThat(fx.dashboardUpdatedBy(id)).isNull();
  }

  @Test
  @DisplayName("insert — 설명·자동 새로고침이 없으면 NULL, 빈 설명은 DB 규칙대로 저장된다")
  void insert_nullables() {
    Long n = insertA(me, req(prefix + "-n", false));
    Long e = insertA(me, new CreateDashboardRequest(prefix + "-e", "", false, null));

    DashboardResponse rn = owned(n);
    assertThat(rn.description()).isNull();
    assertThat(rn.autoRefreshSeconds()).isNull();
    assertThat(rn.isShared()).isFalse();
    if (emptyStringIsNull()) {
      assertThat(owned(e).description()).isNull();
    } else {
      assertThat(owned(e).description()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "findById — 주인과, 공유된 대시보드는 다른 사용자도 본다 / findByIdForOwner 는 주인만이고 위젯은 빈 목록·0개 / 다른 테넌트·없는 id 는 empty")
  void findById_visibility() {
    Long mine = insertA(me, req(prefix + "-mine", false));
    Long othersPrivate = insertA(other, req(prefix + "-op", false));
    Long othersShared = insertA(other, req(prefix + "-os", true));
    fx.addWidget(tenantA, mine, chartA());

    assertThat(inA(() -> repository().findById(mine, me, List.of()))).isPresent();
    assertThat(inA(() -> repository().findById(othersPrivate, me, List.of())))
        .as("남의 비공유 대시보드는 안 보인다")
        .isEmpty();
    assertThat(inA(() -> repository().findById(othersShared, me, List.of()))).isPresent();
    assertThat(inA(() -> repository().findById(-1L, me, List.of()))).isEmpty();

    DashboardResponse ownedRow = owned(mine);
    assertThat(ownedRow.widgets()).isEmpty();
    assertThat(ownedRow.widgetCount()).as("위젯이 있어도 소유자 단건은 0").isZero();
    assertThat(ownedRow.createdByName()).isEqualTo(prefix + "-me-name");
    assertThat(inA(() -> repository().findByIdForOwner(othersShared, me)))
        .as("공유돼도 주인이 아니면 empty")
        .isEmpty();

    assertThat(inB(() -> repository().findById(mine, me, List.of()))).isEmpty();
    assertThat(inB(() -> repository().findByIdForOwner(mine, me))).isEmpty();
  }

  // ---- 목록·건수(WD-328) ----

  @Test
  @DisplayName("findAll·countAll — 기본(내 것+공유)·mineOnly·sharedOnly·둘 다 범위, 남의 비공유와 다른 테넌트는 빠진다")
  void list_scopes() {
    Long myPrivate = insertA(me, req(prefix + "-a", false));
    Long myShared = insertA(me, req(prefix + "-b", true));
    insertA(other, req(prefix + "-c", false));
    Long othersShared = insertA(other, req(prefix + "-d", true));
    fx.createDashboard(tenantB, me, true);

    assertThat(listIds(null, null, null, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared, othersShared);
    assertThat(count(null, null, null)).isEqualTo(3);
    assertThat(listIds(null, false, false, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared, othersShared);
    assertThat(count(null, false, false)).isEqualTo(3);

    assertThat(listIds(null, null, true, 0, 50)).containsExactlyInAnyOrder(myPrivate, myShared);
    assertThat(count(null, null, true)).isEqualTo(2);

    assertThat(listIds(null, true, null, 0, 50)).containsExactlyInAnyOrder(myShared, othersShared);
    assertThat(count(null, true, null)).isEqualTo(2);

    assertThat(listIds(null, true, true, 0, 50)).containsExactly(myShared);
    assertThat(count(null, true, true)).isEqualTo(1);
  }

  @Test
  @DisplayName("findAll·countAll — 검색은 이름 또는 설명의 대소문자 무시 부분 일치(%·_ 는 글자 그대로), 빈 검색어는 거르지 않는다")
  void list_search() {
    Long byName = insertA(me, req(prefix + "-Sales Board", false));
    Long byDesc =
        insertA(me, new CreateDashboardRequest(prefix + "-x", "월간 SALES 추이", false, null));
    Long literal = insertA(me, req(prefix + "-100%_done", false));
    insertA(me, req(prefix + "-1000xdone", false));
    Long othersPrivate = insertA(other, req(prefix + "-sales-hidden", false));

    assertThat(listIds("sales", null, null, 0, 50)).containsExactlyInAnyOrder(byName, byDesc);
    assertThat(listIds("sales", null, null, 0, 50)).doesNotContain(othersPrivate);
    assertThat(count("sales", null, null)).isEqualTo(2);
    assertThat(listIds("0%_d", null, null, 0, 50)).containsExactly(literal);
    assertThat(count("0%_d", null, null)).isEqualTo(1);
    assertThat(listIds(" ", null, null, 0, 50)).hasSize(4);
    assertThat(count("", null, null)).isEqualTo(4);
    assertThat(listIds("sales", null, true, 0, 50)).containsExactlyInAnyOrder(byName, byDesc);
  }

  @Test
  @DisplayName("findAll — 만든이 이름·위젯 수(상관 서브쿼리)가 목록에 실리고 위젯 목록은 비어 있다")
  void list_carriesJoinedFields() {
    Long id = insertA(other, new CreateDashboardRequest(prefix + "-j", "설명", true, 30));
    fx.addWidget(tenantA, id, chartA());
    fx.addWidget(tenantA, id, chartA());

    // 목록의 첫 행이 아니라 방금 만든 대시보드 행을 id 로 골라 단언한다(WD-736 — 다른 행이 끼어도 안정).
    DashboardResponse r =
        inA(() -> repository().findAll(null, null, null, me, 0, 10)).stream()
            .filter(d -> java.util.Objects.equals(d.id(), id))
            .findFirst()
            .orElseThrow();
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.createdByName()).isEqualTo(prefix + "-other-name");
    assertThat(r.createdBy()).isEqualTo(other);
    assertThat(r.widgetCount()).isEqualTo(2);
    assertThat(r.widgets()).isEmpty();
    assertThat(r.description()).isEqualTo("설명");
    assertThat(r.autoRefreshSeconds()).isEqualTo(30);
    assertThat(r.isShared()).isTrue();
  }

  @Test
  @DisplayName("findAll — 수정 시각 내림차순, 같으면 id 내림차순으로 정렬하고 page·size 로 자른다")
  void list_orderAndPaging() {
    Long d1 = insertA(me, req(prefix + "-1", false));
    Long d2 = insertA(me, req(prefix + "-2", false));
    Long d3 = insertA(me, req(prefix + "-3", false));
    Long d4 = insertA(me, req(prefix + "-4", false));
    LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);
    fx.setDashboardUpdatedAt(d1, base.plusHours(1));
    fx.setDashboardUpdatedAt(d2, base);
    fx.setDashboardUpdatedAt(d3, base);
    fx.setDashboardUpdatedAt(d4, base.minusHours(1));

    assertThat(listIds(null, null, null, 0, 10)).containsExactly(d1, d3, d2, d4);
    assertThat(listIds(null, null, null, 0, 2)).containsExactly(d1, d3);
    assertThat(listIds(null, null, null, 1, 2)).containsExactly(d2, d4);
    assertThat(listIds(null, null, null, 2, 2)).isEmpty();
  }

  // ---- 삭제 영향(WD-86, Review Focus 4) ----

  @Test
  @DisplayName(
      "findImpactByChartIds — 대상 차트를 쓰는 대시보드를 모두 이름·id 오름차순으로, 대상 위젯 수와 보이는지(내 것·공유)를 함께 돌려준다")
  void impact_groupsAndVisibility() {
    long c1 = chartA();
    long c2 = chartA();
    long notTarget = chartA();
    Long mineB = insertA(me, req(prefix + "-b", false));
    Long othersPrivateA = insertA(other, req(prefix + "-a", false));
    Long othersSharedC = insertA(other, req(prefix + "-c", true));
    Long tieFirst = insertA(other, req(prefix + "-d", false));
    Long tieSecond = insertA(me, req(prefix + "-d", true));
    Long untouched = insertA(me, req(prefix + "-z", true));
    // 대상 둘을 쓰는 대시보드 → 2, 대상 하나 + 대상 아님 하나 → 1(WHERE 가 GROUP BY 앞), 대상 아님만 → 없음.
    fx.addWidget(tenantA, mineB, c1);
    fx.addWidget(tenantA, mineB, c2);
    fx.addWidget(tenantA, othersPrivateA, c1);
    fx.addWidget(tenantA, othersPrivateA, notTarget);
    fx.addWidget(tenantA, othersSharedC, c2);
    fx.addWidget(tenantA, tieFirst, c1);
    fx.addWidget(tenantA, tieSecond, c2);
    fx.addWidget(tenantA, untouched, notTarget);

    List<DashboardRawRef> refs =
        inA(() -> repository().findImpactByChartIds(List.of(c1, c2, -1L), me));
    assertThat(refs)
        .extracting(
            DashboardRawRef::id,
            DashboardRawRef::name,
            DashboardRawRef::visible,
            DashboardRawRef::widgetCount)
        .containsExactly(
            tuple(othersPrivateA, prefix + "-a", false, 1),
            tuple(mineB, prefix + "-b", true, 2),
            tuple(othersSharedC, prefix + "-c", true, 1),
            tuple(tieFirst, prefix + "-d", false, 1),
            tuple(tieSecond, prefix + "-d", true, 1));

    assertThat(inA(() -> repository().findImpactByChartIds(List.of(), me))).isEmpty();
    assertThat(inA(() -> repository().findImpactByChartIds(List.of(-1L), me))).isEmpty();
    assertThat(inB(() -> repository().findImpactByChartIds(List.of(c1, c2), me)))
        .as("다른 테넌트에서는 안 보인다")
        .isEmpty();
  }

  // ---- 잠금 ----

  @Test
  @DisplayName("lockByIdForOwner — 주인의 행이면 true, 남의 것·없는 id·다른 테넌트는 false")
  void lockByIdForOwner_ownerOnly() {
    Long mine = insertA(me, req(prefix + "-l", true));

    assertThat(inA(() -> repository().lockByIdForOwner(mine, me))).isTrue();
    assertThat(inA(() -> repository().lockByIdForOwner(mine, other))).as("공유돼도 남의 것").isFalse();
    assertThat(inA(() -> repository().lockByIdForOwner(-1L, me))).isFalse();
    assertThat(inB(() -> repository().lockByIdForOwner(mine, me))).isFalse();
  }

  @Test
  @DisplayName("lockUpdatedAtById — 저장된 수정 시각을 돌려주고(소유 무관), 없는 id·다른 테넌트는 empty")
  void lockUpdatedAtById_returnsStored() {
    Long id = insertA(other, req(prefix + "-v", false));
    LocalDateTime stored = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123456000);
    fx.setDashboardUpdatedAt(id, stored);

    assertThat(inA(() -> repository().lockUpdatedAtById(id)))
        .contains(new LockedRowVersion(stored));
    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockUpdatedAtById(id))).isEmpty();
  }

  // ---- 수정·삭제 ----

  @Test
  @DisplayName("update — 넘긴 필드만 바꾸고 빈 설명은 NULL 로 비우며 수정자·수정 시각을 늘 남긴다")
  void update_partialAndBlankToNull() {
    Long id = insertA(me, new CreateDashboardRequest(prefix + "-u", "설명", false, 60));
    LocalDateTime old = LocalDateTime.of(2026, 1, 1, 0, 0);
    fx.setDashboardUpdatedAt(id, old);

    runA(
        () ->
            repository()
                .update(
                    id, new UpdateDashboardRequest(prefix + "-u2", "  ", true, 120, null), other));
    DashboardResponse r = owned(id);
    assertThat(r.name()).isEqualTo(prefix + "-u2");
    assertThat(r.description()).isNull();
    assertThat(r.isShared()).isTrue();
    assertThat(r.autoRefreshSeconds()).isEqualTo(120);
    assertThat(r.updatedAt()).isAfter(old);
    assertThat(fx.dashboardUpdatedBy(id)).isEqualTo(other);

    // null 은 「변경 없음」 — 이름·공유·자동 새로고침은 그대로, 설명은 새 값, 수정 시각은 다시 남는다.
    fx.setDashboardUpdatedAt(id, old);
    runA(
        () ->
            repository()
                .update(id, new UpdateDashboardRequest(null, "새 설명", null, null, null), me));
    DashboardResponse r2 = owned(id);
    assertThat(r2.name()).isEqualTo(prefix + "-u2");
    assertThat(r2.description()).isEqualTo("새 설명");
    assertThat(r2.isShared()).isTrue();
    assertThat(r2.autoRefreshSeconds()).isEqualTo(120);
    assertThat(r2.updatedAt()).isAfter(old);
    assertThat(fx.dashboardUpdatedBy(id)).isEqualTo(me);
  }

  @Test
  @DisplayName("update — clearAutoRefresh 는 자동 새로고침을 NULL 로 비우고, 값과 함께 오면 값이 이긴다(#568)")
  void update_clearAutoRefresh() {
    Long id = insertA(me, new CreateDashboardRequest(prefix + "-r", null, false, 60));

    runA(() -> repository().update(id, new UpdateDashboardRequest(null, null, null, 90, true), me));
    assertThat(owned(id).autoRefreshSeconds()).as("값이 함께 오면 값").isEqualTo(90);

    runA(
        () ->
            repository().update(id, new UpdateDashboardRequest(null, null, false, null, true), me));
    DashboardResponse r = owned(id);
    assertThat(r.autoRefreshSeconds()).isNull();
    assertThat(r.isShared()).isFalse();

    runA(
        () ->
            repository().update(id, new UpdateDashboardRequest(null, null, null, null, false), me));
    assertThat(owned(id).autoRefreshSeconds()).isNull();
  }

  @Test
  @DisplayName("update — 다른 테넌트의 행은 바뀌지 않는다")
  void update_otherTenantUntouched() {
    Long id = insertA(me, req(prefix + "-t", false));

    inB(
        () -> {
          repository()
              .update(
                  id, new UpdateDashboardRequest(prefix + "-hacked", null, null, null, null), me);
          return null;
        });

    assertThat(owned(id).name()).isEqualTo(prefix + "-t");
    assertThat(fx.dashboardUpdatedBy(id)).isNull();
  }

  @Test
  @DisplayName(
      "deleteById — 주인만 지우고(위젯은 FK CASCADE 로 함께 사라진다) 지웠는지 돌려준다, 남의 것·다른 테넌트·없는 id 는 false")
  void deleteById_ownerOnlyAndCascadesWidgets() {
    Long id = insertA(me, req(prefix + "-del", true));
    Long kept = insertA(me, req(prefix + "-keep", true));
    long chart = chartA();
    long widget = fx.addWidget(tenantA, id, chart);
    long keptWidget = fx.addWidget(tenantA, kept, chart);

    assertThat(inA(() -> repository().deleteById(id, other))).isFalse();
    assertThat(inB(() -> repository().deleteById(id, me))).isFalse();
    assertThat(fx.widgetExists(widget)).isTrue();

    assertThat(inA(() -> repository().deleteById(id, me))).isTrue();
    assertThat(inA(() -> repository().findById(id, me, List.of()))).isEmpty();
    assertThat(fx.widgetExists(widget)).as("대시보드를 지우면 위젯도 FK CASCADE 로 사라진다").isFalse();
    assertThat(fx.widgetExists(keptWidget)).isTrue();
    assertThat(inA(() -> repository().deleteById(id, me))).isFalse();
  }
}
