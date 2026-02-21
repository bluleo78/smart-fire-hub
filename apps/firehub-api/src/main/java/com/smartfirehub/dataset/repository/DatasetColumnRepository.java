package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.*;

@Repository
public class DatasetColumnRepository {

    private final DSLContext dsl;

    private static final Table<?> DATASET_COLUMN = table(name("dataset_column"));
    private static final Field<Long> COL_ID = field(name("dataset_column", "id"), Long.class);
    private static final Field<Long> COL_DATASET_ID = field(name("dataset_column", "dataset_id"), Long.class);
    private static final Field<String> COL_COLUMN_NAME = field(name("dataset_column", "column_name"), String.class);
    private static final Field<String> COL_DISPLAY_NAME = field(name("dataset_column", "display_name"), String.class);
    private static final Field<String> COL_DATA_TYPE = field(name("dataset_column", "data_type"), String.class);
    private static final Field<Integer> COL_MAX_LENGTH = field(name("dataset_column", "max_length"), Integer.class);
    private static final Field<Boolean> COL_IS_NULLABLE = field(name("dataset_column", "is_nullable"), Boolean.class);
    private static final Field<Boolean> COL_IS_INDEXED = field(name("dataset_column", "is_indexed"), Boolean.class);
    private static final Field<String> COL_DESCRIPTION = field(name("dataset_column", "description"), String.class);
    private static final Field<Integer> COL_COLUMN_ORDER = field(name("dataset_column", "column_order"), Integer.class);
    private static final Field<Boolean> COL_IS_PRIMARY_KEY = field(name("dataset_column", "is_primary_key"), Boolean.class);

    public DatasetColumnRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    private DatasetColumnResponse mapToColumnResponse(Record r) {
        return new DatasetColumnResponse(
                r.get(COL_ID),
                r.get(COL_COLUMN_NAME),
                r.get(COL_DISPLAY_NAME),
                r.get(COL_DATA_TYPE),
                r.get(COL_MAX_LENGTH),
                r.get(COL_IS_NULLABLE),
                r.get(COL_IS_INDEXED),
                r.get(COL_DESCRIPTION),
                r.get(COL_COLUMN_ORDER),
                Boolean.TRUE.equals(r.get(COL_IS_PRIMARY_KEY))
        );
    }

    public List<DatasetColumnResponse> findByDatasetId(Long datasetId) {
        return dsl.select(COL_ID, COL_COLUMN_NAME, COL_DISPLAY_NAME, COL_DATA_TYPE, COL_MAX_LENGTH,
                        COL_IS_NULLABLE, COL_IS_INDEXED, COL_DESCRIPTION, COL_COLUMN_ORDER, COL_IS_PRIMARY_KEY)
                .from(DATASET_COLUMN)
                .where(COL_DATASET_ID.eq(datasetId))
                .orderBy(COL_COLUMN_ORDER.asc())
                .fetch(this::mapToColumnResponse);
    }

    public DatasetColumnResponse save(Long datasetId, DatasetColumnRequest request, int columnOrder) {
        return dsl.insertInto(DATASET_COLUMN)
                .set(COL_DATASET_ID, datasetId)
                .set(COL_COLUMN_NAME, request.columnName())
                .set(COL_DISPLAY_NAME, request.displayName())
                .set(COL_DATA_TYPE, request.dataType())
                .set(COL_MAX_LENGTH, request.maxLength())
                .set(COL_IS_NULLABLE, request.isNullable())
                .set(COL_IS_INDEXED, request.isIndexed())
                .set(COL_DESCRIPTION, request.description())
                .set(COL_COLUMN_ORDER, columnOrder)
                .set(COL_IS_PRIMARY_KEY, request.isPrimaryKey())
                .returning(COL_ID, COL_COLUMN_NAME, COL_DISPLAY_NAME, COL_DATA_TYPE, COL_MAX_LENGTH,
                        COL_IS_NULLABLE, COL_IS_INDEXED, COL_DESCRIPTION, COL_COLUMN_ORDER, COL_IS_PRIMARY_KEY)
                .fetchOne(this::mapToColumnResponse);
    }

