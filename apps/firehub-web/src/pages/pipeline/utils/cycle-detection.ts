/**
 * 엣지 추가 전에 사이클 발생 여부를 검사한다.
 * newTarget에서 BFS를 시작하여 newSource에 도달 가능하면 사이클이 발생한다.
 *
 * @returns true if adding the edge would create a cycle
 */
export function wouldCreateCycle(
  edges: { source: string; target: string }[],
  newSource: string,
  newTarget: string,
): boolean {
  if (newSource === newTarget) return true;

  // Build adjacency list from existing edges
  const adj = new Map<string, string[]>();
  for (const edge of edges) {
    const targets = adj.get(edge.source) ?? [];
    targets.push(edge.target);
    adj.set(edge.source, targets);
  }

  // BFS from newTarget: can we reach newSource?
  const visited = new Set<string>();
  const queue = [newTarget];

  while (queue.length > 0) {
    const current = queue.shift()!;
    if (current === newSource) return true;
    if (visited.has(current)) continue;
    visited.add(current);

    const neighbors = adj.get(current) ?? [];
    for (const neighbor of neighbors) {
      if (!visited.has(neighbor)) {
        queue.push(neighbor);
      }
    }
  }

  return false;
}
