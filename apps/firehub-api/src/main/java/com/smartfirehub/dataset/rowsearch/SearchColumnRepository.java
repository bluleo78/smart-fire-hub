package com.smartfirehub.dataset.rowsearch;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** dataset_column.is_searchable 읽기/쓰기(RLS 테이블). */
@Repository
@Transactional
@RequiredArgsConstructor
public class SearchColumnRepository {

  private final DSLContext dsl;

  /** 검색 대상 필드를 column_order 순으로. */
  public SearchConfig findConfig(long datasetId) {
    return new SearchConfig(
        dsl.fetch(
                "SELECT column_name, display_name FROM dataset_column"
                    + " WHERE dataset_id = ? AND is_searchable ORDER BY column_order, id",
                datasetId)
            .map(r -> new SearchConfig.Field(r.get(0, String.class), r.get(1, String.class))));
  }

  /** 지정한 컬럼만 true, 나머지는 false. 검증(존재·타입)은 호출자 몫. */
  public void setSearchable(long datasetId, Collection<String> columnNames) {
    dsl.execute(
        "UPDATE dataset_column SET is_searchable = (column_name = ANY(?)) WHERE dataset_id = ?",
        (Object) columnNames.toArray(String[]::new),
        datasetId);
  }

  /** 컬럼이 검색 대상 필드인지. 컬럼이 없으면 false(컬럼 타입 변경 409 판정에 쓴다). */
  public boolean isSearchable(long columnId) {
    Boolean v =
        dsl.fetchOptional("SELECT is_searchable FROM dataset_column WHERE id = ?", columnId)
            .map(r -> r.get(0, Boolean.class))
            .orElse(false);
    return Boolean.TRUE.equals(v);
  }
}
