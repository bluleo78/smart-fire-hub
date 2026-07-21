import '@xyflow/react/dist/style.css';

import { Background, Controls, type Edge, MarkerType, type Node, ReactFlow, ReactFlowProvider } from '@xyflow/react';
import { useMemo } from 'react';

import { colorForType } from '@/lib/ontology-colors';
import { getLayoutedElements } from '@/pages/pipeline/utils/dagre-layout';
import type { OntologySchema } from '@/types/ontology';

interface Props {
  schema: OntologySchema;
  onTypeClick?: (type: string) => void;
}

// 온톨로지 스키마 다이어그램 — 타입 노드 + 허용 트리플 엣지를 dagre 계층형(TB)으로 배치한다.
// 읽기 전용(드래그/연결 불가) — 노드 클릭 시 onTypeClick으로 드릴다운을 위임한다.
function SchemaGraphInner({ schema, onTypeClick }: Props) {
  const { nodes, edges } = useMemo(() => {
    // 타입 노드: colorForType으로 색상 배경 지정(범례·인스턴스 그래프와 색상 일관성 유지).
    const rawNodes: Node[] = schema.entities.map((e) => ({
      id: e.type,
      data: { label: e.type },
      position: { x: 0, y: 0 },
      style: {
        background: colorForType(e.type),
        color: '#fff',
        border: 'none',
        borderRadius: 8,
        fontWeight: 600,
        fontSize: 12,
        width: 120,
      },
    }));
    // 트리플 엣지: subject → object 방향 화살표 + relation 라벨.
    const rawEdges: Edge[] = schema.relations.map((r, i) => ({
      id: `t${i}`,
      source: r.subject,
      target: r.object,
      label: r.relation,
      markerEnd: { type: MarkerType.ArrowClosed },
      labelStyle: { fontSize: 10 },
    }));
    // dagre로 상하 계층 배치.
    return getLayoutedElements(rawNodes, rawEdges, 'TB');
  }, [schema]);

  return (
    <div style={{ width: '100%', height: '100%' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        proOptions={{ hideAttribution: true }}
        onNodeClick={(_e, node) => onTypeClick?.(node.id)}
        nodesDraggable={false}
        nodesConnectable={false}
      >
        <Background />
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
