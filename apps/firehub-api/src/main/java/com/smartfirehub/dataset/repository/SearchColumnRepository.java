package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.rowsearch.SearchConfig;
import java.util.Collection;

/**
 * dataset_column.is_searchable 읽기/쓰기(RLS 테이블).
 *
 * <p>구현은 {@code JooqSearchColumnRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface SearchColumnRepository {

  /** 검색 대상 필드를 column_order 순으로. */
  SearchConfig findConfig(long datasetId);

  /** 지정한 컬럼만 true, 나머지는 false. 검증(존재·타입)은 호출자 몫. */
  void setSearchable(long datasetId, Collection<String> columnNames);

  /** 컬럼이 검색 대상 필드인지. 컬럼이 없으면 false(컬럼 타입 변경 409 판정에 쓴다). */
  boolean isSearchable(long columnId);
}
