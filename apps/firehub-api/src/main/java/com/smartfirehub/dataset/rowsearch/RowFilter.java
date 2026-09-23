package com.smartfirehub.dataset.rowsearch;

import java.util.List;

/**
 * 에이전트가 넘기는 구조화 필터(AND 결합). 자유 SQL 을 받지 않아 외부 벡터 DB 의 payload 필터로도 옮길 수 있다.
 *
 * <p>op: eq, neq, in, gt, gte, lt, lte, is_null, is_not_null
 */
public record RowFilter(List<Condition> conditions) {

  /** 필터 조건 하나. value 는 op 에 따라 스칼라·리스트·null. */
  public record Condition(String column, String op, Object value) {}

  /** 조건 없는 필터. */
  public static RowFilter none() {
    return new RowFilter(List.of());
  }

  /** 조건이 하나도 없는지(null 목록 포함). */
  public boolean isEmpty() {
    return conditions == null || conditions.isEmpty();
  }
}
