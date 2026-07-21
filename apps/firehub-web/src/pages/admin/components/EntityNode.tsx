import { Handle, type Node, type NodeProps, Position } from '@xyflow/react';
import { useTheme } from 'next-themes';
import { memo } from 'react';

import { entityColorSet } from '@/lib/ontology-colors';

// 커스텀 노드 데이터 — 스키마(타입명 라벨)·인스턴스(개체명 라벨) 양쪽에서 공용으로 사용한다.
// 인스턴스 그래프는 클릭 시 상세 드로어를 열기 위해 원본 노드(raw)도 함께 실어 보낸다.
export interface EntityNodeData extends Record<string, unknown> {
  label: string;
  type: string;
}

export type EntityNodeType = Node<EntityNodeData, 'entity'>;

// 커스텀 노드 폭 — dagre 레이아웃(SchemaGraph)에 넘기는 nodeWidth와 반드시 일치시켜야 겹침/여백이 생기지 않는다.
export const ENTITY_NODE_WIDTH = 160;

// 온톨로지 엔티티 노드 — StepNode 방식(옅은 tint 배경 + 타입색 border + 대비 통과 텍스트).
// 하드코딩 #fff 대신 테마별 700/300 shade 텍스트를 써 WCAG AA(4.5:1)를 통과한다. 읽기 전용이라 Handle은 숨긴다.
export const EntityNode = memo(function EntityNode({ data }: NodeProps<EntityNodeType>) {
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';
  const { base, text, tint } = entityColorSet(data.type, isDark);

  return (
    <div
      className="rounded-lg border px-3 py-1.5 text-center shadow-sm"
      style={{
        width: ENTITY_NODE_WIDTH,
        backgroundColor: tint,
        borderColor: base,
      }}
    >
      {/* 읽기 전용 노드 — 연결점은 숨기되(opacity 0) React Flow 엣지 앵커는 유지한다. */}
      <Handle type="target" position={Position.Top} style={{ opacity: 0 }} isConnectable={false} />
      {/* 라벨은 실제 텍스트 노드로 렌더(E2E가 hasText로 탐색). 개체명이 길 수 있어 truncate 처리. */}
      <span className="block truncate text-xs font-semibold" style={{ color: text }} title={data.label}>
        {data.label}
      </span>
      <Handle type="source" position={Position.Bottom} style={{ opacity: 0 }} isConnectable={false} />
    </div>
  );
});
