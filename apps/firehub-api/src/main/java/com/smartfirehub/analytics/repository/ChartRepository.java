package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.global.util.LikePatternUtils;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class ChartRepository {

  private final DSLContext dsl;

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

  public ChartRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ChartResponse> findAll(
      String search, String chartType, Long savedQueryId, Long userId, int page, int size) {

    var dashboardCountField =
        dsl.selectCount().from(DW).where(DW_CHART_ID.eq(C_ID)).asField("dashboard_count");

    List<Condition> conditions = new ArrayList<>();
    conditions.add(C_CREATED_BY.eq(userId).or(C_IS_SHARED.isTrue()));

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

  public long countAll(String search, String chartType, Long savedQueryId, Long userId) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(C_CREATED_BY.eq(userId).or(C_IS_SHARED.isTrue()));

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
            .where(C_ID.eq(id).and(C_CREATED_BY.eq(userId).or(C_IS_SHARED.isTrue())))
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
            .where(C_ID.eq(chartId).and(C_CREATED_BY.eq(userId).or(C_IS_SHARED.isTrue())))
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

  public void update(Long id, UpdateChartRequest req, Long userId) {
    var update = dsl.update(C).set(C_UPDATED_BY, userId).set(C_UPDATED_AT, LocalDateTime.now());

    if (req.name() != null) update = update.set(C_NAME, req.name());
    if (req.description() != null) update = update.set(C_DESCRIPTION, req.description());
    if (req.chartType() != null) update = update.set(C_CHART_TYPE, req.chartType());
    if (req.config() != null) update = update.set(C_CONFIG, JSON.valueOf(mapToJson(req.config())));
    if (req.isShared() != null) update = update.set(C_IS_SHARED, req.isShared());

    update.where(C_ID.eq(id)).execute();
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
        r.get("dashboard_count", Long.class));
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private String mapToJson(Map<String, Object> map) {
    if (map == null || map.isEmpty()) return "{}";
    try {
      return OBJECT_MAPPER.writeValueAsString(map);
    } catch (Exception e) {
      return "{}";
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseJson(String json) {
    // Delegate to Jackson via ObjectMapper if available, otherwise return raw wrapper
    try {
      com.fasterxml.jackson.databind.ObjectMapper om =
          new com.fasterxml.jackson.databind.ObjectMapper();
      return om.readValue(json, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }
}
