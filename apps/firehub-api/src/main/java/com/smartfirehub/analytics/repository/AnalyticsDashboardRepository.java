package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.global.util.LikePatternUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalyticsDashboardRepository {

  private final DSLContext dsl;

  // dashboard table
  private static final Table<?> D = table(name("dashboard"));
  private static final Field<Long> D_ID = field(name("dashboard", "id"), Long.class);
  private static final Field<String> D_NAME = field(name("dashboard", "name"), String.class);
  private static final Field<String> D_DESCRIPTION =
      field(name("dashboard", "description"), String.class);
  private static final Field<Boolean> D_IS_SHARED =
      field(name("dashboard", "is_shared"), Boolean.class);
  private static final Field<Integer> D_AUTO_REFRESH_SECONDS =
      field(name("dashboard", "auto_refresh_seconds"), Integer.class);
  private static final Field<Long> D_CREATED_BY =
      field(name("dashboard", "created_by"), Long.class);
  private static final Field<Long> D_UPDATED_BY =
      field(name("dashboard", "updated_by"), Long.class);
  private static final Field<LocalDateTime> D_CREATED_AT =
      field(name("dashboard", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> D_UPDATED_AT =
      field(name("dashboard", "updated_at"), LocalDateTime.class);

  // dashboard_widget table (for count)
  private static final Table<?> DW = table(name("dashboard_widget"));
  private static final Field<Long> DW_DASHBOARD_ID =
      field(name("dashboard_widget", "dashboard_id"), Long.class);
  private static final Field<Long> DW_CHART_ID =
      field(name("dashboard_widget", "chart_id"), Long.class);

  // user table
  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME_ALIAS =
      field(name("user", "name"), String.class).as("created_by_name");

  /**
   * 조회자에게 보이는 대시보드 — 본인 소유이거나 공유된 것. 목록·단건·삭제 영향 범위(WD-86)가 같은 규칙을 쓰도록 한 곳에 둔다.
   *
   * <p>공개인 이유(WD-143): 데이터셋 참조 집계({@code DatasetService#getReferences})도 대시보드 이름을 이 규칙으로 가린다 — 규칙을
   * 복사하면 한쪽만 바뀌는 순간 볼 수 없는 대시보드 이름이 새어 나간다. 조건은 unaliased {@code dashboard} 테이블을 가리킨다.
   */
  public static Condition visibleTo(Long userId) {
    return D_CREATED_BY.eq(userId).or(D_IS_SHARED.isTrue());
  }

  /**
   * 주어진 차트들을 위젯으로 쓰는 대시보드와 대시보드별 위젯 수(WD-86 삭제 영향 범위). 차트를 지우면 CASCADE 가 대시보드 가시성과 무관하게 위젯을 전부 지우므로
   * 사용자 필터를 걸지 않고, 대신 visible 을 함께 돌려 호출자가 볼 수 없는 이름을 버리게 한다. 위젯 수와 대시보드 목록을 한 쿼리(대시보드별 GROUP BY)로
   * 얻어 두 값이 서로 어긋나지 않게 한다. RLS 가 테넌트 범위를 한정하므로 트랜잭션 안에서 불러야 한다.
   */
  public List<DeleteImpactResponse.DashboardRawRef> findImpactByChartIds(
      Collection<Long> chartIds, Long userId) {
    if (chartIds.isEmpty()) {
      return List.of();
    }
    Field<Boolean> visible = field(visibleTo(userId));
    Field<Integer> widgetCount = count().as("widget_count");
    return dsl.select(D_ID, D_NAME, visible, widgetCount)
        .from(DW)
        .join(D)
        .on(DW_DASHBOARD_ID.eq(D_ID))
        .where(DW_CHART_ID.in(chartIds))
        .groupBy(D_ID, D_NAME, D_CREATED_BY, D_IS_SHARED)
        .orderBy(D_NAME.asc(), D_ID.asc())
        .fetch(
            r ->
                new DeleteImpactResponse.DashboardRawRef(
                    r.value1(), r.value2(), Boolean.TRUE.equals(r.value3()), r.value4()));
  }

  public List<DashboardResponse> findAll(
      String search, Boolean sharedOnly, Long userId, int page, int size) {
    List<Condition> conditions = new ArrayList<>();
    if (Boolean.TRUE.equals(sharedOnly)) {
      conditions.add(D_IS_SHARED.isTrue());
    } else {
      conditions.add(visibleTo(userId));
    }

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          D_NAME.likeIgnoreCase(pattern, '\\').or(D_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    Field<Integer> widgetCountField =
        field(select(count()).from(DW).where(DW_DASHBOARD_ID.eq(D_ID))).as("widget_count");

    var records =
        dsl.select(
                D_ID,
                D_NAME,
                D_DESCRIPTION,
                D_IS_SHARED,
                D_AUTO_REFRESH_SECONDS,
                U_NAME_ALIAS,
                D_CREATED_BY,
                D_CREATED_AT,
                D_UPDATED_AT,
                widgetCountField)
            .from(D)
            .join(USER_TABLE)
            .on(D_CREATED_BY.eq(U_ID))
            .where(combined)
            .orderBy(D_UPDATED_AT.desc())
            .limit(size)
            .offset(page * size)
            .fetch();

    List<DashboardResponse> result = new ArrayList<>();
    for (Record r : records) {
      int wc = r.get("widget_count", Integer.class);
      result.add(mapToResponse(r, List.of(), wc));
    }
    return result;
  }

  public long countAll(String search, Boolean sharedOnly, Long userId) {
    List<Condition> conditions = new ArrayList<>();
    if (Boolean.TRUE.equals(sharedOnly)) {
      conditions.add(D_IS_SHARED.isTrue());
    } else {
      conditions.add(visibleTo(userId));
    }

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          D_NAME.likeIgnoreCase(pattern, '\\').or(D_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    return dsl.selectCount().from(D).where(combined).fetchOne(0, Long.class);
  }

  public Optional<DashboardResponse> findById(
      Long id, Long userId, List<DashboardResponse.DashboardWidgetResponse> widgets) {
    Record r =
        dsl.select(
                D_ID,
                D_NAME,
                D_DESCRIPTION,
                D_IS_SHARED,
                D_AUTO_REFRESH_SECONDS,
                U_NAME_ALIAS,
                D_CREATED_BY,
                D_CREATED_AT,
                D_UPDATED_AT)
            .from(D)
            .join(USER_TABLE)
            .on(D_CREATED_BY.eq(U_ID))
            .where(D_ID.eq(id).and(visibleTo(userId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r, widgets, widgets.size()));
  }

  public Optional<DashboardResponse> findByIdForOwner(Long id, Long userId) {
    Record r =
        dsl.select(
                D_ID,
                D_NAME,
                D_DESCRIPTION,
                D_IS_SHARED,
                D_AUTO_REFRESH_SECONDS,
                U_NAME_ALIAS,
                D_CREATED_BY,
                D_CREATED_AT,
                D_UPDATED_AT)
            .from(D)
            .join(USER_TABLE)
            .on(D_CREATED_BY.eq(U_ID))
            .where(D_ID.eq(id).and(D_CREATED_BY.eq(userId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r, List.of(), 0));
  }

  public Long insert(CreateDashboardRequest req, Long userId) {
    return dsl.insertInto(D)
        .set(D_NAME, req.name())
        .set(D_DESCRIPTION, req.description())
        .set(D_IS_SHARED, req.isShared())
        .set(D_AUTO_REFRESH_SECONDS, req.autoRefreshSeconds())
        .set(D_CREATED_BY, userId)
        .returning(D_ID)
        .fetchOne()
        .get(D_ID);
  }

  public void update(Long id, UpdateDashboardRequest req, Long userId) {
    var update = dsl.update(D).set(D_UPDATED_BY, userId).set(D_UPDATED_AT, LocalDateTime.now());

    if (req.name() != null) update = update.set(D_NAME, req.name());
    // 설명: 생략(null)은 "변경 없음", 빈 문자열(공백만 포함)은 "설명을 지움"으로 해석해 DB 값을 NULL로 비운다(WD-197).
    // 웹이 지운 설명을 빈 문자열로 보내도 이전에는 null과 같이 무시돼 옛 설명이 그대로 남았다.
    if (req.description() != null)
      update = update.set(D_DESCRIPTION, blankToNull(req.description()));
    if (req.isShared() != null) update = update.set(D_IS_SHARED, req.isShared());
    if (req.autoRefreshSeconds() != null) {
      update = update.set(D_AUTO_REFRESH_SECONDS, req.autoRefreshSeconds());
    } else if (Boolean.TRUE.equals(req.clearAutoRefresh())) {
      // 프론트가 자동 새로고침 값을 지워 "수동"으로 되돌리려는 명시적 요청(#568).
      // autoRefreshSeconds만으로는 "미제공"과 "null로 지움"을 구분할 수 없어
      // clearAutoRefresh 플래그로 의도를 명확히 전달받는다.
      update = update.set(D_AUTO_REFRESH_SECONDS, (Integer) null);
    }

    update.where(D_ID.eq(id)).execute();
  }

  public boolean deleteById(Long id, Long userId) {
    int deleted = dsl.deleteFrom(D).where(D_ID.eq(id).and(D_CREATED_BY.eq(userId))).execute();
    return deleted > 0;
  }

  private DashboardResponse mapToResponse(
      Record r, List<DashboardResponse.DashboardWidgetResponse> widgets, int widgetCount) {
    return new DashboardResponse(
        r.get(D_ID),
        r.get(D_NAME),
        r.get(D_DESCRIPTION),
        Boolean.TRUE.equals(r.get(D_IS_SHARED)),
        r.get(D_AUTO_REFRESH_SECONDS),
        widgets,
        widgetCount,
        r.get("created_by_name", String.class),
        r.get(D_CREATED_BY),
        r.get(D_CREATED_AT),
        r.get(D_UPDATED_AT));
  }

  /**
   * 갱신 요청의 빈 문자열(공백만 포함)을 NULL 로 바꾼다(WD-197).
   *
   * <p>갱신 API 는 null 을 "변경 없음"으로 쓰므로, 사용자가 값을 지웠다는 뜻은 빈 문자열로 받는다. 저장할 때는 "값 없음"을 NULL 한 가지로 맞춰
   * 목록·상세가 "" 와 NULL 을 따로 다루지 않게 한다.
   */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
