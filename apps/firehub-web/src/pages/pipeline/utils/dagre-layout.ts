import dagre from '@dagrejs/dagre';
import type { Edge,Node } from '@xyflow/react';

export function getLayoutedElements(
  nodes: Node[],
  edges: Edge[],
  direction: 'LR' | 'TB' = 'LR',
  // 실제 노드 크기에 맞춰 dagre 배치·오프셋을 계산하기 위한 파라미터.
  // 기본값(220/100)은 파이프라인 StepNode 기준 — 온톨로지 등 다른 폭의 노드는 값을 넘겨 겹침을 방지한다.
  nodeWidth = 220,
  nodeHeight = 100,
): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: direction, nodesep: 60, ranksep: 150 });

  nodes.forEach((node) => g.setNode(node.id, { width: nodeWidth, height: nodeHeight }));
  edges.forEach((edge) => g.setEdge(edge.source, edge.target));
  dagre.layout(g);

  const layoutedNodes = nodes.map((node) => {
    const pos = g.node(node.id);
    return { ...node, position: { x: pos.x - nodeWidth / 2, y: pos.y - nodeHeight / 2 } };
  });

  return { nodes: layoutedNodes, edges };
}
