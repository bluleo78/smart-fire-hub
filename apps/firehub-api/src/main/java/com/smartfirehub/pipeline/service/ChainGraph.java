package com.smartfirehub.pipeline.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테넌트의 활성 PIPELINE_CHAIN 연쇄를 메모리 그래프로 들고 순환·길이를 판정한다(WD-106).
 *
 * <p>간선 「upstream → downstream」은 "upstream 이 끝나면 downstream 을 실행"이다. 저장 시 검사가 노드마다 DB 를 조회하던
 * 것(N+1)을 피하려고, 호출부가 간선을 한 번에 읽어 넘기면 이 클래스가 DB 없이 판정한다 — 그래서 단위 테스트로 그래프 규칙을 직접 검증할 수 있다.
 *
 * <p><b>길이의 의미</b>: 실행 시 깊이는 연쇄를 따라 실제로 발화되는 단계 수(걸음 수)다. 순환에 닿는 줄기는 실행에서 끝없이 이어지므로 길이를 {@link
 * #UNBOUNDED} 로 본다. 순환에 닿지 않는 줄기는 가장 긴 경로의 간선 수를 정확히 센다.
 */
final class ChainGraph {

  /** 순환에 닿아 끝없이 이어지는 줄기의 길이. */
  static final int UNBOUNDED = Integer.MAX_VALUE;

  /** 활성 연쇄 트리거 하나 = 간선 하나. */
  record Edge(long triggerId, long upstream, long downstream) {}

  /** 새 간선을 더했을 때의 판정. */
  sealed interface Verdict {}

  /** 더해도 된다. */
  record Ok() implements Verdict {}

  /** 새 간선이 순환을 만든다. path 는 upstream 에서 시작해 upstream 으로 돌아오는 파이프라인 id 목록. */
  record NewCycle(List<Long> path) implements Verdict {}

  /** 새 간선이 이어지는 줄기에 이미 순환이 있다(결함 이전에 저장된 것). path 는 그 순환(시작 노드로 돌아옴). */
  record ExistingCycle(List<Long> path) implements Verdict {}

  /** 새 간선이 들어가는 가장 긴 줄기가 상한을 넘는다. */
  record TooLong(int length) implements Verdict {}

  private final Map<Long, Set<Long>> forward = new HashMap<>();
  private final Map<Long, Set<Long>> backward = new HashMap<>();

  private ChainGraph() {}

  /**
   * 간선 목록으로 그래프를 만든다.
   *
   * @param excludeTriggerId 수정 중인 트리거의 <b>지금</b> 간선을 뺀다(null 이면 빼지 않음). 상류를 바꾸는 수정에서 옛 간선이 남아 있으면 그
   *     수정으로 사라질 순환까지 있는 것처럼 판정된다.
   */
  static ChainGraph of(Collection<Edge> edges, Long excludeTriggerId) {
    ChainGraph graph = new ChainGraph();
    for (Edge e : edges) {
      if (excludeTriggerId != null && e.triggerId() == excludeTriggerId) {
        continue;
      }
      graph.forward.computeIfAbsent(e.upstream(), k -> new LinkedHashSet<>()).add(e.downstream());
      graph.backward.computeIfAbsent(e.downstream(), k -> new LinkedHashSet<>()).add(e.upstream());
    }
    return graph;
  }

  /**
   * config 의 upstreamPipelineId 원값을 파이프라인 id 로 해석한다. 없으면 null, 숫자로 읽을 수 없으면 null.
   *
   * <p>실행 시 조회는 {@code (config->>'upstreamPipelineId')::bigint} 로 읽는다 — JSON 숫자든 숫자 문자열({@code
   * "123"}, 앞뒤 공백 포함)이든 살아 있는 간선이다. 저장 시 검사·그래프 적재가 같은 기준으로 읽어야 문자열 상류가 검사를 건너뛰지 않는다.
   */
  static Long parseUpstreamIdOrNull(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(raw.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** {@link #parseUpstreamIdOrNull} 의 엄격한 판 — 값이 있는데 숫자로 읽을 수 없으면 잘못된 요청(400)으로 거부한다. */
  static Long parseUpstreamId(Object raw) {
    Long id = parseUpstreamIdOrNull(raw);
    if (raw != null && id == null) {
      throw new IllegalArgumentException("먼저 끝나야 할 파이프라인 id(upstreamPipelineId)는 숫자여야 합니다.");
    }
    return id;
  }

  /**
   * 새 간선 「upstream → target」을 더해도 되는지 판정한다.
   *
   * <p>순서: (1) target 에서 하류로 upstream 에 닿으면 새 순환. (2) 새 간선이 이어지는 줄기(upstream 위쪽·target 아래쪽)에 이미 순환이
   * 있으면 그 순환. (3) (upstream 위쪽 최장) + 1 + (target 아래쪽 최장) 이 상한을 넘으면 너무 김. 다이아몬드(같은 하류로 가는 두 경로)는 순환이
   * 아니므로 통과한다.
   */
  Verdict check(long target, long upstream, int maxDepth) {
    List<Long> path = findPath(target, upstream);
    if (path != null) {
      List<Long> cycle = new ArrayList<>();
      cycle.add(upstream);
      cycle.addAll(path);
      return new NewCycle(cycle);
    }
    int down = longestFrom(target, true);
    if (down == UNBOUNDED) {
      return new ExistingCycle(findReachableCycle(target, true));
    }
    int up = longestFrom(upstream, false);
    if (up == UNBOUNDED) {
      return new ExistingCycle(findReachableCycle(upstream, false));
    }
    int length = up + 1 + down;
    return length > maxDepth ? new TooLong(length) : new Ok();
  }

  /** from 에서 하류를 따라 to 에 닿는 가장 짧은 경로(from … to)를 너비 우선으로 찾는다. 없으면 null. 방문 집합이 있어 순환이 있어도 끝난다. */
  List<Long> findPath(long from, long to) {
    Map<Long, Long> parent = new HashMap<>();
    Deque<Long> queue = new ArrayDeque<>();
    parent.put(from, null);
    queue.add(from);
    while (!queue.isEmpty()) {
      Long current = queue.poll();
      for (Long next : next(current, true)) {
        if (parent.containsKey(next)) {
          continue;
        }
        parent.put(next, current);
        if (next == to) {
          LinkedList<Long> path = new LinkedList<>();
          for (Long node = next; node != null; node = parent.get(node)) {
            path.addFirst(node);
          }
          return path;
        }
        queue.add(next);
      }
    }
    return null;
  }

  /**
   * node 에서 한 방향(downstream=true 면 하류, false 면 상류)으로 이어지는 가장 긴 줄기의 간선 수. 순환에 닿으면 {@link
   * #UNBOUNDED}.
   *
   * <p>세 가지 색 깊이 우선 탐색이다. 탐색 중(회색) 노드로 돌아오는 간선은 순환이므로 그 줄기는 끝없이 이어진다. 끝난(검정) 노드의 값만 기억하는데, 그 값은
   * "건너뛴 노드" 없이 계산된 것이라 다른 경로에서 다시 써도 정확하다(예전 구현은 따라가는 중인 노드를 건너뛴 채 계산한 값을 기억해, 그래프에 순환이 있으면 다른 갈래의
   * 길이를 짧게 셀 수 있었다).
   */
  int longestFrom(long node, boolean downstream) {
    return longest(node, downstream, new HashMap<>(), new HashSet<>());
  }

  private int longest(long node, boolean downstream, Map<Long, Integer> done, Set<Long> gray) {
    Integer cached = done.get(node);
    if (cached != null) {
      return cached;
    }
    gray.add(node);
    int best = 0;
    for (Long next : next(node, downstream)) {
      int sub = gray.contains(next) ? UNBOUNDED : longest(next, downstream, done, gray);
      if (sub == UNBOUNDED) {
        best = UNBOUNDED;
        // 이미 끝없는 줄기로 확정됐지만, 다른 이웃을 마저 보지 않아도 값은 같다.
        break;
      }
      best = Math.max(best, sub + 1);
    }
    gray.remove(node);
    done.put(node, best);
    return best;
  }

  /**
   * start 에서 한 방향으로 닿는 순환 하나를 하류 방향 순서(시작 노드로 돌아옴)로 돌려준다. 안내 문구용이며 {@link #longestFrom} 이 {@link
   * #UNBOUNDED} 를 돌려준 경우에만 부른다.
   */
  List<Long> findReachableCycle(long start, boolean downstream) {
    List<Long> stack = new ArrayList<>();
    List<Long> cycle = cycleDfs(start, downstream, stack, new HashSet<>());
    if (cycle == null) {
      return List.of();
    }
    if (!downstream) {
      // 상류 방향으로 찾은 순환은 간선 방향이 거꾸로다 — 실행 순서대로 뒤집는다.
      Collections.reverse(cycle);
    }
    return cycle;
  }

  private List<Long> cycleDfs(long node, boolean downstream, List<Long> stack, Set<Long> finished) {
    stack.add(node);
    for (Long next : next(node, downstream)) {
      int at = stack.indexOf(next);
      if (at >= 0) {
        List<Long> cycle = new ArrayList<>(stack.subList(at, stack.size()));
        cycle.add(next);
        return cycle;
      }
      if (!finished.contains(next)) {
        List<Long> found = cycleDfs(next, downstream, stack, finished);
        if (found != null) {
          return found;
        }
      }
    }
    stack.remove(stack.size() - 1);
    finished.add(node);
    return null;
  }

  private Set<Long> next(long node, boolean downstream) {
    return (downstream ? forward : backward).getOrDefault(node, Set.of());
  }
}
