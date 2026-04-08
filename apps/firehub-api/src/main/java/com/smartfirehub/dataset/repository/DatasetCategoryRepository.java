package com.smartfirehub.dataset.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DatasetCategoryRepository {

  private final DSLContext dsl;

  private static final Table<?> DATASET_CATEGORY = table(name("dataset_category"));
  private static final Field<Long> DC_ID = field(name("dataset_category", "id"), Long.class);
  private static final Field<String> DC_NAME =
      field(name("dataset_category", "name"), String.class);
  private static final Field<String> DC_DESCRIPTION =
      field(name("dataset_category", "description"), String.class);
  private static final Field<LocalDateTime> DC_UPDATED_AT =
      field(name("dataset_category", "updated_at"), LocalDateTime.class);

  private CategoryResponse mapToCategoryResponse(Record r) {
    return new CategoryResponse(r.get(DC_ID), r.get(DC_NAME), r.get(DC_DESCRIPTION));
  }

  public List<CategoryResponse> findAll() {
    return dsl.select(DC_ID, DC_NAME, DC_DESCRIPTION)
        .from(DATASET_CATEGORY)
        .orderBy(DC_ID.asc())
        .fetch(this::mapToCategoryResponse);
  }

  public Optional<CategoryResponse> findById(Long id) {
    return dsl.select(DC_ID, DC_NAME, DC_DESCRIPTION)
        .from(DATASET_CATEGORY)
        .where(DC_ID.eq(id))
        .fetchOptional(this::mapToCategoryResponse);
  }

  public CategoryResponse save(String name, String description) {
    return dsl.insertInto(DATASET_CATEGORY)
        .set(DC_NAME, name)
        .set(DC_DESCRIPTION, description)
        .returning(DC_ID, DC_NAME, DC_DESCRIPTION)
        .fetchOne(this::mapToCategoryResponse);
  }

  public void update(Long id, String name, String description) {
    dsl.update(DATASET_CATEGORY)
        .set(DC_NAME, name)
        .set(DC_DESCRIPTION, description)
        .set(DC_UPDATED_AT, LocalDateTime.now())
        .where(DC_ID.eq(id))
        .execute();
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(DATASET_CATEGORY).where(DC_ID.eq(id)).execute();
  }

  public boolean existsByName(String name) {
    return dsl.fetchExists(dsl.selectOne().from(DATASET_CATEGORY).where(DC_NAME.eq(name)));
  }

  /**
   * 주어진 이름이 다른 카테고리에 이미 존재하는지 확인한다.
   * 수정 시 자기 자신을 제외하고 중복 여부를 판별하기 위해 사용한다.
   *
   * @param name 확인할 카테고리 이름
   * @param excludeId 제외할 카테고리 ID (자기 자신)
   * @return 다른 카테고리에 동일 이름이 존재하면 true
   */
  public boolean existsByNameExcludingId(String name, Long excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(DATASET_CATEGORY)
            .where(DC_NAME.eq(name).and(DC_ID.ne(excludeId))));
  }
}
