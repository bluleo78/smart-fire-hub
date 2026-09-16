import type { GraphData, GraphNode } from '@/types/ontology';

/**
 * 그래프를 노드 조건으로 좁힌다 — 조건을 통과한 노드만 남기고, 엣지는 양쪽 노드가 모두 남아있을 때만
 * 유지한다("고아 엣지" 방지). OntologyPage(온톨로지 선택 스코프)와 InstanceGraph(타입/검색 필터)가
 * 각자 "노드 필터 → 양끝 생존 엣지만 유지"를 따로 구현해 중복돼 있던 것을 하나로 모았다.
 */
export function filterGraphByNode(graph: GraphData, predicate: (node: GraphNode) => boolean): GraphData {
  const nodes = graph.nodes.filter(predicate);
  const visible = new Set(nodes.map((n) => n.key));
  const edges = graph.edges.filter((e) => visible.has(e.subjectKey) && visible.has(e.objectKey));
  return { nodes, edges };
}
