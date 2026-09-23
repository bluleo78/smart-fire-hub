package com.smartfirehub.global.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * RRF(Reciprocal Rank Fusion) 공통 유틸.
 *
 * <p>점수 척도가 다른 검색(코사인·word_similarity)을 순위만으로 합친다. 문서·데이터셋 카탈로그·행 검색이 같은
 * 상수(k=60)와 동점 규칙(키 오름차순)을 공유하도록 한곳에 둔다.
 */
public final class RankFusion {

  /** RRF 표준 권장 상수. */
  public static final int RRF_K = 60;

  private RankFusion() {}

  /**
   * 융합 결과 한 건.
   *
   * @param hit 처음 등장한 리스트의 원본 항목
   * @param score 누적 RRF 점수
   * @param sources 이 항목이 등장한 입력 리스트 인덱스(0-based) — "어느 검색에서 걸렸나" 설명용
   */
  public record Fused<T>(T hit, double score, Set<Integer> sources) {}

  /** 각 리스트에서의 순위(0-based)로 1/(k+rank+1)을 누적해 점수 내림차순, 동점은 키 오름차순으로 limit 개를 반환한다. */
  public static <T> List<Fused<T>> fuse(List<List<T>> rankings, Function<T, Long> keyOf, int limit) {
    Map<Long, Double> scoreByKey = new LinkedHashMap<>();
    Map<Long, T> hitByKey = new LinkedHashMap<>();
    Map<Long, Set<Integer>> sourcesByKey = new LinkedHashMap<>();
    for (int list = 0; list < rankings.size(); list++) {
      List<T> ranking = rankings.get(list);
      for (int rank = 0; rank < ranking.size(); rank++) {
        T hit = ranking.get(rank);
        Long key = keyOf.apply(hit);
        scoreByKey.merge(key, 1.0 / (RRF_K + rank + 1), Double::sum);
        hitByKey.putIfAbsent(key, hit);
        sourcesByKey.computeIfAbsent(key, k -> new TreeSet<>()).add(list);
      }
    }
    List<Map.Entry<Long, Double>> entries = new ArrayList<>(scoreByKey.entrySet());
    entries.sort(
        (x, y) -> {
          int byScore = Double.compare(y.getValue(), x.getValue()); // 점수 내림차순
          return byScore != 0 ? byScore : Long.compare(x.getKey(), y.getKey()); // 동점 → 키 오름차순
        });
    List<Fused<T>> result = new ArrayList<>();
    for (Map.Entry<Long, Double> e : entries) {
      if (result.size() >= limit) break;
      result.add(new Fused<>(hitByKey.get(e.getKey()), e.getValue(), sourcesByKey.get(e.getKey())));
    }
    return result;
  }
}
