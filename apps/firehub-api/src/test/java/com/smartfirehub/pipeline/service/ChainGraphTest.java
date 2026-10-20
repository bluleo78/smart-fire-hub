package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.service.ChainGraph.Edge;
import com.smartfirehub.pipeline.service.ChainGraph.ExistingCycle;
import com.smartfirehub.pipeline.service.ChainGraph.NewCycle;
import com.smartfirehub.pipeline.service.ChainGraph.Ok;
import com.smartfirehub.pipeline.service.ChainGraph.TooLong;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 연쇄 그래프 순수 판정(WD-106) 단위 테스트 — DB 없이 순환·다이아몬드·길이·id 해석 규칙을 본다.
 *
 * <p>표기: {@code e(A, B)} = "A 가 끝나면 B 실행" 간선 A → B. {@code check(target, upstream)} 은 새 간선 upstream
 * → target 을 더해도 되는지다.
 */
class ChainGraphTest {

  private static final int MAX = 10;
  private long nextTriggerId = 1;

  private Edge e(long upstream, long downstream) {
    return new Edge(nextTriggerId++, upstream, downstream);
  }

  private static ChainGraph graph(Edge... edges) {
    return ChainGraph.of(List.of(edges), null);
  }

  // ------------------------------------------------------------------ 순환

  @Test
  void twoCycle_isNewCycleStartingAtUpstream() {
    // A(1) → B(2) 가 있을 때 B → A 를 더하면 B → A → B.
    assertThat(graph(e(1, 2)).check(1, 2, MAX)).isEqualTo(new NewCycle(List.of(2L, 1L, 2L)));
  }

  @Test
  void threeCycle_isNewCycle() {
    // 1 → 2 → 3 에 3 → 1.
    assertThat(graph(e(1, 2), e(2, 3)).check(1, 3, MAX))
        .isEqualTo(new NewCycle(List.of(3L, 1L, 2L, 3L)));
  }

  @Test
  void diamond_isOk() {
    // 결함 보고의 재현: 10 → 11 → 2 가 있을 때 10 → 2 는 순환이 아니다.
    assertThat(graph(e(10, 11), e(11, 2)).check(2, 10, MAX)).isInstanceOf(Ok.class);
    // 1 → 2 → 4, 1 → 3 에 3 → 4 (합류).
    assertThat(graph(e(1, 2), e(2, 4), e(1, 3)).check(4, 3, MAX)).isInstanceOf(Ok.class);
  }

  @Test
  void excludedTrigger_edgeIsIgnored() {
    // 수정 중인 트리거의 지금 간선(1 → 2)은 바뀔 것이므로 빼고 본다 — 빼지 않으면 2 → 1 이 순환으로 보인다.
    Edge current = e(1, 2);
    assertThat(ChainGraph.of(List.of(current), current.triggerId()).check(1, 2, MAX))
        .isInstanceOf(Ok.class);
    assertThat(ChainGraph.of(List.of(current), null).check(1, 2, MAX)).isInstanceOf(NewCycle.class);
  }

  // ------------------------------------------------------------------ 길이

  @Test
  void length_countsBothSidesOfTheNewEdge() {
    // 0 → 1 → … → 10 (10단계).
    List<Edge> line = new ArrayList<>();
    for (long i = 0; i < 10; i++) {
      line.add(e(i, i + 1));
    }
    ChainGraph g = ChainGraph.of(line, null);
    assertThat(g.check(100, 10, MAX)).isEqualTo(new TooLong(11)); // 꼬리 10 → 100
    assertThat(g.check(0, 100, MAX)).isEqualTo(new TooLong(11)); // 머리 100 → 0
    assertThat(g.check(100, 4, MAX)).isInstanceOf(Ok.class); // 가지 4 → 100 = 4 + 1
    assertThat(g.check(5, 3, MAX)).isInstanceOf(Ok.class); // 지름길 3 → 5 = 3 + 1 + 5 = 9
  }

