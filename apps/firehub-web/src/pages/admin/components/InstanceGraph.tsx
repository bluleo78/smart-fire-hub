import '@xyflow/react/dist/style.css';

import { Background, Controls, type Edge, MarkerType, type Node, ReactFlow, ReactFlowProvider } from '@xyflow/react';
import { Network, SearchX } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useMemo } from 'react';

import type { GraphData, GraphNode } from '@/types/ontology';

import { computeForceLayout } from '../utils/force-layout';
import { EntityNode } from './EntityNode';

interface Props {
  graph: GraphData;
  activeTypes: Set<string>;
  search: string;
  onNodeSelect: (n: GraphNode) => void;
}

// 커스텀 노드 타입은 모듈 스코프에 한 번만 정의(렌더마다 재생성 시 React Flow 경고·리마운트 방지).
const nodeTypes = { entity: EntityNode };

// 인스턴스 그래프 캔버스 — force 좌표로 배치한 개체·관계를 React Flow로 렌더링한다.
// activeTypes(빈 Set이면 전체)·search(이름 부분일치, 대소문자 무시)로 필터링하며, 노드 클릭 시 상세 드로어 오픈을 위임한다.
function InstanceGraphInner({ graph, activeTypes, search, onNodeSelect }: Props) {
  const { resolvedTheme } = useTheme();

  const { nodes, edges } = useMemo(() => {
    // 타입 토글·검색 필터 적용(activeTypes 비어 있으면 전체 표시).
    const q = search.trim().toLowerCase();
    const filtered = graph.nodes.filter(
      (n) => (activeTypes.size === 0 || activeTypes.has(n.type)) && (q === '' || n.name.toLowerCase().includes(q)),
    );
    const visible = new Set(filtered.map((n) => n.key));
    const positioned = computeForceLayout(filtered, graph.edges);
    // 커스텀 EntityNode로 렌더 — 클릭 시 드로어를 열기 위해 원본 노드(raw)와 type을 data에 함께 싣는다.
    const rfNodes: Node[] = positioned.map((n) => ({
      id: n.key,
      type: 'entity',
      position: { x: n.x, y: n.y },
      data: { label: n.name, type: n.type, raw: n },
    }));
    // 엣지는 필터로 남은 노드 사이의 관계만 표시(양쪽 노드가 모두 보일 때만).
    const rfEdges: Edge[] = graph.edges
      .filter((e) => visible.has(e.subjectKey) && visible.has(e.objectKey))
      .map((e, i) => ({
        id: `e${i}`,
        source: e.subjectKey,
        target: e.objectKey,
        label: e.type,
        markerEnd: { type: MarkerType.ArrowClosed },
        labelStyle: { fontSize: 12 },
        labelBgStyle: { fill: 'var(--background)', fillOpacity: 0.8 },
        labelBgPadding: [4, 2] as [number, number],
      }));
    return { nodes: rfNodes, edges: rfEdges };
  }, [graph, activeTypes, search]);

  if (graph.nodes.length === 0) {
    // 적재된 그래프가 아예 없는 빈 상태 — 아이콘 + 메시지(하우스 empty 패턴).
    return (
      <div
        className="flex h-full flex-col items-center justify-center gap-3 text-center"
        data-testid="instance-graph-empty"
      >
        <Network className="h-10 w-10 text-muted-foreground" />
        <p className="text-sm font-medium">적재된 그래프가 없습니다.</p>
      </div>
    );
  }

  // 그래프 자체는 비어있지 않지만 타입 필터/검색 결과가 0건인 경우 — 빈 캔버스 대신 안내 메시지 표시.
  if (nodes.length === 0) {
    return (
      <div
        className="flex h-full flex-col items-center justify-center gap-3 text-center"
        data-testid="instance-graph-no-results"
      >
        <SearchX className="h-10 w-10 text-muted-foreground" />
        <p className="text-sm font-medium">조건에 맞는 노드가 없습니다.</p>
      </div>
    );
  }

  return (
    <div className="w-full h-full" data-testid="instance-graph">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        colorMode={resolvedTheme as 'light' | 'dark' | 'system'}
        fitView
        proOptions={{ hideAttribution: true }}
        nodesDraggable
        nodesConnectable={false}
        onNodeClick={(_e, node) => onNodeSelect((node.data as { raw: GraphNode }).raw)}
      >
        <Background color={resolvedTheme === 'dark' ? 'oklch(1 0 0 / 8%)' : undefined} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}

// ReactFlow 컨텍스트(내부 상태 훅) 사용을 위해 Provider로 감싼다.
export default function InstanceGraph(props: Props) {
  return (
    <ReactFlowProvider>
      <InstanceGraphInner {...props} />
    </ReactFlowProvider>
  );
}
