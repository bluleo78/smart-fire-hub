import '@xyflow/react/dist/style.css';

import { Background, Controls, type Edge, MarkerType, type Node, ReactFlow, ReactFlowProvider } from '@xyflow/react';
import { useTheme } from 'next-themes';
import { useMemo } from 'react';

import { getLayoutedElements } from '@/pages/pipeline/utils/dagre-layout';
import type { OntologySchema } from '@/types/ontology';

import { ENTITY_NODE_WIDTH, EntityNode } from './EntityNode';

interface Props {
  schema: OntologySchema;
  onTypeClick?: (type: string) => void;
}

// 커스텀 노드 타입은 모듈 스코프에 한 번만 정의(렌더마다 재생성 시 React Flow 경고·리마운트 방지).
const nodeTypes = { entity: EntityNode };
// 커스텀 노드 높이(px) — dagre 오프셋 계산용. EntityNode 실제 렌더 높이와 대략 일치.
const ENTITY_NODE_HEIGHT = 44;

// 온톨로지 스키마 다이어그램 — 타입 노드 + 허용 트리플 엣지를 dagre 계층형(TB)으로 배치한다.
// 읽기 전용(드래그/연결 불가) — 노드 클릭 시 onTypeClick으로 드릴다운을 위임한다.
function SchemaGraphInner({ schema, onTypeClick }: Props) {
  const { resolvedTheme } = useTheme();

  const { nodes, edges } = useMemo(() => {
    // 타입 노드: 커스텀 EntityNode로 렌더(테마 인지 tint 배경 + 타입색 border + 대비 통과 텍스트).
    const rawNodes: Node[] = schema.entities.map((e) => ({
      id: e.type,
      type: 'entity',
      data: { label: e.type, type: e.type },
      position: { x: 0, y: 0 },
    }));
    // 트리플 엣지: subject → object 방향 화살표 + relation 라벨(가독성 위해 12px + 반투명 배경).
    const rawEdges: Edge[] = schema.relations.map((r, i) => ({
      id: `t${i}`,
      source: r.subject,
      target: r.object,
      label: r.relation,
      markerEnd: { type: MarkerType.ArrowClosed },
      labelStyle: { fontSize: 12 },
      labelBgStyle: { fill: 'var(--background)', fillOpacity: 0.8 },
      labelBgPadding: [4, 2] as [number, number],
    }));
    // dagre로 상하 계층 배치 — 커스텀 노드 실제 폭/높이를 넘겨 겹침을 방지한다.
    return getLayoutedElements(rawNodes, rawEdges, 'TB', ENTITY_NODE_WIDTH, ENTITY_NODE_HEIGHT);
  }, [schema]);

  return (
    <div style={{ width: '100%', height: '100%' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        colorMode={resolvedTheme as 'light' | 'dark' | 'system'}
        fitView
        proOptions={{ hideAttribution: true }}
        onNodeClick={(_e, node) => onTypeClick?.(node.id)}
        nodesDraggable={false}
        nodesConnectable={false}
      >
        <Background color={resolvedTheme === 'dark' ? 'oklch(1 0 0 / 8%)' : undefined} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}

// ReactFlow 컨텍스트(내부 상태 훅) 사용을 위해 Provider로 감싼다.
export default function SchemaGraph(props: Props) {
  return (
    <ReactFlowProvider>
      <SchemaGraphInner {...props} />
    </ReactFlowProvider>
  );
}
