package com.smartfirehub.global.platformdb.mybatis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * IN 목록 조각 나누기 헬퍼(WD-706). Tibero 는 IN 목록이 1000 개를 넘으면 거절하므로 MyBatis 리포지토리가 값 목록을 중복 제거(입력 순서 유지)한
 * 뒤 {@value #SIZE} 개 이하 조각으로 나눠 문장을 여러 번 돈다. 여러 리포지토리에 복제돼 있던 로직을 한 곳으로 모았다.
 */
public final class InChunks {

  /** IN 목록 한 번의 최대 크기 — Tibero 한도(1000)의 절반으로 여유를 둔다. */
  public static final int SIZE = 500;

  /**
   * 다중 행 INSERT(Tibero {@code INSERT ALL}) 한 문장의 최대 행 수(WD-737). Oracle 계열은 INSERT ALL 이 999 컬럼까지라
   * 행당 컬럼 2개면 499행이 한계이고, 바인드도 한도에 바짝 붙지 않게 {@link #SIZE} 와 같은 절반 여유(250행 = 바인드 500)를 둔다.
   */
  public static final int INSERT_ROWS = 250;

  private InChunks() {}

  /**
   * 중복을 걷지 않고 입력 순서대로 {@code size} 개 이하 조각으로 나눈다. 빈 입력이면 빈 목록. 일괄 INSERT 용이다 — 중복 id 는 걸러 주지 말고 PK
   * 위반으로 드러나야 행별 삽입·jOOQ 판과 동작이 같다. (IN 목록용 {@link #of} 는 중복을 걷는다.)
   */
  public static <T> List<List<T>> ordered(List<T> values, int size) {
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < values.size(); i += size) {
      out.add(values.subList(i, Math.min(i + size, values.size())));
    }
    return out;
  }

  /** 중복을 걷고(처음 나온 순서 유지) {@value #SIZE} 개 이하 조각으로 나눈다. 빈 입력이면 빈 목록. */
  public static <T> List<List<T>> of(Collection<T> values) {
    List<T> distinct = new ArrayList<>(new LinkedHashSet<>(values));
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < distinct.size(); i += SIZE) {
      out.add(distinct.subList(i, Math.min(i + SIZE, distinct.size())));
    }
    return out;
  }
}
