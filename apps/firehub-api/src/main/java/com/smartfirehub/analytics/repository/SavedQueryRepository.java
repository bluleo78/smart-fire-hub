package com.smartfirehub.analytics.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
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
public class SavedQueryRepository {

  private final DSLContext dsl;

  // saved_query table fields
  private static final Table<?> SQ = table(name("saved_query"));
  private static final Field<Long> SQ_ID = field(name("saved_query", "id"), Long.class);
  private static final Field<String> SQ_NAME = field(name("saved_query", "name"), String.class);
  private static final Field<String> SQ_DESCRIPTION =
      field(name("saved_query", "description"), String.class);
  private static final Field<String> SQ_SQL_TEXT =
      field(name("saved_query", "sql_text"), String.class);
  private static final Field<Long> SQ_DATASET_ID =
      field(name("saved_query", "dataset_id"), Long.class);
  private static final Field<String> SQ_FOLDER = field(name("saved_query", "folder"), String.class);
  private static final Field<Boolean> SQ_IS_SHARED =
      field(name("saved_query", "is_shared"), Boolean.class);
  private static final Field<Long> SQ_CREATED_BY =
      field(name("saved_query", "created_by"), Long.class);
  private static final Field<Long> SQ_UPDATED_BY =
      field(name("saved_query", "updated_by"), Long.class);
  private static final Field<LocalDateTime> SQ_CREATED_AT =
      field(name("saved_query", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> SQ_UPDATED_AT =
      field(name("saved_query", "updated_at"), LocalDateTime.class);

  // dataset table
  private static final Table<?> DS = table(name("dataset"));
  private static final Field<Long> DS_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> DS_NAME = field(name("dataset", "name"), String.class);

  // user table
  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  // chart table (for count sub-query)
  private static final Table<?> CHART = table(name("chart"));
  private static final Field<Long> CHART_SAVED_QUERY_ID =
      field(name("chart", "saved_query_id"), Long.class);

  // aliased fields for result mapping
  private static final Field<String> DS_NAME_ALIAS =
      field(name("dataset", "name"), String.class).as("dataset_name");
  private static final Field<String> U_NAME_ALIAS =
      field(name("user", "name"), String.class).as("created_by_name");

  public SavedQueryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<SavedQueryListResponse> findAll(
      String search, String folder, Boolean sharedOnly, Long userId, int page, int size) {

    var chartCountField =
        dsl.selectCount().from(CHART).where(CHART_SAVED_QUERY_ID.eq(SQ_ID)).asField("chart_count");

    List<Condition> conditions = new ArrayList<>();

    if (Boolean.TRUE.equals(sharedOnly)) {
      conditions.add(SQ_IS_SHARED.isTrue());
    } else {
      conditions.add(SQ_CREATED_BY.eq(userId).or(SQ_IS_SHARED.isTrue()));
    }

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          SQ_NAME.likeIgnoreCase(pattern, '\\').or(SQ_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    if (folder != null && !folder.isBlank()) {
      conditions.add(SQ_FOLDER.eq(folder));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    var records =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_FOLDER,
                SQ_DATASET_ID,
                DS_NAME_ALIAS,
                SQ_IS_SHARED,
                U_NAME_ALIAS,
                SQ_CREATED_AT,
                SQ_UPDATED_AT,
                chartCountField)
            .from(SQ)
            .leftJoin(DS)
            .on(SQ_DATASET_ID.eq(DS_ID))
            .join(USER_TABLE)
            .on(SQ_CREATED_BY.eq(U_ID))
            .where(combined)
            .orderBy(SQ_UPDATED_AT.desc())
            .limit(size)
            .offset(page * size)
            .fetch();

    List<SavedQueryListResponse> result = new ArrayList<>();
    for (Record r : records) {
      result.add(
          new SavedQueryListResponse(
              r.get(SQ_ID),
              r.get(SQ_NAME),
              r.get(SQ_DESCRIPTION),
              r.get(SQ_FOLDER),
              r.get(SQ_DATASET_ID),
              r.get("dataset_name", String.class),
              Boolean.TRUE.equals(r.get(SQ_IS_SHARED)),
              r.get("created_by_name", String.class),
              r.get(SQ_CREATED_AT),
              r.get(SQ_UPDATED_AT),
              r.get("chart_count", Long.class)));
    }
    return result;
  }

  public long countAll(String search, String folder, Boolean sharedOnly, Long userId) {
    List<Condition> conditions = new ArrayList<>();

    if (Boolean.TRUE.equals(sharedOnly)) {
      conditions.add(SQ_IS_SHARED.isTrue());
    } else {
      conditions.add(SQ_CREATED_BY.eq(userId).or(SQ_IS_SHARED.isTrue()));
    }

    if (search != null && !search.isBlank()) {
      String pattern = LikePatternUtils.containsPattern(search);
      conditions.add(
          SQ_NAME.likeIgnoreCase(pattern, '\\').or(SQ_DESCRIPTION.likeIgnoreCase(pattern, '\\')));
    }

    if (folder != null && !folder.isBlank()) {
      conditions.add(SQ_FOLDER.eq(folder));
    }

    Condition combined = conditions.stream().reduce(Condition::and).orElse(trueCondition());

    return dsl.selectCount().from(SQ).where(combined).fetchOne(0, Long.class);
  }

  public Optional<SavedQueryResponse> findById(Long id, Long userId) {
    var chartCountField =
        dsl.selectCount().from(CHART).where(CHART_SAVED_QUERY_ID.eq(SQ_ID)).asField("chart_count");

    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                DS_NAME_ALIAS,
                SQ_FOLDER,
                SQ_IS_SHARED,
                U_NAME_ALIAS,
                SQ_CREATED_BY,
                SQ_CREATED_AT,
                SQ_UPDATED_AT,
                chartCountField)
            .from(SQ)
            .leftJoin(DS)
            .on(SQ_DATASET_ID.eq(DS_ID))
            .join(USER_TABLE)
            .on(SQ_CREATED_BY.eq(U_ID))
            .where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(userId).or(SQ_IS_SHARED.isTrue())))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r));
  }

  public Optional<SavedQueryResponse> findByIdForOwner(Long id, Long userId) {
    var chartCountField =
        dsl.selectCount().from(CHART).where(CHART_SAVED_QUERY_ID.eq(SQ_ID)).asField("chart_count");

    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                DS_NAME_ALIAS,
                SQ_FOLDER,
                SQ_IS_SHARED,
                U_NAME_ALIAS,
                SQ_CREATED_BY,
                SQ_CREATED_AT,
                SQ_UPDATED_AT,
                chartCountField)
            .from(SQ)
            .leftJoin(DS)
            .on(SQ_DATASET_ID.eq(DS_ID))
            .join(USER_TABLE)
            .on(SQ_CREATED_BY.eq(U_ID))
            .where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(userId)))
            .fetchOne();

    if (r == null) return Optional.empty();
    return Optional.of(mapToResponse(r));
  }

  /** Returns the raw record regardless of ownership (used for clone). */
  public Optional<Record> findRawById(Long id) {
    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                SQ_FOLDER,
                SQ_IS_SHARED,
                SQ_CREATED_BY)
            .from(SQ)
            .where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(SQ_CREATED_BY)))
            .fetchOne();
    return Optional.ofNullable(r);
  }

  /** Finds a record by id with no ownership restriction (for reading shared queries). */
  public Optional<Record> findRawByIdUnrestricted(Long id) {
    Record r =
        dsl.select(
                SQ_ID,
                SQ_NAME,
                SQ_DESCRIPTION,
                SQ_SQL_TEXT,
                SQ_DATASET_ID,
                SQ_FOLDER,
                SQ_IS_SHARED,
                SQ_CREATED_BY)
            .from(SQ)
            .where(SQ_ID.eq(id))
            .fetchOne();
    return Optional.ofNullable(r);
  }

  public Long insert(CreateSavedQueryRequest req, Long userId) {
    return dsl.insertInto(SQ)
        .set(SQ_NAME, req.name())
        .set(SQ_DESCRIPTION, req.description())
        .set(SQ_SQL_TEXT, req.sqlText())
        .set(SQ_DATASET_ID, req.datasetId())
        .set(SQ_FOLDER, req.folder())
        .set(SQ_IS_SHARED, req.isShared())
        .set(SQ_CREATED_BY, userId)
        .returning(SQ_ID)
        .fetchOne()
        .get(SQ_ID);
  }

  public void update(Long id, UpdateSavedQueryRequest req, Long userId) {
    var update = dsl.update(SQ).set(SQ_UPDATED_BY, userId).set(SQ_UPDATED_AT, LocalDateTime.now());

    if (req.name() != null) update = update.set(SQ_NAME, req.name());
    if (req.description() != null) update = update.set(SQ_DESCRIPTION, req.description());
    if (req.sqlText() != null) update = update.set(SQ_SQL_TEXT, req.sqlText());
    if (req.datasetId() != null) update = update.set(SQ_DATASET_ID, req.datasetId());
    if (req.folder() != null) update = update.set(SQ_FOLDER, req.folder());
    if (req.isShared() != null) update = update.set(SQ_IS_SHARED, req.isShared());

    update.where(SQ_ID.eq(id)).execute();
  }

  public boolean deleteById(Long id, Long userId) {
    int deleted = dsl.deleteFrom(SQ).where(SQ_ID.eq(id).and(SQ_CREATED_BY.eq(userId))).execute();
    return deleted > 0;
  }

  public List<String> findDistinctFolders(Long userId) {
    return dsl.selectDistinct(SQ_FOLDER)
        .from(SQ)
        .where(SQ_FOLDER.isNotNull().and(SQ_CREATED_BY.eq(userId).or(SQ_IS_SHARED.isTrue())))
        .orderBy(SQ_FOLDER.asc())
        .fetch(SQ_FOLDER);
  }

  /** Count charts referencing a query created by a user OTHER than the owner. */
  public long countOtherUserCharts(Long savedQueryId, Long ownerId) {
    Table<?> C = table(name("chart"));
    Field<Long> C_SAVED_QUERY_ID = field(name("chart", "saved_query_id"), Long.class);
    Field<Long> C_CREATED_BY = field(name("chart", "created_by"), Long.class);
    return dsl.selectCount()
        .from(C)
        .where(C_SAVED_QUERY_ID.eq(savedQueryId).and(C_CREATED_BY.ne(ownerId)))
        .fetchOne(0, Long.class);
  }

  private SavedQueryResponse mapToResponse(Record r) {
    return new SavedQueryResponse(
        r.get(SQ_ID),
        r.get(SQ_NAME),
        r.get(SQ_DESCRIPTION),
        r.get(SQ_SQL_TEXT),
        r.get(SQ_DATASET_ID),
        r.get("dataset_name", String.class),
        r.get(SQ_FOLDER),
        Boolean.TRUE.equals(r.get(SQ_IS_SHARED)),
        r.get("created_by_name", String.class),
        r.get(SQ_CREATED_BY),
        r.get(SQ_CREATED_AT),
        r.get(SQ_UPDATED_AT),
        r.get("chart_count", Long.class));
  }
}
