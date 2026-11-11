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

  private InChunks() {}

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
