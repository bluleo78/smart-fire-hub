package com.smartfirehub.dataset.rowsearch;

import java.util.List;

/**
 * 검증·컴파일된 필터. sql 은 원본 테이블 별칭 {@code t} 를 참조하는 WHERE 조각(앞에 AND 없음), params 는 바인딩 값.
 * 비어 있으면 원본과 조인하지 않는다.
 */
public record CompiledFilter(String sql, List<Object> params) {

  /** 조건 없는 필터(원본과 조인하지 않는다). */
  public static CompiledFilter none() {
    return new CompiledFilter("", List.of());
  }

  /** 붙일 WHERE 조각이 없는지 — 비어 있으면 색인 테이블만으로 검색한다(sql 은 {@link #none()}·컴파일러가 늘 non-null 로 만든다). */
  public boolean isEmpty() {
    return sql.isEmpty();
  }
}
