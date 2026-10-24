package com.smartfirehub.dataset.repository;

import static org.jooq.impl.DSL.*;

import com.smartfirehub.dataset.dto.CategoryResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
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

  /**
   * 주어진 이름과 앞뒤 공백·대소문자를 무시하고 같은 카테고리가 있는지 확인한다(WD-241).
   *
   * <p>예전에는 정확히 같은 문자열만 비교해 「 행정 」·「HAENG」/「haeng」이 별개 카테고리로 저장됐다. 저장된 값도 trim·lower 해 비교하므로, 수정
   * 전부터 남아 있던 공백 든 이름과도 겹침을 잡는다. 호출 쪽이 이미 trim 한 이름을 넘긴다.
   *
   * @param name 확인할 카테고리 이름(trim 된 값)
   * @return 같은 이름으로 볼 카테고리가 있으면 true
   */
  public boolean existsByName(String name) {
    return dsl.fetchExists(dsl.selectOne().from(DATASET_CATEGORY).where(sameNameAs(name)));
  }

  /**
   * 주어진 이름이 다른 카테고리에 이미 존재하는지 확인한다. 수정 시 자기 자신을 제외하고 중복 여부를 판별하기 위해 사용한다. 비교 규칙은 {@link
   * #existsByName(String)} 과 같다(앞뒤 공백·대소문자 무시) — 자기 이름의 공백만 다르게 보내도 자기 자신이라 통과한다.
   *
   * @param name 확인할 카테고리 이름(trim 된 값)
   * @param excludeId 제외할 카테고리 ID (자기 자신)
   * @return 다른 카테고리에 동일 이름이 존재하면 true
   */
  public boolean existsByNameExcludingId(String name, Long excludeId) {
    return dsl.fetchExists(
        dsl.selectOne().from(DATASET_CATEGORY).where(sameNameAs(name).and(DC_ID.ne(excludeId))));
  }

  /** 저장된 이름을 trim·소문자로 바꿔 받은 이름(소문자)과 비교하는 조건 — 대소문자 변환은 DB 의 lower 한 곳에서만 한다. */
  private static Condition sameNameAs(String name) {
    return lower(trim(DC_NAME)).eq(lower(val(name)));
  }
}
