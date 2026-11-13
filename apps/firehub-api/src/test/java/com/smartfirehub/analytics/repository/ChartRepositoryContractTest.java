package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ChartRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-710).
 *
 * <p>chart 는 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트로 감싼다. 공유 가시성(Review Focus 4): 남의 비공유 차트는 목록·건수·단건·SQL
 * 조회·{@code findVisibleIds} 에 안 나오고 공유된 것은 나온다, 삭제 영향 조회는 모두 싣되 visible 로 가린다, {@code shareCharts}
 * 는 이번에 실제로 공유로 바뀐 내 차트만 돌려준다. config JSON 은 값 그대로 왕복한다(Tibero JSON 이 실수 표기를 바꿀 수 있어 정수·문자열·불리언·중첩만
 * 쓴다). 차트를 지우면 위젯은 DB FK CASCADE 로 사라진다.
 */
public abstract class ChartRepositoryContractTest extends TenantPairContractBase {

  protected abstract ChartRepository repository();

  /** 조회자 겸 주인. */
  private long me;

  /** 다른 사용자(같은 테넌트). */
  private long other;

  /** 테넌트 A 의 저장 쿼리 둘 — 차트가 참조한다. */
  private long query;

  private long query2;

  @BeforeEach
  void createUsersAndQueries() {
    me = fx.createUser(prefix + "-me", null);
    fx.setUserName(me, prefix + "-me-name");
    other = fx.createUser(prefix + "-other", null);
    fx.setUserName(other, prefix + "-other-name");
    query = fx.createSavedQuery(tenantA, me, prefix + "-sq", false, null);
    query2 = fx.createSavedQuery(tenantA, me, prefix + "-sq2", true, null);
  }

