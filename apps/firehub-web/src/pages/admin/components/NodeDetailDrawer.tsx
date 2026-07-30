import { X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { Button } from '@/components/ui/button';
import { colorForType } from '@/lib/ontology-colors';
import type { GraphEdge, GraphNode } from '@/types/ontology';

interface Props {
  node: GraphNode | null;
  edges: GraphEdge[];
  nodesByKey: Map<string, GraphNode>;
  onClose: () => void;
  // 관계 항목 클릭 시 인접 노드로 이동(대상 노드가 그래프에 있을 때만 활성).
  onNavigate?: (key: string) => void;
  // 현재 온톨로지 schema_version — node.schemaVersion과 비교해 구버전 적재 여부를 표시(5-4).
  currentSchemaVersion?: number;
}

// 인스펙터 리사이즈 폭 범위(px) — AI 패널 규약(w-80=320)을 기본값으로 한다.
const MIN_WIDTH = 260;
const MAX_WIDTH = 560;
const DEFAULT_WIDTH = 320;
// 키보드(화살표) 1회 조작당 변화량(px) — 드래그와 달리 이산 조작이므로 체감 가능한 단위로 잡는다(#332).
const KEY_STEP = 16;

/** 폭을 허용 범위로 clamp. */
const clampWidth = (w: number) => Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, w));

