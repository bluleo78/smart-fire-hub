package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class DatasetRepository {

    private final DSLContext dsl;

    private static final Table<?> DATASET = table(name("dataset"));
    private static final Field<Long> DS_ID = field(name("dataset", "id"), Long.class);
    private static final Field<String> DS_NAME = field(name("dataset", "name"), String.class);
    private static final Field<String> DS_TABLE_NAME = field(name("dataset", "table_name"), String.class);
    private static final Field<String> DS_DESCRIPTION = field(name("dataset", "description"), String.class);
    private static final Field<Long> DS_CATEGORY_ID = field(name("dataset", "category_id"), Long.class);
    private static final Field<String> DS_DATASET_TYPE = field(name("dataset", "dataset_type"), String.class);
    private static final Field<Long> DS_CREATED_BY = field(name("dataset", "created_by"), Long.class);
    private static final Field<LocalDateTime> DS_CREATED_AT = field(name("dataset", "created_at"), LocalDateTime.class);
    private static final Field<LocalDateTime> DS_UPDATED_AT = field(name("dataset", "updated_at"), LocalDateTime.class);
    private static final Field<Long> DS_UPDATED_BY = field(name("dataset", "updated_by"), Long.class);

    private static final Table<?> DATASET_CATEGORY = table(name("dataset_category"));
    private static final Field<Long> DC_ID = field(name("dataset_category", "id"), Long.class);
    private static final Field<String> DC_NAME = field(name("dataset_category", "name"), String.class);
    private static final Field<String> DC_DESCRIPTION = field(name("dataset_category", "description"), String.class);

    public DatasetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private DatasetResponse mapToDatasetResponse(Record r) {
        CategoryResponse category = r.get(DC_ID) != null
                ? new CategoryResponse(r.get(DC_ID), r.get(DC_NAME), r.get(DC_DESCRIPTION))
                : null;
        return new DatasetResponse(
                r.get(DS_ID),
                r.get(DS_NAME),
                r.get(DS_TABLE_NAME),
                r.get(DS_DESCRIPTION),
                category,
                r.get(DS_DATASET_TYPE),
                r.get(DS_CREATED_AT)
        );
    }

    public List<DatasetResponse> findAll(Long categoryId, String datasetType, String search, int page, int size) {
        Condition condition = trueCondition();

        if (categoryId != null) {
            condition = condition.and(DS_CATEGORY_ID.eq(categoryId));
        }

        if (datasetType != null && !datasetType.isBlank()) {
            condition = condition.and(DS_DATASET_TYPE.eq(datasetType));
        }

        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            condition = condition.and(
                    DS_NAME.likeIgnoreCase(pattern)
                            .or(DS_DESCRIPTION.likeIgnoreCase(pattern))
            );
        }

        return dsl.select(DS_ID, DS_NAME, DS_TABLE_NAME, DS_DESCRIPTION, DS_DATASET_TYPE, DS_CREATED_AT,
                        DC_ID, DC_NAME, DC_DESCRIPTION)
                .from(DATASET)
                .leftJoin(DATASET_CATEGORY).on(DS_CATEGORY_ID.eq(DC_ID))
                .where(condition)
                .orderBy(DS_ID.asc())
                .limit(size)
                .offset(page * size)
                .fetch(this::mapToDatasetResponse);
    }

    public long count(Long categoryId, String datasetType, String search) {
        Condition condition = trueCondition();

        if (categoryId != null) {
            condition = condition.and(DS_CATEGORY_ID.eq(categoryId));
        }

        if (datasetType != null && !datasetType.isBlank()) {
            condition = condition.and(DS_DATASET_TYPE.eq(datasetType));
        }

        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.toLowerCase() + "%";
            condition = condition.and(
                    DS_NAME.likeIgnoreCase(pattern)
                            .or(DS_DESCRIPTION.likeIgnoreCase(pattern))
            );
        }

        return dsl.selectCount()
                .from(DATASET)
                .where(condition)
                .fetchOne(0, Long.class);
    }

    public Optional<DatasetResponse> findById(Long id) {
        return dsl.select(DS_ID, DS_NAME, DS_TABLE_NAME, DS_DESCRIPTION, DS_DATASET_TYPE, DS_CREATED_AT,
                        DC_ID, DC_NAME, DC_DESCRIPTION)
                .from(DATASET)
                .leftJoin(DATASET_CATEGORY).on(DS_CATEGORY_ID.eq(DC_ID))
                .where(DS_ID.eq(id))
                .fetchOptional(this::mapToDatasetResponse);
    }

    public DatasetResponse save(CreateDatasetRequest request, Long createdBy) {
        return dsl.insertInto(DATASET)
                .set(DS_NAME, request.name())
                .set(DS_TABLE_NAME, request.tableName())
                .set(DS_DESCRIPTION, request.description())
                .set(DS_CATEGORY_ID, request.categoryId())
                .set(DS_DATASET_TYPE, request.datasetType())
                .set(DS_CREATED_BY, createdBy)
                .returning(DS_ID, DS_NAME, DS_TABLE_NAME, DS_DESCRIPTION, DS_DATASET_TYPE, DS_CREATED_AT, DS_CATEGORY_ID)
                .fetchOne(r -> {
                    // Need to fetch category separately for the response
                    CategoryResponse category = r.get(DS_CATEGORY_ID) != null
                            ? dsl.select(DC_ID, DC_NAME, DC_DESCRIPTION)
                                .from(DATASET_CATEGORY)
                                .where(DC_ID.eq(r.get(DS_CATEGORY_ID)))
                                .fetchOne(cat -> new CategoryResponse(
                                        cat.get(DC_ID),
                                        cat.get(DC_NAME),
                                        cat.get(DC_DESCRIPTION)
                                ))
                            : null;
                    return new DatasetResponse(
                            r.get(DS_ID),
                            r.get(DS_NAME),
                            r.get(DS_TABLE_NAME),
                            r.get(DS_DESCRIPTION),
                            category,
                            r.get(DS_DATASET_TYPE),
                            r.get(DS_CREATED_AT)
                    );
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

    public void deleteById(Long id) {
        dsl.deleteFrom(DATASET)
                .where(DS_ID.eq(id))
                .execute();
    }

    public boolean existsByName(String name) {
        return dsl.fetchExists(
                dsl.selectOne().from(DATASET).where(DS_NAME.eq(name))
        );
    }

    public boolean existsByTableName(String tableName) {
        return dsl.fetchExists(
                dsl.selectOne().from(DATASET).where(DS_TABLE_NAME.eq(tableName))
        );
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
}
