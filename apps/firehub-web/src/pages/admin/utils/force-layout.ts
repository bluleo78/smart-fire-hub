import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, type SimulationNodeDatum } from 'd3-force';

import type { GraphEdge, GraphNode } from '@/types/ontology';

export interface PositionedNode extends GraphNode {
  x: number;
  y: number;
}

// d3-force 시뮬레이션 중간 타입 — x/y를 비워 두면(undefined) d3가 초기 배치(phyllotaxis 나선)를
// 자동으로 적용한다. 미리 0,0으로 채우면 모든 노드가 원점에서 겹쳐 시작해 배치가 나빠지므로 optional로 둔다.
type SimNode = GraphNode & SimulationNodeDatum;

// d3-force forceLink가 요구하는 링크 형태 — source/target은 노드의 key(id)로 지정한다.
interface SimLink {
  source: string;
  target: string;
}

// d3-force로 노드 좌표를 1회 계산한다(정적 배치 — 시뮬레이션을 끝까지 돌린 뒤 좌표를 확정하고 애니메이션 없이 렌더링에 사용).
export function computeForceLayout(nodes: GraphNode[], edges: GraphEdge[]): PositionedNode[] {
  const sim: SimNode[] = nodes.map((n) => ({ ...n }));
  const keys = new Set(nodes.map((n) => n.key));
  const links: SimLink[] = edges
    .filter((e) => keys.has(e.subjectKey) && keys.has(e.objectKey)) // 필터로 사라진 노드의 엣지 제외
    .map((e) => ({ source: e.subjectKey, target: e.objectKey }));

  forceSimulation(sim)
    .force('charge', forceManyBody().strength(-400))
    .force(
      'link',
      forceLink<SimNode, SimLink>(links)
        .id((d) => d.key)
        .distance(120),
    )
    .force('center', forceCenter(0, 0))
    .force('collide', forceCollide(46))
    .stop()
    .tick(300);

  return sim as PositionedNode[];
}
