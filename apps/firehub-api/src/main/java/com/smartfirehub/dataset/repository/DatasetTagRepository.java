package com.smartfirehub.dataset.repository;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.jooq.impl.DSL.*;

@Repository
public class DatasetTagRepository {

    private final DSLContext dsl;

    private static final Table<?> DATASET_TAG = table(name("dataset_tag"));
    private static final Field<Long> DT_DATASET_ID = field(name("dataset_tag", "dataset_id"), Long.class);
    private static final Field<String> DT_TAG_NAME = field(name("dataset_tag", "tag_name"), String.class);
    private static final Field<Long> DT_CREATED_BY = field(name("dataset_tag", "created_by"), Long.class);

    public DatasetTagRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<String> findByDatasetId(Long datasetId) {
        return dsl.select(DT_TAG_NAME)
                .from(DATASET_TAG)
                .where(DT_DATASET_ID.eq(datasetId))
                .orderBy(DT_TAG_NAME.asc())
                .fetch(r -> r.get(DT_TAG_NAME));
    }

    public void insert(Long datasetId, String tagName, Long userId) {
        dsl.insertInto(DATASET_TAG)
                .set(DT_DATASET_ID, datasetId)
                .set(DT_TAG_NAME, tagName)
                .set(DT_CREATED_BY, userId)
                .onConflictDoNothing()
                .execute();
    }

    public void delete(Long datasetId, String tagName) {
        dsl.deleteFrom(DATASET_TAG)
                .where(DT_DATASET_ID.eq(datasetId))
                .and(DT_TAG_NAME.eq(tagName))
                .execute();
    }

    public List<String> findAllDistinctTags() {
        return dsl.selectDistinct(DT_TAG_NAME)
                .from(DATASET_TAG)
                .orderBy(DT_TAG_NAME.asc())
                .fetch(r -> r.get(DT_TAG_NAME));
    }
}
