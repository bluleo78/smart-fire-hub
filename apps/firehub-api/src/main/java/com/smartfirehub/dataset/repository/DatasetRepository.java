package com.smartfirehub.dataset.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
public class DatasetRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET = table(name("dataset"));
  private static final Field<Long> DS_ID = field(name("dataset", "id"), Long.class);
  private static final Field<String> DS_NAME = field(name("dataset", "name"), String.class);
  private static final Field<String> DS_TABLE_NAME =
      field(name("dataset", "table_name"), String.class);
  private static final Field<String> DS_DESCRIPTION =
      field(name("dataset", "description"), String.class);
  private static final Field<Long> DS_CATEGORY_ID =
      field(name("dataset", "category_id"), Long.class);
  private static final Field<String> DS_DATASET_TYPE =
      field(name("dataset", "dataset_type"), String.class);
  private static final Field<Long> DS_CREATED_BY = field(name("dataset", "created_by"), Long.class);
  private static final Field<LocalDateTime> DS_CREATED_AT =
      field(name("dataset", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> DS_UPDATED_AT =
      field(name("dataset", "updated_at"), LocalDateTime.class);
  private static final Field<Long> DS_UPDATED_BY = field(name("dataset", "updated_by"), Long.class);
  private static final Field<String> DS_STATUS = field(name("dataset", "status"), String.class);
  private static final Field<String> DS_STATUS_NOTE =
      field(name("dataset", "status_note"), String.class);
  private static final Field<Long> DS_STATUS_UPDATED_BY =
      field(name("dataset", "status_updated_by"), Long.class);
  private static final Field<LocalDateTime> DS_STATUS_UPDATED_AT =
      field(name("dataset", "status_updated_at"), LocalDateTime.class);

  private static final Table<?> DATASET_CATEGORY = table(name("dataset_category"));
  private static final Field<Long> DC_ID = field(name("dataset_category", "id"), Long.class);
  private static final Field<String> DC_NAME =
      field(name("dataset_category", "name"), String.class);
  private static final Field<String> DC_DESCRIPTION =
      field(name("dataset_category", "description"), String.class);

  private static final Table<?> DATASET_COLUMN = table(name("dataset_column"));
  private static final Field<Long> COL_DATASET_ID =
      field(name("dataset_column", "dataset_id"), Long.class);
  private static final Field<String> COL_COLUMN_NAME =
      field(name("dataset_column", "column_name"), String.class);
  private static final Field<String> COL_DISPLAY_NAME =
      field(name("dataset_column", "display_name"), String.class);
  private static final Field<String> COL_DESCRIPTION =
      field(name("dataset_column", "description"), String.class);

  private static final Table<?> DATASET_FAVORITE = table(name("dataset_favorite"));
  private static final Field<Long> DF_USER_ID =
      field(name("dataset_favorite", "user_id"), Long.class);
  private static final Field<Long> DF_DATASET_ID =
      field(name("dataset_favorite", "dataset_id"), Long.class);

  private static final Table<?> DATASET_TAG = table(name("dataset_tag"));
  private static final Field<Long> DT_DATASET_ID =
      field(name("dataset_tag", "dataset_id"), Long.class);
  private static final Field<String> DT_TAG_NAME =
      field(name("dataset_tag", "tag_name"), String.class);

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Field<String> U_NAME = field(name("user", "name"), String.class);

  public DatasetRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  private DatasetResponse mapToDatasetResponse(
      Record r, Set<Long> favoriteIds, Map<Long, List<String>> tagsByDatasetId) {
    CategoryResponse category =
        r.get(DC_ID) != null
            ? new CategoryResponse(r.get(DC_ID), r.get(DC_NAME), r.get(DC_DESCRIPTION))
            : null;
    Long id = r.get(DS_ID);
    boolean isFavorite = favoriteIds != null && favoriteIds.contains(id);
    List<String> tags =
        tagsByDatasetId != null ? tagsByDatasetId.getOrDefault(id, List.of()) : List.of();

    String statusUpdatedByName = null;
    Long statusUpdatedById = r.get(DS_STATUS_UPDATED_BY);
    if (statusUpdatedById != null) {
      statusUpdatedByName =
          dsl.select(U_NAME)
              .from(USER_TABLE)
              .where(U_ID.eq(statusUpdatedById))
              .fetchOptional(u -> u.get(U_NAME))
              .orElse(null);
    }

    return new DatasetResponse(
        id,
        r.get(DS_NAME),
        r.get(DS_TABLE_NAME),
        r.get(DS_DESCRIPTION),
        category,
        r.get(DS_DATASET_TYPE),
        r.get(DS_CREATED_AT),
        isFavorite,
        tags,
        r.get(DS_STATUS) != null ? r.get(DS_STATUS) : "NONE",
        r.get(DS_STATUS_NOTE),
        statusUpdatedByName,
        r.get(DS_STATUS_UPDATED_AT));
  }

  private Map<Long, List<String>> fetchTagsByDatasetIds(List<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Long, List<String>> result = new HashMap<>();
    dsl.select(DT_DATASET_ID, DT_TAG_NAME)
        .from(DATASET_TAG)
        .where(DT_DATASET_ID.in(datasetIds))
        .orderBy(DT_TAG_NAME.asc())
        .forEach(
            r -> {
              Long dsId = r.get(DT_DATASET_ID);
              result.computeIfAbsent(dsId, k -> new ArrayList<>()).add(r.get(DT_TAG_NAME));
            });
    return result;
  }

  private Set<Long> fetchFavoriteIds(Long currentUserId) {
    if (currentUserId == null) return Collections.emptySet();
    return new HashSet<>(
        dsl.select(DF_DATASET_ID)
            .from(DATASET_FAVORITE)
            .where(DF_USER_ID.eq(currentUserId))
            .fetch(r -> r.get(DF_DATASET_ID)));
  }

  private Condition buildCondition(
      Long categoryId, String datasetType, String search, String status) {
    Condition condition = trueCondition();
    if (categoryId != null) {
      condition = condition.and(DS_CATEGORY_ID.eq(categoryId));
    }
    if (datasetType != null && !datasetType.isBlank()) {
      condition = condition.and(DS_DATASET_TYPE.eq(datasetType));
    }
    if (status != null && !status.isBlank()) {
      condition = condition.and(DS_STATUS.eq(status));
    }
    if (search != null && !search.isBlank()) {
      String pattern = "%" + search.toLowerCase() + "%";
      condition =
          condition.and(
              DS_NAME
                  .likeIgnoreCase(pattern)
                  .or(DS_DESCRIPTION.likeIgnoreCase(pattern))
                  .or(DS_TABLE_NAME.likeIgnoreCase(pattern))
                  .or(COL_COLUMN_NAME.likeIgnoreCase(pattern))
                  .or(COL_DISPLAY_NAME.likeIgnoreCase(pattern))
                  .or(COL_DESCRIPTION.likeIgnoreCase(pattern)));
    }
    return condition;
  }

  public List<DatasetResponse> findAll(
      Long categoryId, String datasetType, String search, int page, int size) {
    return findAll(categoryId, datasetType, search, page, size, null, null, false);
  }

  public List<DatasetResponse> findAll(
      Long categoryId,
      String datasetType,
      String search,
      int page,
      int size,
      Long currentUserId,
      String status,
      boolean favoriteOnly) {
    Condition condition = buildCondition(categoryId, datasetType, search, status);

    // First get the distinct IDs for the page
    var idQuery =
        dsl.selectDistinct(DS_ID)
            .from(DATASET)
            .leftJoin(DATASET_COLUMN)
            .on(COL_DATASET_ID.eq(DS_ID));

    if (favoriteOnly && currentUserId != null) {
      idQuery =
          idQuery
              .innerJoin(DATASET_FAVORITE)
              .on(DF_DATASET_ID.eq(DS_ID).and(DF_USER_ID.eq(currentUserId)));
    }

    List<Long> datasetIds =
        idQuery
            .where(condition)
            .orderBy(DS_ID.asc())
            .limit(size)
            .offset(page * size)
            .fetch(r -> r.get(DS_ID));

    if (datasetIds.isEmpty()) {
      return List.of();
    }

    Set<Long> favoriteIds = fetchFavoriteIds(currentUserId);
    Map<Long, List<String>> tagsByDatasetId = fetchTagsByDatasetIds(datasetIds);

    return dsl.select(
            DS_ID,
            DS_NAME,
            DS_TABLE_NAME,
            DS_DESCRIPTION,
            DS_DATASET_TYPE,
            DS_CREATED_AT,
            DS_STATUS,
            DS_STATUS_NOTE,
            DS_STATUS_UPDATED_BY,
            DS_STATUS_UPDATED_AT,
            DC_ID,
            DC_NAME,
            DC_DESCRIPTION)
        .from(DATASET)
        .leftJoin(DATASET_CATEGORY)
        .on(DS_CATEGORY_ID.eq(DC_ID))
        .where(DS_ID.in(datasetIds))
        .orderBy(DS_ID.asc())
        .fetch(r -> mapToDatasetResponse(r, favoriteIds, tagsByDatasetId));
  }

  public long count(Long categoryId, String datasetType, String search) {
    return count(categoryId, datasetType, search, null, null, false);
  }

  public long count(
      Long categoryId,
      String datasetType,
      String search,
      Long currentUserId,
      String status,
      boolean favoriteOnly) {
    Condition condition = buildCondition(categoryId, datasetType, search, status);

    var countQuery =
        dsl.select(countDistinct(DS_ID))
            .from(DATASET)
            .leftJoin(DATASET_COLUMN)
            .on(COL_DATASET_ID.eq(DS_ID));

    if (favoriteOnly && currentUserId != null) {
      return countQuery
          .innerJoin(DATASET_FAVORITE)
          .on(DF_DATASET_ID.eq(DS_ID).and(DF_USER_ID.eq(currentUserId)))
          .where(condition)
          .fetchOne(0, Long.class);
    }

    return countQuery.where(condition).fetchOne(0, Long.class);
  }

  public Optional<DatasetResponse> findById(Long id) {
    return findById(id, null);
  }

  public Optional<DatasetResponse> findById(Long id, Long currentUserId) {
    Set<Long> favoriteIds = fetchFavoriteIds(currentUserId);
    Map<Long, List<String>> tagMap = fetchTagsByDatasetIds(List.of(id));

    return dsl.select(
            DS_ID,
            DS_NAME,
            DS_TABLE_NAME,
            DS_DESCRIPTION,
            DS_DATASET_TYPE,
            DS_CREATED_AT,
            DS_STATUS,
            DS_STATUS_NOTE,
            DS_STATUS_UPDATED_BY,
            DS_STATUS_UPDATED_AT,
            DC_ID,
            DC_NAME,
            DC_DESCRIPTION)
        .from(DATASET)
        .leftJoin(DATASET_CATEGORY)
        .on(DS_CATEGORY_ID.eq(DC_ID))
        .where(DS_ID.eq(id))
        .fetchOptional(r -> mapToDatasetResponse(r, favoriteIds, tagMap));
  }

  public DatasetResponse save(CreateDatasetRequest request, Long createdBy) {
    return dsl.insertInto(DATASET)
        .set(DS_NAME, request.name())
        .set(DS_TABLE_NAME, request.tableName())
        .set(DS_DESCRIPTION, request.description())
        .set(DS_CATEGORY_ID, request.categoryId())
        .set(DS_DATASET_TYPE, request.datasetType())
        .set(DS_CREATED_BY, createdBy)
        .returning(
            DS_ID,
            DS_NAME,
            DS_TABLE_NAME,
            DS_DESCRIPTION,
            DS_DATASET_TYPE,
            DS_CREATED_AT,
            DS_STATUS,
            DS_STATUS_NOTE,
            DS_STATUS_UPDATED_BY,
            DS_STATUS_UPDATED_AT,
            DS_CATEGORY_ID)
        .fetchOne(
            r -> {
              CategoryResponse category =
                  r.get(DS_CATEGORY_ID) != null
                      ? dsl.select(DC_ID, DC_NAME, DC_DESCRIPTION)
                          .from(DATASET_CATEGORY)
                          .where(DC_ID.eq(r.get(DS_CATEGORY_ID)))
                          .fetchOne(
                              cat ->
                                  new CategoryResponse(
                                      cat.get(DC_ID), cat.get(DC_NAME), cat.get(DC_DESCRIPTION)))
                      : null;
              return new DatasetResponse(
                  r.get(DS_ID),
                  r.get(DS_NAME),
                  r.get(DS_TABLE_NAME),
                  r.get(DS_DESCRIPTION),
                  category,
                  r.get(DS_DATASET_TYPE),
                  r.get(DS_CREATED_AT),
                  false,
                  List.of(),
                  r.get(DS_STATUS) != null ? r.get(DS_STATUS) : "NONE",
                  r.get(DS_STATUS_NOTE),
                  null,
                  r.get(DS_STATUS_UPDATED_AT));
            });
  }

  public void update(Long id, UpdateDatasetRequest request, Long updatedBy) {
    dsl.update(DATASET)
        .set(DS_NAME, request.name())
        .set(DS_DESCRIPTION, request.description())
        .set(DS_CATEGORY_ID, request.categoryId())
        .set(DS_UPDATED_AT, LocalDateTime.now())
        .set(DS_UPDATED_BY, updatedBy)
        .where(DS_ID.eq(id))
        .execute();
  }

  public void updateStatus(Long id, String status, String statusNote, Long statusUpdatedBy) {
    dsl.update(DATASET)
        .set(DS_STATUS, status)
        .set(DS_STATUS_NOTE, statusNote)
        .set(DS_STATUS_UPDATED_BY, statusUpdatedBy)
        .set(DS_STATUS_UPDATED_AT, LocalDateTime.now())
        .where(DS_ID.eq(id))
        .execute();
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(DATASET).where(DS_ID.eq(id)).execute();
  }

  public boolean existsByName(String name) {
    return dsl.fetchExists(dsl.selectOne().from(DATASET).where(DS_NAME.eq(name)));
  }

  public boolean existsByTableName(String tableName) {
    return dsl.fetchExists(dsl.selectOne().from(DATASET).where(DS_TABLE_NAME.eq(tableName)));
  }

  public Optional<String> findTableNameById(Long id) {
    return dsl.select(DS_TABLE_NAME)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_TABLE_NAME));
  }

  public Optional<Long> findCreatedByById(Long id) {
    return dsl.select(DS_CREATED_BY)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_CREATED_BY));
  }

  public Optional<LocalDateTime> findUpdatedAtById(Long id) {
    return dsl.select(DS_UPDATED_AT)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_UPDATED_AT));
  }

  public Optional<Long> findUpdatedByById(Long id) {
    return dsl.select(DS_UPDATED_BY)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_UPDATED_BY));
  }

  public Optional<String> findDatasetTypeById(Long id) {
    return dsl.select(DS_DATASET_TYPE)
        .from(DATASET)
        .where(DS_ID.eq(id))
        .fetchOptional(r -> r.get(DS_DATASET_TYPE));
  }
}
