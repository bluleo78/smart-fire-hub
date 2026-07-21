import { colorForType } from '@/lib/ontology-colors';
import type { GraphEdge, GraphNode } from '@/types/ontology';

interface Props {
  node: GraphNode | null;
  edges: GraphEdge[];
  nodesByKey: Map<string, GraphNode>;
  onClose: () => void;
}

// 노드 상세 우측 드로어 — 선택 노드의 속성과 인접 관계(들어오는/나가는 트리플)를 보여준다(읽기 전용).
export default function NodeDetailDrawer({ node, edges, nodesByKey, onClose }: Props) {
  if (!node) return null;

  // 이 노드가 주어(subject)인 관계와 목적어(object)인 관계를 각각 수집한다.
  const outgoing = edges.filter((e) => e.subjectKey === node.key);
  const incoming = edges.filter((e) => e.objectKey === node.key);
  const nameOf = (k: string) => nodesByKey.get(k)?.name ?? k;

  return (
    <aside className="w-72 shrink-0 border-l bg-background p-4 overflow-y-auto" data-testid="node-detail-drawer">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="w-3 h-3 rounded-full" style={{ background: colorForType(node.type) }} />
          <span className="font-semibold text-sm">{node.type}</span>
        </div>
        <button onClick={onClose} className="text-muted-foreground text-sm" aria-label="닫기">
          ✕
        </button>
      </div>
      <div className="text-sm mb-2">{node.name}</div>
      <div className="text-xs text-muted-foreground break-all mb-3">{node.key}</div>
      <div className="text-xs text-muted-foreground mb-3">출처 청크 {node.sourceChunkCount}개</div>
      <div className="text-xs font-medium uppercase text-muted-foreground mb-1">관계</div>
      <ul className="text-xs space-y-1">
        {outgoing.map((e, i) => (
          <li key={`o${i}`}>
            → {e.type} → {nameOf(e.objectKey)}
          </li>
        ))}
        {incoming.map((e, i) => (
          <li key={`i${i}`}>
            ← {e.type} ← {nameOf(e.subjectKey)}
          </li>
        ))}
        {outgoing.length + incoming.length === 0 && <li className="text-muted-foreground">관계 없음</li>}
      </ul>
    </aside>
  );
}