// 노드 상세 우측 인스펙터 — 선택 노드의 속성 + 인접 관계(들어오는/나가는 트리플)를 보여준다(읽기 전용).
// 좌측 엣지 드래그로 폭 조절(AISidePanel 패턴). 관계 항목은 대상 노드로 이동하는 버튼.
export default function NodeDetailDrawer({
  node,
  edges,
  nodesByKey,
  onClose,
  onNavigate,
  currentSchemaVersion,
}: Props) {
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  // 드래그 시작점(clientX)과 시작 폭을 보관 — null이면 드래그 중 아님.
  const dragRef = useRef<{ startX: number; startWidth: number } | null>(null);

  // window 리스너로 캔버스 밖 드래그까지 추적한다(핸들은 mount 시 1회만 바인딩).
  useEffect(() => {
    const onMove = (e: MouseEvent) => {
      const d = dragRef.current;
      if (!d) return;
      // 오른쪽 패널이므로 왼쪽으로 끌수록(clientX 감소) 넓어진다.
      setWidth(clampWidth(d.startWidth + (d.startX - e.clientX)));
    };
    const onUp = () => {
      if (!dragRef.current) return;
      dragRef.current = null;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, []);

  const startDrag = (e: React.MouseEvent) => {
    dragRef.current = { startX: e.clientX, startWidth: width };
    // 드래그 중 텍스트 선택/커서 깜빡임 방지.
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  };

  // 키보드 폭 조절(#332) — 마우스 전용이던 리사이즈 핸들에 window-splitter 조작을 부여한다.
  // 방향 규약: 이 인스펙터는 오른쪽 고정이라 드래그도 "왼쪽으로 끌면 넓어진다". 화살표도 이를 따라
  // ←가 폭 증가 / →가 폭 감소다. aria-valuenow(=폭)와 화살표 방향이 반대로 보이지만,
  // 사용자가 보는 것은 "핸들이 움직이는 방향"이므로 마우스와 일치시키는 편이 혼란이 적다.
  const onHandleKeyDown = (e: React.KeyboardEvent) => {
    let next: number | null = null;
    if (e.key === 'ArrowLeft') next = width + KEY_STEP;
    else if (e.key === 'ArrowRight') next = width - KEY_STEP;
    // Home/End는 노출한 aria-valuemin/max에 맞춘다 — Home=최소 폭, End=최대 폭.
    else if (e.key === 'Home') next = MIN_WIDTH;
    else if (e.key === 'End') next = MAX_WIDTH;
    if (next === null) return;
    // aside가 overflow-y-auto라 화살표/Home/End가 스크롤로 새는 것을 막는다.
    e.preventDefault();
    setWidth(clampWidth(next));
  };

  if (!node) return null;

  // 이 노드가 주어(subject)/목적어(object)인 관계를 각각 수집한다.
  const outgoing = edges.filter((e) => e.subjectKey === node.key);
  const incoming = edges.filter((e) => e.objectKey === node.key);
  const nameOf = (k: string) => nodesByKey.get(k)?.name ?? k;

  // 관계 대상 렌더 — 그래프에 존재하는 노드면 클릭 이동 버튼, 아니면 plain text.
  const target = (k: string) =>
    onNavigate && nodesByKey.has(k) ? (
      <button
        type="button"
        onClick={() => onNavigate(k)}
        className="text-left text-primary hover:underline"
      >
        {nameOf(k)}
      </button>
    ) : (
      <span>{nameOf(k)}</span>
    );

  return (
    <aside
      className="relative shrink-0 overflow-y-auto border-l bg-background"
      style={{ width }}
      data-testid="node-detail-drawer"
    >
      {/* 좌측 리사이즈 핸들 — 마우스 드래그 + 키보드(←/→/Home/End) 양쪽 지원(#332).
          폭 1px이라 기본 포커스 링이 보이지 않으므로 ring을 안쪽으로 명시한다. */}
      <div
        className="absolute bottom-0 left-0 top-0 z-10 w-1 cursor-col-resize hover:bg-primary/20 active:bg-primary/30 focus-visible:bg-primary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
        onMouseDown={startDrag}
        onKeyDown={onHandleKeyDown}
        tabIndex={0}
        role="separator"
        aria-orientation="vertical"
        aria-label="인스펙터 폭 조절"
        aria-valuenow={width}
        aria-valuemin={MIN_WIDTH}
        aria-valuemax={MAX_WIDTH}
        data-testid="node-detail-resize-handle"
      />
      <div className="p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="h-3 w-3 rounded-full" style={{ background: colorForType(node.type) }} />
            <h3 className="text-sm font-semibold">{node.type}</h3>
          </div>
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onClose} aria-label="닫기">
            <X className="h-4 w-4" />
          </Button>
        </div>
        <div className="mb-2 text-sm">{node.name}</div>
        <div className="mb-3 break-all text-xs text-muted-foreground">{node.key}</div>
        <div className="mb-3 text-xs text-muted-foreground">출처 청크 {node.sourceChunkCount}개</div>
        {/* schemaVersion이 없는 노드(스탬프 도입 이전 레거시 적재)는 아무것도 표시하지 않는다 —
            "값 없음"을 0이나 구버전으로 오인하지 않기 위함. */}
        {node.schemaVersion != null && (
          <div
            className={
              currentSchemaVersion != null && node.schemaVersion < currentSchemaVersion
                ? 'mb-3 text-xs text-destructive'
                : 'mb-3 text-xs text-muted-foreground'
            }
            data-testid="node-schema-version"
          >
            적재 시점 스키마 v{node.schemaVersion}
            {currentSchemaVersion != null && node.schemaVersion < currentSchemaVersion && '(구버전)'}
          </div>
        )}
        <div className="mb-1 text-xs font-medium uppercase text-muted-foreground">관계</div>
        <ul className="space-y-1 text-xs">
          {outgoing.map((e, i) => (
            <li key={`o${i}`} className="flex items-center gap-1">
              <span className="text-muted-foreground">→ {e.type} →</span> {target(e.objectKey)}
            </li>
          ))}
          {incoming.map((e, i) => (
            <li key={`i${i}`} className="flex items-center gap-1">
              <span className="text-muted-foreground">← {e.type} ←</span> {target(e.subjectKey)}
            </li>
          ))}
          {outgoing.length + incoming.length === 0 && <li className="text-muted-foreground">관계 없음</li>}
        </ul>
      </div>
    </aside>
  );
}