  private static Map<String, Object> config() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("x", "월");
    nested.put("y", List.of("매출", "매입"));
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("xAxis", nested);
    c.put("stacked", true);
    c.put("limit", 25);
    c.put("title", "한글 제목");
    c.put("note", "가".repeat(1000));
    return c;
  }

  private CreateChartRequest req(String name, long savedQueryId, String type, boolean shared) {
    return new CreateChartRequest(name, null, savedQueryId, type, Map.of("k", 1), shared);
  }

  private Long insertA(long userId, CreateChartRequest req) {
    return inA(() -> repository().insert(req, userId));
  }

  private List<Long> listIds(
      String search,
      String type,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      int page,
      int size) {
    return inA(
            () ->
                repository()
                    .findAll(search, type, savedQueryId, sharedOnly, mineOnly, me, page, size))
        .stream()
        .map(ChartResponse::id)
        .toList();
  }

  private long count(
      String search, String type, Long savedQueryId, Boolean sharedOnly, Boolean mineOnly) {
    return inA(() -> repository().countAll(search, type, savedQueryId, sharedOnly, mineOnly, me));
  }

  // ---- insert·단건 ----

  @Test
  @DisplayName(
      "insert·findById·findByIdForOwner — 모든 필드·config JSON 이 왕복하고 만든이·저장 쿼리 이름·DB 시계 시각·위젯 수가 실린다")
  void insert_roundTripsAllFields() {
    Long id =
        insertA(me, new CreateChartRequest(prefix + "-c1", "설명 한글", query, "LINE", config(), true));
    long dash = fx.createDashboard(tenantA, me, false);
    long dash2 = fx.createDashboard(tenantA, other, true);
    fx.addWidget(tenantA, dash, id);
    fx.addWidget(tenantA, dash2, id);

    ChartResponse r = inA(() -> repository().findById(id, me)).orElseThrow();
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.name()).isEqualTo(prefix + "-c1");
    assertThat(r.description()).isEqualTo("설명 한글");
    assertThat(r.savedQueryId()).isEqualTo(query);
    assertThat(r.savedQueryName()).isEqualTo(prefix + "-sq");
    assertThat(r.chartType()).isEqualTo("LINE");
    assertThat(r.config()).isEqualTo(config());
    assertThat(r.isShared()).isTrue();
    assertThat(r.createdByName()).isEqualTo(prefix + "-me-name");
    assertThat(r.createdBy()).isEqualTo(me);
    assertThat(Duration.between(r.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
    assertThat(r.updatedAt()).isNotNull();
    assertThat(r.dashboardCount()).isEqualTo(2);
    assertThat(r.configWithheld()).isFalse();
    assertThat(fx.chartUpdatedBy(id)).isNull();

    assertThat(inA(() -> repository().findByIdForOwner(id, me))).contains(r);
  }

  /**
   * 4000바이트를 넘는 config(약 6KB, 10KB 상한 안) — Tibero JSON_VALUE·VARCHAR2 4000바이트 경계를 넘는 값의 왕복
   * 확인용(WD-710 P2).
   */
  private static Map<String, Object> largeConfig(String tag) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("tag", tag);
    c.put("series", List.of("a".repeat(2000), "b".repeat(2000)));
    c.put("note", "c".repeat(2000));
    return c;
  }

  @Test
  @DisplayName("insert·update — 4000바이트를 넘는 config(약 6KB)도 단건·목록에서 그대로 왕복한다(WD-710 P2)")
  void largeConfig_roundTrips() {
    Map<String, Object> big = largeConfig("insert");
    Long id = insertA(me, new CreateChartRequest(prefix + "-big", null, query, "BAR", big, false));

    assertThat(inA(() -> repository().findById(id, me)).orElseThrow().config()).isEqualTo(big);
    assertThat(
            inA(() -> repository().findAll(prefix + "-big", null, null, null, null, me, 0, 10))
                .stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow()
                .config())
        .isEqualTo(big);

    Map<String, Object> bigger = largeConfig("update");
    runA(() -> repository().update(id, new UpdateChartRequest(null, null, null, bigger, null), me));
    assertThat(inA(() -> repository().findById(id, me)).orElseThrow().config()).isEqualTo(bigger);
  }

  @Test
  @DisplayName("insert — config 가 null·빈 맵이면 {} 로 저장돼 빈 맵으로 읽히고, 빈 설명은 DB 규칙대로 저장된다")
  void insert_emptyConfigAndDescription() {
    Long nullConfig =
        insertA(me, new CreateChartRequest(prefix + "-n", null, query, "BAR", null, false));
    Long emptyConfig =
        insertA(me, new CreateChartRequest(prefix + "-e", "", query, "PIE", Map.of(), false));

    ChartResponse n = inA(() -> repository().findById(nullConfig, me)).orElseThrow();
    assertThat(n.config()).isEmpty();
    assertThat(n.description()).isNull();
    assertThat(n.isShared()).isFalse();
    assertThat(n.dashboardCount()).isZero();
    ChartResponse e = inA(() -> repository().findById(emptyConfig, me)).orElseThrow();
    assertThat(e.config()).isEmpty();
    if (emptyStringIsNull()) {
      assertThat(e.description()).isNull();
    } else {
      assertThat(e.description()).isEmpty();
    }
  }

  @Test
  @DisplayName("findById — 주인과, 공유된 차트는 다른 사용자도 본다 / findByIdForOwner 는 주인만 / 다른 테넌트·없는 id 는 empty")
  void findById_visibility() {
    Long mine = insertA(me, req(prefix + "-mine", query, "BAR", false));
    Long othersPrivate = insertA(other, req(prefix + "-op", query, "BAR", false));
    Long othersShared = insertA(other, req(prefix + "-os", query, "BAR", true));

    assertThat(inA(() -> repository().findById(mine, me))).isPresent();
    assertThat(inA(() -> repository().findById(othersPrivate, me)))
        .as("남의 비공유 차트는 안 보인다")
        .isEmpty();
    assertThat(inA(() -> repository().findById(othersShared, me))).isPresent();
    assertThat(inA(() -> repository().findById(-1L, me))).isEmpty();

    assertThat(inA(() -> repository().findByIdForOwner(mine, me))).isPresent();
    assertThat(inA(() -> repository().findByIdForOwner(othersShared, me)))
        .as("공유돼도 주인이 아니면 empty")
        .isEmpty();

    assertThat(inB(() -> repository().findById(mine, me))).isEmpty();
    assertThat(inB(() -> repository().findByIdForOwner(mine, me))).isEmpty();
  }

  // ---- 목록·건수(WD-328) ----

  @Test
  @DisplayName("findAll·countAll — 기본(내 것+공유)·mineOnly·sharedOnly·둘 다 범위, 남의 비공유와 다른 테넌트는 빠진다")
  void list_scopes() {
    Long myPrivate = insertA(me, req(prefix + "-a", query, "BAR", false));
    Long myShared = insertA(me, req(prefix + "-b", query, "BAR", true));
    insertA(other, req(prefix + "-c", query, "BAR", false));
    Long othersShared = insertA(other, req(prefix + "-d", query, "BAR", true));
    long bQuery = fx.createSavedQuery(tenantB, me, prefix + "-bq", true, null);
    fx.createChart(tenantB, me, bQuery, true);

    assertThat(listIds(null, null, null, null, null, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared, othersShared);
    assertThat(count(null, null, null, null, null)).isEqualTo(3);
    assertThat(listIds(null, null, null, false, false, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared, othersShared);
    assertThat(count(null, null, null, false, false)).isEqualTo(3);

    assertThat(listIds(null, null, null, null, true, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared);
    assertThat(count(null, null, null, null, true)).isEqualTo(2);

    assertThat(listIds(null, null, null, true, null, 0, 50))
        .containsExactlyInAnyOrder(myShared, othersShared);
    assertThat(count(null, null, null, true, null)).isEqualTo(2);

    assertThat(listIds(null, null, null, true, true, 0, 50)).containsExactly(myShared);
    assertThat(count(null, null, null, true, true)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "findAll·countAll — 검색은 이름 또는 설명의 대소문자 무시 부분 일치(%·_ 는 글자 그대로), 종류·저장 쿼리는 정확히 일치, 빈 값은 거르지 않는다")
  void list_filters() {
    Long byName = insertA(me, req(prefix + "-Sales Chart", query, "BAR", false));
    Long byDesc =
        insertA(
            me, new CreateChartRequest(prefix + "-x", "월간 SALES 추이", query2, "LINE", null, false));
    Long literal = insertA(me, req(prefix + "-100%_done", query, "PIE", false));
    insertA(me, req(prefix + "-1000xdone", query2, "PIE", false));

    assertThat(listIds("sales", null, null, null, null, 0, 50))
        .containsExactlyInAnyOrder(byName, byDesc);
    assertThat(count("sales", null, null, null, null)).isEqualTo(2);
    assertThat(listIds("0%_d", null, null, null, null, 0, 50)).containsExactly(literal);
    assertThat(count("0%_d", null, null, null, null)).isEqualTo(1);
    assertThat(listIds(" ", " ", null, null, null, 0, 50)).hasSize(4);
    assertThat(count(" ", "", null, null, null)).isEqualTo(4);

    assertThat(listIds(null, "PIE", null, null, null, 0, 50)).hasSize(2);
    assertThat(count(null, "PIE", null, null, null)).isEqualTo(2);
    assertThat(listIds("sales", "LINE", null, null, null, 0, 50)).containsExactly(byDesc);
    assertThat(listIds(null, null, query2, null, null, 0, 50)).hasSize(2);
    assertThat(count(null, null, query2, null, null)).isEqualTo(2);
    assertThat(count(null, "PIE", query, null, null)).isEqualTo(1);
  }

  @Test
  @DisplayName("findAll — 저장 쿼리 이름·만든이 이름·위젯 수·config 가 목록에도 실린다")
  void list_carriesJoinedFields() {
    Long id =
        insertA(other, new CreateChartRequest(prefix + "-j", null, query2, "AREA", config(), true));
    fx.addWidget(tenantA, fx.createDashboard(tenantA, me, false), id);

    ChartResponse r =
        inA(() -> repository().findAll(null, null, null, null, null, me, 0, 10)).get(0);
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.savedQueryName()).isEqualTo(prefix + "-sq2");
    assertThat(r.createdByName()).isEqualTo(prefix + "-other-name");
    assertThat(r.dashboardCount()).isEqualTo(1);
    assertThat(r.config()).isEqualTo(config());
    assertThat(r).isEqualTo(inA(() -> repository().findById(id, me)).orElseThrow());
  }

  @Test
  @DisplayName("findAll — 수정 시각 내림차순, 같으면 id 내림차순으로 정렬하고 page·size 로 자른다")
  void list_orderAndPaging() {
    Long c1 = insertA(me, req(prefix + "-1", query, "BAR", false));
    Long c2 = insertA(me, req(prefix + "-2", query, "BAR", false));
    Long c3 = insertA(me, req(prefix + "-3", query, "BAR", false));
    Long c4 = insertA(me, req(prefix + "-4", query, "BAR", false));
    LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);
    fx.setChartUpdatedAt(c1, base.plusHours(1));
    fx.setChartUpdatedAt(c2, base);
    fx.setChartUpdatedAt(c3, base);
    fx.setChartUpdatedAt(c4, base.minusHours(1));

    assertThat(listIds(null, null, null, null, null, 0, 10)).containsExactly(c1, c3, c2, c4);
    assertThat(listIds(null, null, null, null, null, 0, 2)).containsExactly(c1, c3);
    assertThat(listIds(null, null, null, null, null, 1, 2)).containsExactly(c2, c4);
    assertThat(listIds(null, null, null, null, null, 2, 2)).isEmpty();
  }

  // ---- 삭제 영향·내부 조회 ----

  @Test
  @DisplayName(
      "findImpactRefsBySavedQueryId — 쿼리를 쓰는 차트를 모두 이름·id 오름차순으로, 보이는지(내 것·공유)는 visible 로 돌려준다")
  void impactRefs_allWithVisibility() {
    Long mineB = insertA(me, req(prefix + "-b", query, "BAR", false));
    Long othersPrivate = insertA(other, req(prefix + "-a", query, "BAR", false));
    Long othersShared = insertA(other, req(prefix + "-c", query, "BAR", true));
    Long tieFirst = insertA(other, req(prefix + "-d", query, "BAR", false));
    Long tieSecond = insertA(me, req(prefix + "-d", query, "BAR", true));
    insertA(me, req(prefix + "-z", query2, "BAR", false));

    List<DeleteImpactResponse.ChartRawRef> refs =
        inA(() -> repository().findImpactRefsBySavedQueryId(query, me));
    assertThat(refs)
        .extracting(
            DeleteImpactResponse.ChartRawRef::id,
            DeleteImpactResponse.ChartRawRef::name,
            DeleteImpactResponse.ChartRawRef::visible)
        .containsExactly(
            tuple(othersPrivate, prefix + "-a", false),
            tuple(mineB, prefix + "-b", true),
            tuple(othersShared, prefix + "-c", true),
            tuple(tieFirst, prefix + "-d", false),
            tuple(tieSecond, prefix + "-d", true));
    assertThat(inA(() -> repository().findImpactRefsBySavedQueryId(-1L, me))).isEmpty();
    assertThat(inB(() -> repository().findImpactRefsBySavedQueryId(query, me))).isEmpty();
  }

  @Test
  @DisplayName("findSavedQueryId·findSavedQuerySqlTextById — 소유와 무관하게 읽고, 없거나 다른 테넌트면 null·empty")
  void internalLookups_ignoreOwnership() {
    Long othersPrivate = insertA(other, req(prefix + "-op", query2, "BAR", false));

    assertThat(inA(() -> repository().findSavedQueryId(othersPrivate))).isEqualTo(query2);
    assertThat(inA(() -> repository().findSavedQueryId(-1L))).isNull();
    assertThat(inB(() -> repository().findSavedQueryId(othersPrivate))).isNull();

    assertThat(inA(() -> repository().findSavedQuerySqlTextById(query))).contains("SELECT 1");
    assertThat(inA(() -> repository().findSavedQuerySqlTextById(-1L))).isEmpty();
    assertThat(inB(() -> repository().findSavedQuerySqlTextById(query))).isEmpty();
  }

  @Test
  @DisplayName("findSavedQuerySqlText — 볼 수 있는(내 것·공유) 차트의 쿼리 SQL 만, 남의 비공유·다른 테넌트는 empty")
  void sqlText_visibility() {
    Long mine = insertA(me, req(prefix + "-m", query, "BAR", false));
    Long othersPrivate = insertA(other, req(prefix + "-op", query, "BAR", false));
    Long othersShared = insertA(other, req(prefix + "-os", query, "BAR", true));

    assertThat(inA(() -> repository().findSavedQuerySqlText(mine, me))).contains("SELECT 1");
    assertThat(inA(() -> repository().findSavedQuerySqlText(othersShared, me)))
        .contains("SELECT 1");
    assertThat(inA(() -> repository().findSavedQuerySqlText(othersPrivate, me))).isEmpty();
    assertThat(inA(() -> repository().findSavedQuerySqlText(-1L, me))).isEmpty();
    assertThat(inB(() -> repository().findSavedQuerySqlText(mine, me))).isEmpty();
  }

  // ---- 공유(Review Focus 4) ----

  @Test
  @DisplayName(
      "shareCharts — 내 비공유 차트만 공유로 바꾸고 바뀐 것만 id→이름(오름차순)으로 돌려준다(이미 공유·남의 것·다른 테넌트·없는 id 제외)")
  void shareCharts_returnsOnlyFlipped() {
    Long mine1 = insertA(me, req(prefix + "-m1", query, "BAR", false));
    Long mine2 = insertA(me, req(prefix + "-m2", query, "BAR", false));
    Long alreadyShared = insertA(me, req(prefix + "-s", query, "BAR", true));
    Long others = insertA(other, req(prefix + "-o", query, "BAR", false));
    long bQuery = fx.createSavedQuery(tenantB, me, prefix + "-bq", false, null);
    long tenantBMine = fx.createChart(tenantB, me, bQuery, false);

    Map<Long, String> flipped =
        inA(
            () ->
                repository()
                    .shareCharts(
                        List.of(mine2, alreadyShared, others, tenantBMine, -1L, mine1), me));

    assertThat(flipped)
        .containsExactly(Map.entry(mine1, prefix + "-m1"), Map.entry(mine2, prefix + "-m2"));
    assertThat(owned(mine1).isShared()).isTrue();
    assertThat(owned(mine2).isShared()).isTrue();
    assertThat(owned(alreadyShared).isShared()).isTrue();
    assertThat(inA(() -> repository().findByIdForOwner(others, other)).orElseThrow().isShared())
        .as("남의 차트는 그대로")
        .isFalse();
    assertThat(inB(() -> repository().findByIdForOwner(tenantBMine, me)).orElseThrow().isShared())
        .as("다른 테넌트 차트는 그대로")
        .isFalse();

    assertThat(inA(() -> repository().shareCharts(List.of(mine1, mine2), me)))
        .as("두 번째 호출은 바뀐 것이 없다")
        .isEmpty();
    assertThat(inA(() -> repository().shareCharts(List.of(), me))).isEmpty();
  }

  @Test
  @DisplayName("findVisibleIds — 내 것·공유된 차트 id 만, 남의 비공유·다른 테넌트·없는 id 는 빠진다, 빈 입력은 빈 집합")
  void findVisibleIds_filters() {
    Long mine = insertA(me, req(prefix + "-m", query, "BAR", false));
    Long othersPrivate = insertA(other, req(prefix + "-op", query, "BAR", false));
    Long othersShared = insertA(other, req(prefix + "-os", query, "BAR", true));
    long bQuery = fx.createSavedQuery(tenantB, me, prefix + "-bq", true, null);
    long tenantBShared = fx.createChart(tenantB, me, bQuery, true);

    assertThat(
            inA(
                () ->
                    repository()
                        .findVisibleIds(
                            List.of(mine, othersPrivate, othersShared, tenantBShared, -1L), me)))
        .containsExactlyInAnyOrder(mine, othersShared);
    assertThat(inA(() -> repository().findVisibleIds(List.of(), me))).isEmpty();
  }

  @Test
  @DisplayName("countOwnedBySharing — 내가 만든 차트만 [비공개 수, 공유 수] 로 센다, 빈 목록은 [0, 0]")
  void countOwnedBySharing_counts() {
    Long p1 = insertA(me, req(prefix + "-p1", query, "BAR", false));
    Long p2 = insertA(me, req(prefix + "-p2", query, "BAR", false));
    Long s1 = insertA(me, req(prefix + "-s1", query, "BAR", true));
    Long others = insertA(other, req(prefix + "-o", query, "BAR", true));
    long bQuery = fx.createSavedQuery(tenantB, me, prefix + "-bq", true, null);
    long tenantBMine = fx.createChart(tenantB, me, bQuery, true);

    assertThat(
            inA(
                () ->
                    repository()
                        .countOwnedBySharing(List.of(p1, p2, s1, others, tenantBMine, -1L), me)))
        .containsExactly(2, 1);
    assertThat(inA(() -> repository().countOwnedBySharing(List.of(s1), me))).containsExactly(0, 1);
    assertThat(inA(() -> repository().countOwnedBySharing(List.of(others), me)))
        .containsExactly(0, 0);
    assertThat(inA(() -> repository().countOwnedBySharing(List.of(), me))).containsExactly(0, 0);
  }

  @Test
  @DisplayName("findSavedQueryIdsByChartIds — 주어진 차트들이 쓰는 저장 쿼리 id 를 중복 없이, 다른 테넌트·없는 id 는 빠진다")
  void savedQueryIdsByChartIds_distinct() {
    Long a = insertA(me, req(prefix + "-a", query, "BAR", false));
    Long b = insertA(other, req(prefix + "-b", query, "BAR", false));
    Long c = insertA(me, req(prefix + "-c", query2, "BAR", false));
    long bQuery = fx.createSavedQuery(tenantB, me, prefix + "-bq", true, null);
    long tenantBChart = fx.createChart(tenantB, me, bQuery, true);

    assertThat(
            inA(
                () ->
                    repository().findSavedQueryIdsByChartIds(List.of(a, b, c, tenantBChart, -1L))))
        .containsExactlyInAnyOrder(query, query2);
    assertThat(inA(() -> repository().findSavedQueryIdsByChartIds(List.of(a, b))))
        .containsExactly(query);
    assertThat(inA(() -> repository().findSavedQueryIdsByChartIds(List.of()))).isEmpty();
  }

  // ---- 잠금·수정·삭제 ----

  @Test
  @DisplayName("lockUpdatedAtById — 저장된 수정 시각을 돌려주고, 없는 id·다른 테넌트는 empty")
  void lockUpdatedAtById_returnsStored() {
    Long id = insertA(me, req(prefix + "-l", query, "BAR", false));
    LocalDateTime stored = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123456000);
    fx.setChartUpdatedAt(id, stored);

    assertThat(inA(() -> repository().lockUpdatedAtById(id)))
        .contains(new LockedRowVersion(stored));
    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockUpdatedAtById(id))).isEmpty();
  }

  @Test
  @DisplayName("update — 넘긴 필드만 바꾸고 빈 설명은 NULL 로 비우며 config·수정자·수정 시각을 남긴다")
  void update_partialAndBlankToNull() {
    Long id =
        insertA(
            me, new CreateChartRequest(prefix + "-u", "설명", query, "BAR", Map.of("a", 1), false));
    LocalDateTime old = LocalDateTime.of(2026, 1, 1, 0, 0);
    fx.setChartUpdatedAt(id, old);

    runA(
        () ->
            repository()
                .update(
                    id,
                    new UpdateChartRequest(prefix + "-u2", "  ", "LINE", config(), true),
                    other));

    ChartResponse r = owned(id);
    assertThat(r.name()).isEqualTo(prefix + "-u2");
    assertThat(r.description()).isNull();
    assertThat(r.chartType()).isEqualTo("LINE");
    assertThat(r.config()).isEqualTo(config());
    assertThat(r.isShared()).isTrue();
    assertThat(r.savedQueryId()).isEqualTo(query);
    assertThat(r.updatedAt()).isAfter(old);
    assertThat(fx.chartUpdatedBy(id)).isEqualTo(other);

    // null 은 「변경 없음」 — 이름·종류·config·공유는 그대로, 설명은 새 값.
    runA(() -> repository().update(id, new UpdateChartRequest(null, "새 설명", null, null, null), me));
    ChartResponse r2 = owned(id);
    assertThat(r2.name()).isEqualTo(prefix + "-u2");
    assertThat(r2.description()).isEqualTo("새 설명");
    assertThat(r2.chartType()).isEqualTo("LINE");
    assertThat(r2.config()).isEqualTo(config());
    assertThat(r2.isShared()).isTrue();
    assertThat(fx.chartUpdatedBy(id)).isEqualTo(me);

    // 빈 맵 config 는 {} 로 덮어쓴다, 공유 끄기.
    runA(
        () ->
            repository().update(id, new UpdateChartRequest(null, null, null, Map.of(), false), me));
    ChartResponse r3 = owned(id);
    assertThat(r3.config()).isEmpty();
    assertThat(r3.isShared()).isFalse();
    assertThat(r3.description()).isEqualTo("새 설명");
  }

  @Test
  @DisplayName("update — 다른 테넌트의 행은 바뀌지 않는다")
  void update_otherTenantUntouched() {
    Long id = insertA(me, req(prefix + "-t", query, "BAR", false));

    inB(
        () -> {
          repository()
              .update(id, new UpdateChartRequest(prefix + "-hacked", null, null, null, null), me);
          return null;
        });

    assertThat(owned(id).name()).isEqualTo(prefix + "-t");
  }

  @Test
  @DisplayName(
      "deleteById — 주인만 지우고(위젯은 FK CASCADE 로 함께 사라진다) 지웠는지 돌려준다, 남의 것·다른 테넌트·없는 id 는 false")
  void deleteById_ownerOnlyAndCascadesWidgets() {
    Long id = insertA(me, req(prefix + "-del", query, "BAR", true));
    Long kept = insertA(me, req(prefix + "-keep", query, "BAR", true));
    long dash = fx.createDashboard(tenantA, me, false);
    long widget = fx.addWidget(tenantA, dash, id);
    long keptWidget = fx.addWidget(tenantA, dash, kept);

    assertThat(inA(() -> repository().deleteById(id, other))).isFalse();
    assertThat(inB(() -> repository().deleteById(id, me))).isFalse();
    assertThat(inA(() -> repository().findById(id, me))).isPresent();
    assertThat(fx.widgetExists(widget)).isTrue();

    assertThat(inA(() -> repository().deleteById(id, me))).isTrue();
    assertThat(inA(() -> repository().findById(id, me))).isEmpty();
    assertThat(fx.widgetExists(widget)).as("차트를 지우면 위젯도 FK CASCADE 로 사라진다").isFalse();
    assertThat(fx.widgetExists(keptWidget)).isTrue();
    assertThat(inA(() -> repository().deleteById(id, me))).isFalse();
  }

  private ChartResponse owned(Long id) {
    return inA(() -> repository().findByIdForOwner(id, me)).orElseThrow();
  }
}
