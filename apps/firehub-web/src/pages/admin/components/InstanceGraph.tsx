import '@xyflow/react/dist/style.css';

import { Background, Controls, type Edge, MarkerType, type Node, ReactFlow, ReactFlowProvider } from '@xyflow/react';
import { useMemo } from 'react';

import { colorForType } from '@/lib/ontology-colors';
import type { GraphData, GraphNode } from '@/types/ontology';

import { computeForceLayout } from '../utils/force-layout';

interface Props {
  graph: GraphData;
  activeTypes: Set<string>;
  search: string;
  onNodeSelect: (n: GraphNode) => void;
}

// 인스턴스 그래프 캔버스 — force 좌표로 배치한 개체·관계를 React Flow로 렌더링한다.
// activeTypes(빈 Set이면 전체)·search(이름 부분일치, 대소문자 무시)로 필터링하며, 노드 클릭 시 상세 드로어 오픈을 위임한다.
function InstanceGraphInner({ graph, activeTypes, search, onNodeSelect }: Props) {
  const { nodes, edges } = useMemo(() => {
    // 타입 토글·검색 필터 적용(activeTypes 비어 있으면 전체 표시).
    const q = search.trim().toLowerCase();
    const filtered = graph.nodes.filter(
      (n) => (activeTypes.size === 0 || activeTypes.has(n.type)) && (q === '' || n.name.toLowerCase().includes(q)),
    );
    const visible = new Set(filtered.map((n) => n.key));
    const positioned = computeForceLayout(filtered, graph.edges);
    const rfNodes: Node[] = positioned.map((n) => ({
      id: n.key,
      position: { x: n.x, y: n.y },
      data: { label: n.name, raw: n },
      style: {
        background: colorForType(n.type),
        color: '#fff',
        border: 'none',
        borderRadius: 20,
        fontSize: 11,
        width: 90,
        textAlign: 'center',
      },
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
        labelStyle: { fontSize: 9 },
      }));
    return { nodes: rfNodes, edges: rfEdges };
  }, [graph, activeTypes, search]);

  if (graph.nodes.length === 0) {
    return (
      <div
        className="flex items-center justify-center h-full text-muted-foreground text-sm"
        data-testid="instance-graph-empty"
      >
        적재된 그래프가 없습니다.
      </div>
    );
  }

  // 그래프 자체는 비어있지 않지만 타입 필터/검색 결과가 0건인 경우 — 빈 캔버스 대신 안내 메시지 표시.
  if (nodes.length === 0) {
    return (
      <div
        className="flex items-center justify-center h-full text-muted-foreground text-sm"
        data-testid="instance-graph-no-results"
      >
        조건에 맞는 노드가 없습니다.
      </div>
    );
  }

  return (
    <div className="w-full h-full" data-testid="instance-graph">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        proOptions={{ hideAttribution: true }}
        nodesDraggable
        nodesConnectable={false}
        onNodeClick={(_e, node) => onNodeSelect((node.data as { raw: GraphNode }).raw)}
      >
        <Background />
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