  @Test
  void longest_isNotHiddenByVisitOrder() {
    // 1 → 2 → 5, 1 → 3 → 4 → 5 → 6. 먼저 닿은 짧은 경로가 긴 경로를 가리면 안 된다: 1 에서 최장은 1-3-4-5-6 = 4.
    ChainGraph g = graph(e(1, 2), e(2, 5), e(1, 3), e(3, 4), e(4, 5), e(5, 6));
    assertThat(g.longestFrom(1, true)).isEqualTo(4);
    assertThat(g.longestFrom(6, false)).isEqualTo(4);
  }

  @Test
  void existingCycle_makesReachingBranchUnbounded_andKeepsUnrelatedBranchExact() {
    // 그래프에 이미 순환(50 ↔ 51)과, 그 순환에서 갈라져 나온 51 → 1 → 2 → … → 10 이 있다.
    // 예전 구현은 "따라가는 중" 노드를 건너뛴 채 계산한 값을 기억해, 순환에 닿는 줄기를 유한한(짧은) 값으로 셌다(리뷰 3번).
    // 실행 시에는 순환에 닿는 줄기가 끝없이 이어지므로 UNBOUNDED 가 맞고, 순환에 닿지 않는 하류(1 → … → 10)는 정확히 센다.
    List<Edge> edges = new ArrayList<>(List.of(e(50, 51), e(51, 50), e(51, 1)));
    for (long i = 1; i < 10; i++) {
      edges.add(e(i, i + 1));
    }
    ChainGraph g = ChainGraph.of(edges, null);
    assertThat(g.longestFrom(50, true)).isEqualTo(ChainGraph.UNBOUNDED);
    assertThat(g.longestFrom(51, true)).isEqualTo(ChainGraph.UNBOUNDED);
    assertThat(g.longestFrom(1, true)).isEqualTo(9);
    assertThat(g.longestFrom(10, false)).isEqualTo(ChainGraph.UNBOUNDED); // 위쪽이 순환에 닿는다

    // 순환과 무관한 갈래: 고립된 60 → 61 → 62 에 63 을 붙이면 정확히 3단계.
    edges.addAll(List.of(e(60, 61), e(61, 62)));
    ChainGraph g2 = ChainGraph.of(edges, null);
    assertThat(g2.longestFrom(60, true)).isEqualTo(2);
    assertThat(g2.check(63, 62, MAX)).isInstanceOf(Ok.class);
    assertThat(g2.check(63, 62, 2)).isEqualTo(new TooLong(3));
  }

  @Test
  void existingCycleOnTheNewEdgesLine_isReportedWithItsPath() {
    // 결함 이전에 저장된 순환 7 ↔ 8. 그 순환으로 이어지는 3 → 7 을 더하면 실행이 끝없이 이어진다 — 그 순환을 안내한다.
    ChainGraph g = graph(e(7, 8), e(8, 7));
    assertThat(g.check(7, 3, MAX)).isEqualTo(new ExistingCycle(List.of(7L, 8L, 7L)));
    // 위쪽에 순환이 있는 경우(8 → 9)도 실행 순서대로 보여 준다.
    assertThat(g.check(9, 8, MAX)).isInstanceOf(ExistingCycle.class);
    List<Long> upCycle = ((ExistingCycle) g.check(9, 8, MAX)).path();
    assertThat(upCycle).containsExactly(8L, 7L, 8L); // 8 → 7 → 8: 상류 방향으로 찾아도 실행 순서다
  }

  // ------------------------------------------------------------------ id 해석

  @Test
  void upstreamId_isParsedLikeBigintCast() {
    assertThat(ChainGraph.parseUpstreamId(12)).isEqualTo(12L);
    assertThat(ChainGraph.parseUpstreamId(12L)).isEqualTo(12L);
    assertThat(ChainGraph.parseUpstreamId("12")).isEqualTo(12L);
    assertThat(ChainGraph.parseUpstreamId(" 7 ")).isEqualTo(7L);
    assertThat(ChainGraph.parseUpstreamId(null)).isNull();
    assertThatThrownBy(() -> ChainGraph.parseUpstreamId("abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("숫자");
    assertThat(ChainGraph.parseUpstreamIdOrNull("abc")).isNull();
  }
}
