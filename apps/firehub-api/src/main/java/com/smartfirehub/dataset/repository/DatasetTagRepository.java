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

  /**
   * 태그를 넣고 넣은 행 수를 돌려준다. 같은 데이터셋에 대소문자만 다른 태그가 이미 있으면(유일 인덱스
   * (dataset_id, lower(tag_name)), V155) 넣지 않고 0 — 호출자는 0 이면 중복으로 본다(WD-389).
   */
  public int insert(Long datasetId, String tagName, Long userId) {
    return dsl.insertInto(DATASET_TAG)
        .set(DT_DATASET_ID, datasetId)
        .set(DT_TAG_NAME, tagName)
        .set(DT_CREATED_BY, userId)
        .onConflictDoNothing()
        .execute();
  }

  /**
   * 데이터셋의 태그를 대소문자 무시로 지우고 지운 행 수를 돌려준다.
   *
   * <p>WD-242: 추가 중복 검사가 대소문자를 무시하므로(#671) 삭제도 같은 기준이어야 한다 — 예전에는 정확 일치만 지워 「Fire」가 있을 때 「fire」 삭제가
   * 204 를 주고도 아무것도 지우지 않았다. 호출자는 0 이면 없는 태그로 본다.
   */
  public int deleteIgnoreCase(Long datasetId, String tagName) {
    return dsl.deleteFrom(DATASET_TAG)
        .where(DT_DATASET_ID.eq(datasetId))
        .and(lower(DT_TAG_NAME).eq(lower(val(tagName))))
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
