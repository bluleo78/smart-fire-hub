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
import org.jooq.Record1;
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

  /**
   * 목록·건수가 함께 쓰는 소유/공유 범위 조건 (WD-328). 목록과 건수가 어긋나지 않도록 한 곳에 둔다.
   *
   * <ul>
   *   <li>mineOnly=true — 내가 만든 대시보드만. 「내 대시보드」 탭에 남의 공유 대시보드가 섞이지 않게 한다
   *   <li>sharedOnly=true — 공유된 대시보드만(내 공유 대시보드 포함). 「공유됨」 탭
   *   <li>둘 다 true — 별도 의미를 만들지 않고 두 조건을 AND 로 결합(내가 만든 공유 대시보드)
   *   <li>둘 다 없음 — 기존 {@link #visibleTo} 그대로. AI 에이전트 MCP 도구 등 다른 호출자 호환
   * </ul>
   */
  private static Condition listScope(Boolean sharedOnly, Boolean mineOnly, Long userId) {
    boolean shared = Boolean.TRUE.equals(sharedOnly);
    boolean mine = Boolean.TRUE.equals(mineOnly);
    if (!shared && !mine) {
      return visibleTo(userId);
    }
    Condition scope = trueCondition();
    if (shared) {
      scope = scope.and(D_IS_SHARED.isTrue());
    }
    if (mine) {
      scope = scope.and(D_CREATED_BY.eq(userId));
    }
    return scope;
  }

  public List<DashboardResponse> findAll(
      String search, Boolean sharedOnly, Boolean mineOnly, Long userId, int page, int size) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(listScope(sharedOnly, mineOnly, userId));

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

  public long countAll(String search, Boolean sharedOnly, Boolean mineOnly, Long userId) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(listScope(sharedOnly, mineOnly, userId));

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

  /**
   * 소유자 대시보드 행을 {@code SELECT ... FOR UPDATE} 로 잠근다(WD-202). 행이 없거나 남의 것이면 false.
   *
   * <p>위젯 추가는 "개수 세기 → 같은 차트 확인 → 삽입" 순서라, 잠금 없이 동시에 들어오면 모두 같은 개수를 보고 통과해 한도(20개)를 넘기거나 같은 차트가 겹쳐
   * 들어갔다. 대시보드 행을 잠가 같은 대시보드의 위젯 추가를 한 줄로 세운다 — 호출부 트랜잭션이 끝날 때 풀린다. user 를 조인하는 {@link
   * #findByIdForOwner} 에 걸면 사용자 행까지 잠기므로 dashboard 만 읽는 별도 쿼리로 둔다. 테넌트 범위는 다른 쿼리와 같이 RLS 가 건다.
   */
  public boolean lockByIdForOwner(Long id, Long userId) {
    return dsl.select(D_ID)
        .from(D)
        .where(D_ID.eq(id).and(D_CREATED_BY.eq(userId)))
        .forUpdate()
        .fetchOptional()
        .isPresent();
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

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   */
  public Optional<Record1<LocalDateTime>> lockUpdatedAtById(Long id) {
    return dsl.select(D_UPDATED_AT).from(D).where(D_ID.eq(id)).forUpdate().fetchOptional();
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
