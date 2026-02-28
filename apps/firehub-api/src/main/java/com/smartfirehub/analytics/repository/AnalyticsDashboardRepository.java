package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.global.util.LikePatternUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
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

  // user table
  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME_ALIAS =
      field(name("user", "name"), String.class).as("created_by_name");

  public AnalyticsDashboardRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<DashboardResponse> findAll(String search, Long userId, int page, int size) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(D_CREATED_BY.eq(userId).or(D_IS_SHARED.isTrue()));

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          D_NAME.likeIgnoreCase(pattern, '\\').or(D_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

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
                D_UPDATED_AT)
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
      result.add(mapToResponse(r, List.of()));
    }
    return result;
  }

  public long countAll(String search, Long userId) {
    List<Condition> conditions = new ArrayList<>();
    conditions.add(D_CREATED_BY.eq(userId).or(D_IS_SHARED.isTrue()));

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
            .where(D_ID.eq(id).and(D_CREATED_BY.eq(userId).or(D_IS_SHARED.isTrue())))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r, widgets));
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
    return Optional.of(mapToResponse(r, List.of()));
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
    if (req.description() != null) update = update.set(D_DESCRIPTION, req.description());
    if (req.isShared() != null) update = update.set(D_IS_SHARED, req.isShared());
    if (req.autoRefreshSeconds() != null) {
      update = update.set(D_AUTO_REFRESH_SECONDS, req.autoRefreshSeconds());
    }

    update.where(D_ID.eq(id)).execute();
  }

  public boolean deleteById(Long id, Long userId) {
    int deleted = dsl.deleteFrom(D).where(D_ID.eq(id).and(D_CREATED_BY.eq(userId))).execute();
    return deleted > 0;
  }

  private DashboardResponse mapToResponse(
      Record r, List<DashboardResponse.DashboardWidgetResponse> widgets) {
    return new DashboardResponse(
        r.get(D_ID),
        r.get(D_NAME),
        r.get(D_DESCRIPTION),
        Boolean.TRUE.equals(r.get(D_IS_SHARED)),
        r.get(D_AUTO_REFRESH_SECONDS),
        widgets,
        r.get("created_by_name", String.class),
        r.get(D_CREATED_BY),
        r.get(D_CREATED_AT),
        r.get(D_UPDATED_AT));
  }
}