    public void saveBatch(Long datasetId, List<DatasetColumnRequest> columns) {
        for (int i = 0; i < columns.size(); i++) {
            DatasetColumnRequest col = columns.get(i);
            dsl.insertInto(DATASET_COLUMN)
                    .set(COL_DATASET_ID, datasetId)
                    .set(COL_COLUMN_NAME, col.columnName())
                    .set(COL_DISPLAY_NAME, col.displayName())
                    .set(COL_DATA_TYPE, col.dataType())
                    .set(COL_MAX_LENGTH, col.maxLength())
                    .set(COL_IS_NULLABLE, col.isNullable())
                    .set(COL_IS_INDEXED, col.isIndexed())
                    .set(COL_DESCRIPTION, col.description())
                    .set(COL_COLUMN_ORDER, i)
                    .set(COL_IS_PRIMARY_KEY, col.isPrimaryKey())
                    .execute();
        }
    }

    public Optional<DatasetColumnResponse> findById(Long id) {
        return dsl.select(COL_ID, COL_COLUMN_NAME, COL_DISPLAY_NAME, COL_DATA_TYPE, COL_MAX_LENGTH,
                        COL_IS_NULLABLE, COL_IS_INDEXED, COL_DESCRIPTION, COL_COLUMN_ORDER, COL_IS_PRIMARY_KEY)
                .from(DATASET_COLUMN)
                .where(COL_ID.eq(id))
                .fetchOptional(this::mapToColumnResponse);
    }

    public void update(Long id, UpdateColumnRequest request) {
        var step = dsl.update(DATASET_COLUMN);
        var set = step.set(COL_DISPLAY_NAME, request.displayName())
                .set(COL_IS_INDEXED, request.isIndexed())
                .set(COL_DESCRIPTION, request.description());

        if (request.columnName() != null) {
            set = set.set(COL_COLUMN_NAME, request.columnName());
        }
        if (request.dataType() != null) {
            set = set.set(COL_DATA_TYPE, request.dataType());
        }
        if (request.maxLength() != null) {
            set = set.set(COL_MAX_LENGTH, request.maxLength());
        }
        // maxLength를 null로 명시적으로 세팅해야 하는 경우 (VARCHAR→TEXT 등)
        if (request.dataType() != null && !"VARCHAR".equals(request.dataType())) {
            set = set.setNull(COL_MAX_LENGTH);
        }
        if (request.isNullable() != null) {
            set = set.set(COL_IS_NULLABLE, request.isNullable());
        }
        if (request.isPrimaryKey() != null) {
            set = set.set(COL_IS_PRIMARY_KEY, request.isPrimaryKey());
        }

        set.where(COL_ID.eq(id)).execute();
    }

    public int getMaxOrder(Long datasetId) {
        Integer maxOrder = dsl.select(max(COL_COLUMN_ORDER))
                .from(DATASET_COLUMN)
                .where(COL_DATASET_ID.eq(datasetId))
                .fetchOne(0, Integer.class);
        return maxOrder != null ? maxOrder : -1;
    }

    public void deleteByDatasetId(Long datasetId) {
        dsl.deleteFrom(DATASET_COLUMN)
                .where(COL_DATASET_ID.eq(datasetId))
                .execute();
    }

    public Optional<Long> findDatasetIdByColumnId(Long columnId) {
        return dsl.select(COL_DATASET_ID)
                .from(DATASET_COLUMN)
                .where(COL_ID.eq(columnId))
                .fetchOptional(r -> r.get(COL_DATASET_ID));
    }

    public void deleteById(Long id) {
        dsl.deleteFrom(DATASET_COLUMN)
                .where(COL_ID.eq(id))
                .execute();
    }

    public void updateOrders(Long datasetId, List<Long> columnIds) {
        for (int i = 0; i < columnIds.size(); i++) {
            dsl.update(DATASET_COLUMN)
                    .set(COL_COLUMN_ORDER, i)
                    .where(COL_ID.eq(columnIds.get(i)))
                    .and(COL_DATASET_ID.eq(datasetId))
                    .execute();
        }
    }

    public String findFirstDescriptionByColumnName(String columnName, Long excludeDatasetId) {
        return dsl.select(COL_DESCRIPTION)
                .from(DATASET_COLUMN)
                .where(COL_COLUMN_NAME.eq(columnName))
                .and(COL_DATASET_ID.ne(excludeDatasetId))
                .and(COL_DESCRIPTION.isNotNull())
                .and(COL_DESCRIPTION.ne(""))
                .limit(1)
                .fetchOptional(r -> r.get(COL_DESCRIPTION))
                .orElse(null);
    }

    public void updateDescription(Long columnId, String description) {
        dsl.update(DATASET_COLUMN)
                .set(COL_DESCRIPTION, description)
                .where(COL_ID.eq(columnId))
                .execute();
    }
}
