package com.smartfirehub.dataset.rowsearch;

import java.util.Map;

/**
 * 검증을 마친 행 검색 필터 — 구조화 조건과 허용 컬럼 화이트리스트(컬럼명 → 데이터 타입)를 함께 싣는다(ADR 0002 결정 2, WD-722).
 *
 * <p>왜 컴파일 결과(SQL) 대신 이것을 포트에 넘기는가: 필터를 평가할 곳이 색인 구현마다 다르다. PG 색인은 원본 테이블과 SQL 조인하고, Qdrant 색인은 데이터
 * 저장소에서 필터로 row_id 를 먼저 구하거나 결과를 사후 확인한다(결정 9 의 2단계). 데이터 저장소가 Tibero 면 다른 SQL 이 필요하다(WD-723). 그래서
 * 포트는 저장소 중립 형태를 받고, 각자 필요한 방언으로 컴파일한다.
 *
 * <p>검증은 만들 때 한 번 한다({@link #validated}) — 잘못된 필터는 색인을 건드리기 전에 400 으로 거부된다.
 */
public record RowQueryFilter(RowFilter filter, Map<String, String> columnTypes) {

  /** 조건 없는 필터. */
  public static RowQueryFilter none() {
    return new RowQueryFilter(RowFilter.none(), Map.of());
  }

  /**
   * 필터를 검증하고 감싼다.
   *
   * @throws IllegalArgumentException 알 수 없는 컬럼·GEOMETRY 컬럼·미지원 연산자·타입 변환 실패 시({@link
   *     RowFilterCompiler})
   */
  public static RowQueryFilter validated(RowFilter filter, Map<String, String> columnTypes) {
    RowFilterCompiler.compile(filter, columnTypes); // 검증만 — 결과는 각 구현이 필요할 때 다시 만든다
    return new RowQueryFilter(filter == null ? RowFilter.none() : filter, Map.copyOf(columnTypes));
  }

  /** 조건이 하나도 없는지. */
  public boolean isEmpty() {
    return filter == null || filter.isEmpty();
  }

  /** PostgreSQL 데이터 저장소용 WHERE 조각(원본 별칭 t). 조건이 없으면 {@link CompiledFilter#none()}. */
  public CompiledFilter toPostgres() {
    return RowFilterCompiler.compile(filter, columnTypes);
  }
}
