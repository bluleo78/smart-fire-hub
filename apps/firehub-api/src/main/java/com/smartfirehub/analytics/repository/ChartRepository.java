package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.global.util.LikePatternUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChartRepository {

  private final DSLContext dsl;

  /** 매 호출마다 ObjectMapper를 새로 생성하지 않도록 스프링 빈으로 주입받아 재사용 (#182) */
  private final ObjectMapper objectMapper;

  // chart table
  private static final Table<?> C = table(name("chart"));
  private static final Field<Long> C_ID = field(name("chart", "id"), Long.class);
  private static final Field<String> C_NAME = field(name("chart", "name"), String.class);
  private static final Field<String> C_DESCRIPTION =
      field(name("chart", "description"), String.class);
  private static final Field<Long> C_SAVED_QUERY_ID =
      field(name("chart", "saved_query_id"), Long.class);
  private static final Field<String> C_CHART_TYPE =
      field(name("chart", "chart_type"), String.class);
  private static final Field<JSON> C_CONFIG = field(name("chart", "config"), JSON.class);
  private static final Field<Boolean> C_IS_SHARED =
      field(name("chart", "is_shared"), Boolean.class);
  private static final Field<Long> C_CREATED_BY = field(name("chart", "created_by"), Long.class);
  private static final Field<Long> C_UPDATED_BY = field(name("chart", "updated_by"), Long.class);
  private static final Field<LocalDateTime> C_CREATED_AT =
      field(name("chart", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> C_UPDATED_AT =
      field(name("chart", "updated_at"), LocalDateTime.class);

  // saved_query table
  private static final Table<?> SQ = table(name("saved_query"));
  private static final Field<Long> SQ_ID = field(name("saved_query", "id"), Long.class);
  private static final Field<String> SQ_NAME =
      field(name("saved_query", "name"), String.class).as("saved_query_name");

  // user table
  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME_ALIAS =
      field(name("user", "name"), String.class).as("created_by_name");

  // dashboard_widget table (for count sub-query)
  private static final Table<?> DW = table(name("dashboard_widget"));
  private static final Field<Long> DW_CHART_ID =
      field(name("dashboard_widget", "chart_id"), Long.class);

  /** 조회자에게 보이는 차트 — 본인 소유이거나 공유된 것. 목록·단건·삭제 영향 범위(WD-86)가 같은 규칙을 쓰도록 한 곳에 둔다. */
  private static Condition visibleTo(Long userId) {
    return C_CREATED_BY.eq(userId).or(C_IS_SHARED.isTrue());
  }

  /**
   * 목록·건수가 함께 쓰는 소유/공유 범위 조건 (WD-328). 목록과 건수가 어긋나지 않도록 한 곳에 둔다.
   *
   * <ul>
   *   <li>mineOnly=true — 내가 만든 차트만. 「내 차트」 탭에 남의 공유 차트가 섞이지 않게 한다
   *   <li>sharedOnly=true — 공유된 차트만(내 공유 차트 포함). 「공유됨」 탭
   *   <li>둘 다 true — 별도 의미를 만들지 않고 두 조건을 AND 로 결합(내가 만든 공유 차트)
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
      scope = scope.and(C_IS_SHARED.isTrue());
    }
    if (mine) {
      scope = scope.and(C_CREATED_BY.eq(userId));
    }
    return scope;
  }

  public List<ChartResponse> findAll(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size) {

    var dashboardCountField =
        dsl.selectCount().from(DW).where(DW_CHART_ID.eq(C_ID)).asField("dashboard_count");

    List<Condition> conditions = new ArrayList<>();
    // 탭별 범위: mineOnly=내 차트만, sharedOnly=공유된 차트만, 둘 다 없으면 내 차트 + 공유된 차트
    conditions.add(listScope(sharedOnly, mineOnly, userId));

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          C_NAME.likeIgnoreCase(pattern, '\\').or(C_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    if (chartType != null && !chartType.isBlank()) {
      conditions.add(C_CHART_TYPE.eq(chartType));
    }

    if (savedQueryId != null) {
      conditions.add(C_SAVED_QUERY_ID.eq(savedQueryId));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    var records =
        dsl.select(
                C_ID,
                C_NAME,
                C_DESCRIPTION,
                C_SAVED_QUERY_ID,
                SQ_NAME,
                C_CHART_TYPE,
                C_CONFIG,
                C_IS_SHARED,
                U_NAME_ALIAS,
                C_CREATED_BY,
                C_CREATED_AT,
                C_UPDATED_AT,
                dashboardCountField)
            .from(C)
            .join(SQ)
            .on(C_SAVED_QUERY_ID.eq(SQ_ID))
            .join(USER_TABLE)
            .on(C_CREATED_BY.eq(U_ID))
            .where(combined)
            .orderBy(C_UPDATED_AT.desc())
            .limit(size)
            .offset(page * size)
            .fetch();

    List<ChartResponse> result = new ArrayList<>();
    for (Record r : records) {
      result.add(mapToResponse(r));
    }
    return result;
  }

  public long countAll(
      String search,
      String chartType,
      Long savedQueryId,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(listScope(sharedOnly, mineOnly, userId));

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          C_NAME.likeIgnoreCase(pattern, '\\').or(C_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    if (chartType != null && !chartType.isBlank()) {
      conditions.add(C_CHART_TYPE.eq(chartType));
    }

    if (savedQueryId != null) {
      conditions.add(C_SAVED_QUERY_ID.eq(savedQueryId));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    return dsl.selectCount().from(C).where(combined).fetchOne(0, Long.class);
  }

  public Optional<ChartResponse> findById(Long id, Long userId) {
    var dashboardCountField =
        dsl.selectCount().from(DW).where(DW_CHART_ID.eq(C_ID)).asField("dashboard_count");

    Record r =
        dsl.select(
                C_ID,
                C_NAME,
                C_DESCRIPTION,
                C_SAVED_QUERY_ID,
                SQ_NAME,
                C_CHART_TYPE,
                C_CONFIG,
                C_IS_SHARED,
                U_NAME_ALIAS,
                C_CREATED_BY,
                C_CREATED_AT,
                C_UPDATED_AT,
                dashboardCountField)
            .from(C)
            .join(SQ)
            .on(C_SAVED_QUERY_ID.eq(SQ_ID))
            .join(USER_TABLE)
            .on(C_CREATED_BY.eq(U_ID))
            .where(C_ID.eq(id).and(visibleTo(userId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r));
  }

  public Optional<ChartResponse> findByIdForOwner(Long id, Long userId) {
    var dashboardCountField =
        dsl.selectCount().from(DW).where(DW_CHART_ID.eq(C_ID)).asField("dashboard_count");

    Record r =
        dsl.select(
                C_ID,
                C_NAME,
                C_DESCRIPTION,
                C_SAVED_QUERY_ID,
                SQ_NAME,
                C_CHART_TYPE,
                C_CONFIG,
                C_IS_SHARED,
                U_NAME_ALIAS,
                C_CREATED_BY,
                C_CREATED_AT,
                C_UPDATED_AT,
                dashboardCountField)
            .from(C)
            .join(SQ)
            .on(C_SAVED_QUERY_ID.eq(SQ_ID))
            .join(USER_TABLE)
            .on(C_CREATED_BY.eq(U_ID))
            .where(C_ID.eq(id).and(C_CREATED_BY.eq(userId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r));
  }

  /**
   * 저장 쿼리를 참조하는 <b>모든</b> 차트를 가시성 판정과 함께 돌려준다(WD-86 삭제 영향 범위). 쿼리를 지우면 CASCADE 가 조회자의 가시성과 무관하게 전부
   * 지우므로 사용자 필터를 걸지 않고, 대신 목록 조회와 같은 규칙(본인 소유 또는 공유)으로 visible 을 계산한다 — 호출자가 볼 수 없는 이름을 버린다. RLS 가
   * 테넌트 범위를 한정하므로 트랜잭션 안에서 불러야 한다.
   */
  public List<DeleteImpactResponse.ChartRawRef> findImpactRefsBySavedQueryId(
      Long savedQueryId, Long userId) {
    Field<Boolean> visible = field(visibleTo(userId));
    return dsl.select(C_ID, C_NAME, visible)
        .from(C)
        .where(C_SAVED_QUERY_ID.eq(savedQueryId))
        .orderBy(C_NAME.asc(), C_ID.asc())
        .fetch(
            r ->
                new DeleteImpactResponse.ChartRawRef(
                    r.value1(), r.value2(), Boolean.TRUE.equals(r.value3())));
  }

  /** Returns the saved_query_id for a given chart (no user filter — internal use). */
  public Long findSavedQueryId(Long chartId) {
    return dsl.select(C_SAVED_QUERY_ID).from(C).where(C_ID.eq(chartId)).fetchOne(C_SAVED_QUERY_ID);
  }

  /** Returns raw saved_query sql_text by saved_query id (no user filter — internal use). */
  public Optional<String> findSavedQuerySqlTextById(Long savedQueryId) {
    Field<String> sqSqlText = field(name("saved_query", "sql_text"), String.class);
    Field<Long> sqId = field(name("saved_query", "id"), Long.class);
    String result = dsl.select(sqSqlText).from(SQ).where(sqId.eq(savedQueryId)).fetchOne(sqSqlText);
    return Optional.ofNullable(result);
  }

  /** Returns raw saved_query sql_text for a chart the user can access. */
  public Optional<String> findSavedQuerySqlText(Long chartId, Long userId) {
    Field<String> sqSqlText = field(name("saved_query", "sql_text"), String.class);

    String result =
        dsl.select(sqSqlText)
            .from(C)
            .join(SQ)
            .on(C_SAVED_QUERY_ID.eq(SQ_ID))
            .where(C_ID.eq(chartId).and(visibleTo(userId)))
            .fetchOne(sqSqlText);

    return Optional.ofNullable(result);
  }

  public Long insert(CreateChartRequest req, Long userId) {
    String configJson = mapToJson(req.config());
    return dsl.insertInto(C)
        .set(C_NAME, req.name())
        .set(C_DESCRIPTION, req.description())
        .set(C_SAVED_QUERY_ID, req.savedQueryId())
        .set(C_CHART_TYPE, req.chartType())
        .set(C_CONFIG, JSON.valueOf(configJson))
        .set(C_IS_SHARED, req.isShared())
        .set(C_CREATED_BY, userId)
        .returning(C_ID)
        .fetchOne()
        .get(C_ID);
  }

  /**
   * 수정 시각을 행 잠금(FOR UPDATE)과 함께 읽는다 — 편집 충돌(낙관적 잠금) 비교용(WD-364, 파이프라인 WD-353 과 같은 방식).
   *
   * <p>잠금 없이 읽고 비교하면 동시에 들어온 두 저장이 같은 값을 읽어 둘 다 통과한다. 잠금을 잡으면 나중 저장은 앞 저장이 커밋될 때까지 기다렸다가 바뀐 값을 읽어
   * 충돌로 거부된다. 소유자 확인을 마친 뒤에 부른다 — 먼저 부르면 남의 행에 대한 낡은 요청이 404 대신 409 를 받아 존재가 드러난다.
   */
  public Optional<Record1<LocalDateTime>> lockUpdatedAtById(Long id) {
    return dsl.select(C_UPDATED_AT).from(C).where(C_ID.eq(id)).forUpdate().fetchOptional();
  }

  public void update(Long id, UpdateChartRequest req, Long userId) {
    var update = dsl.update(C).set(C_UPDATED_BY, userId).set(C_UPDATED_AT, LocalDateTime.now());

    if (req.name() != null) update = update.set(C_NAME, req.name());
    // 설명: 생략(null)은 "변경 없음", 빈 문자열(공백만 포함)은 "설명을 지움"으로 해석해 DB 값을 NULL로 비운다(WD-197).
    // 웹이 지운 설명을 빈 문자열로 보내도 이전에는 null과 같이 무시돼 옛 설명이 그대로 남았다.
    if (req.description() != null)
      update = update.set(C_DESCRIPTION, blankToNull(req.description()));
    if (req.chartType() != null) update = update.set(C_CHART_TYPE, req.chartType());
    if (req.config() != null) update = update.set(C_CONFIG, JSON.valueOf(mapToJson(req.config())));
    if (req.isShared() != null) update = update.set(C_IS_SHARED, req.isShared());

    update.where(C_ID.eq(id)).execute();
  }

  /**
   * 주어진 차트 중 {@code ownerId} 가 만든 것만 공유로 바꾼다(WD-194).
   *
   * <p>대시보드를 공유할 때 위젯 차트를 함께 공유하는 용도다. 남이 만든 차트는 그 주인만 공유 여부를 정할 수 있으므로 작성자 조건 없이 바꾸면 남이 비공개로 되돌린
   * 차트가 내 대시보드 저장 한 번으로 주인 모르게 다시 공유된다. 그래서 소유자 인자를 필수로 받는다.
   *
   * <p>이미 공유된 차트는 건드리지 않고, 이번에 실제로 비공개 → 공유로 바뀐 차트의 id → 이름을 돌려준다 — 호출자가 그 차트마다 공유 켜기 감사를 남긴다
   * (WD-464). 결과는 true → true 갱신을 빼도 같다(공유 여부 외 컬럼은 바꾸지 않는다).
   */
  public Map<Long, String> shareCharts(List<Long> chartIds, Long ownerId) {
    if (chartIds.isEmpty()) return Map.of();
    Map<Long, String> flipped = new TreeMap<>();
    dsl.update(C)
        .set(C_IS_SHARED, true)
        .where(C_ID.in(chartIds).and(C_CREATED_BY.eq(ownerId)).and(C_IS_SHARED.isFalse()))
        .returning(C_ID, C_NAME)
        .fetch()
        .forEach(r -> flipped.put(r.get(C_ID), r.get(C_NAME)));
    return flipped;
  }

  /**
   * 주어진 차트 중 조회자에게 보이는(본인 소유이거나 공유된) 차트 id 만 돌려준다(WD-195).
   *
   * <p>대시보드 위젯 목록에서 볼 수 없는 차트의 이름·종류를 가리는 데 쓴다. 차트 단건·차트 데이터 경로와 같은 {@link #visibleTo} 규칙을 써서 "위젯
   * 목록에는 이름이 보이는데 데이터는 404" 같은 어긋남이 생기지 않게 한다. 위젯 수만큼이 아니라 한 번의 질의로 판정한다.
   */
  public Set<Long> findVisibleIds(Collection<Long> chartIds, Long userId) {
    if (chartIds.isEmpty()) return Set.of();
    return new HashSet<>(
        dsl.select(C_ID).from(C).where(C_ID.in(chartIds).and(visibleTo(userId))).fetch(C_ID));
  }

  /**
   * 주어진 차트 중 {@code ownerId} 가 만든 것을 공유 여부별로 센다(WD-325) — [비공개 수, 공유 수]. 대시보드 공유 안내 문구가 {@link
   * #shareCharts} 와 같은 대상(작성자 = 요청자)을 세도록 같은 조건을 쓴다.
   */
  public int[] countOwnedBySharing(List<Long> chartIds, Long ownerId) {
    if (chartIds.isEmpty()) return new int[] {0, 0};
    int[] counts = {0, 0};
    dsl.select(C_IS_SHARED, count())
        .from(C)
        .where(C_ID.in(chartIds).and(C_CREATED_BY.eq(ownerId)))
        .groupBy(C_IS_SHARED)
        .fetch()
        .forEach(r -> counts[Boolean.TRUE.equals(r.value1()) ? 1 : 0] = r.value2());
    return counts;
  }

  public List<Long> findSavedQueryIdsByChartIds(List<Long> chartIds) {
    if (chartIds.isEmpty()) return List.of();
    return dsl.selectDistinct(C_SAVED_QUERY_ID)
        .from(C)
        .where(C_ID.in(chartIds).and(C_SAVED_QUERY_ID.isNotNull()))
        .fetch(C_SAVED_QUERY_ID);
  }

  public boolean deleteById(Long id, Long userId) {
    int deleted = dsl.deleteFrom(C).where(C_ID.eq(id).and(C_CREATED_BY.eq(userId))).execute();
    return deleted > 0;
  }

  @SuppressWarnings("unchecked")
  private ChartResponse mapToResponse(Record r) {
    JSON configJson = r.get(C_CONFIG);
    Map<String, Object> config = parseJson(configJson != null ? configJson.data() : "{}");
    return new ChartResponse(
        r.get(C_ID),
        r.get(C_NAME),
        r.get(C_DESCRIPTION),
        r.get(C_SAVED_QUERY_ID),
        r.get("saved_query_name", String.class),
        r.get(C_CHART_TYPE),
        config,
        Boolean.TRUE.equals(r.get(C_IS_SHARED)),
        r.get("created_by_name", String.class),
        r.get(C_CREATED_BY),
        r.get(C_CREATED_AT),
        r.get(C_UPDATED_AT),
        r.get("dashboard_count", Long.class),
        false);
  }

  private String mapToJson(Map<String, Object> map) {
    if (map == null || map.isEmpty()) return "{}";
    try {
      return objectMapper.writeValueAsString(map);
    } catch (Exception e) {
      return "{}";
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseJson(String json) {
    // 주입받은 ObjectMapper 빈을 재사용하여 JSON 파싱 (#182)
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
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
