package com.smartfirehub.dataset.repository;

import static org.jooq.impl.DSL.*;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DatasetTagRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_TAG = table(name("dataset_tag"));
  private static final Field<Long> DT_DATASET_ID =
      field(name("dataset_tag", "dataset_id"), Long.class);
  private static final Field<String> DT_TAG_NAME =
      field(name("dataset_tag", "tag_name"), String.class);
  private static final Field<Long> DT_CREATED_BY =
      field(name("dataset_tag", "created_by"), Long.class);

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

  /** 열람 가능한 데이터셋의 태그만 집계한다 — 숨김 데이터셋의 태그명도 정보다(스펙 §4.2 1행). */
  public List<String> findAllDistinctTags(Condition visibleDataset) {
    return dsl.selectDistinct(DT_TAG_NAME)
        .from(DATASET_TAG)
        .join(table(name("dataset")))
        .on(field(name("dataset", "id"), Long.class).eq(DT_DATASET_ID))
        .where(visibleDataset)
        .orderBy(DT_TAG_NAME.asc())
        .fetch(r -> r.get(DT_TAG_NAME));
  }
}
