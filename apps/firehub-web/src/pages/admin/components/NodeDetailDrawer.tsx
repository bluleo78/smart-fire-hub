import { X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { useDataset } from '@/hooks/queries/useDatasets';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import type { TypePalette } from '@/lib/ontology-colors';
import type { GraphEdge, GraphNode } from '@/types/ontology';

interface Props {
  node: GraphNode | null;
  edges: GraphEdge[];
  nodesByKey: Map<string, GraphNode>;
  onClose: () => void;
  // 관계 항목 클릭 시 인접 노드로 이동(대상 노드가 그래프에 있을 때만 활성).
  onNavigate?: (key: string) => void;
  // 온톨로지 id → 현재 schema_version. node.ontologyId로 찾은 값과 node.schemaVersion을 비교해
  // 구버전 적재 여부를 표시한다(#678 — 노드마다 실제로 속한 온톨로지가 다를 수 있어, 하나의
  // "기본" 버전과 비교하던 이전 방식은 다른 온톨로지 소속 노드를 잘못 판정했다).
  schemaVersionByOntologyId?: Map<number, number>;
  // (#396) 타입 색 팔레트 — 캔버스·타입 필터와 같은 인스턴스를 받아야 인스펙터의 점 색이
  // 캔버스 노드와 일치한다.
  palette: TypePalette;
}

// 인스펙터 리사이즈 폭 범위(px) — AI 패널 규약(w-80=320)을 기본값으로 한다.
const MIN_WIDTH = 260;
const MAX_WIDTH = 560;
const DEFAULT_WIDTH = 320;
// 키보드(화살표) 1회 조작당 변화량(px) — 드래그와 달리 이산 조작이므로 체감 가능한 단위로 잡는다(#332).
const KEY_STEP = 16;

// (WD-372) Tailwind md(768px) 이상만 그래프 옆 도킹 패널로 둔다. 그보다 좁으면 320px 고정 패널이
// 화면 밖으로 밀려나 「닫기」·관계 버튼에 닿을 수 없었으므로, 화면 폭에 맞춘 모달(Dialog)로 띄운다.
const DOCKED_MQ = '(min-width: 768px)';

/**
 * 표(데이터셋) 투영 노드의 출처 — 「출처 데이터셋: 이름 외 n개」(WD-382).
 * 표 투영에는 청크가 없어 예전엔 늘 「출처 청크 0개」로 보여 근거 없는 노드처럼 읽혔다. 첫 데이터셋 이름만 불러와 보이고
 * (노드 하나에 데이터셋이 여럿일 수 있다), 이름을 못 읽으면(권한 없음·삭제됨) id 로 대신한다.
 */
function DatasetProvenance({ datasetIds }: { datasetIds: number[] }) {
  const [firstId] = datasetIds;
  const { data: dataset } = useDataset(firstId);
  const rest = datasetIds.length - 1;
  return (
    <div className="mb-3 text-xs text-muted-foreground" data-testid="node-source-datasets">
      출처 데이터셋:{' '}
      <Link to={`/data/datasets/${firstId}`} className="text-primary hover:underline">
        {dataset?.name ?? `데이터셋 #${firstId}`}
      </Link>
      {rest > 0 && ` 외 ${rest}개`}
    </div>
  );
}

/** 속성 값 표시 — 수는 자릿수 구분 쉼표를 넣어 읽기 쉽게 한다(예: 9,077,284). */
const formatPropertyValue = (v: string | number) => (typeof v === 'number' ? v.toLocaleString('ko-KR') : v);

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
  schemaVersionByOntologyId,
  palette,
}: Props) {
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  // (WD-372) 도킹 가능 폭인지 — matchMedia가 없는 단위 테스트 환경은 기존 도킹 배치를 기본값으로 한다.
  const docked = useMediaQuery(DOCKED_MQ, true);
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

  // 이 노드가 실제로 적재된 온톨로지의 현재 schema_version. ontologyId가 없는 레거시 노드는
  // 기준을 알 수 없으므로 undefined로 두어 배지를 표시하지 않는다.
  const currentSchemaVersion =
    node.ontologyId != null ? schemaVersionByOntologyId?.get(node.ontologyId) : undefined;

  // 이 노드가 주어(subject)/목적어(object)인 관계를 각각 수집한다.
  const outgoing = edges.filter((e) => e.subjectKey === node.key);
  const incoming = edges.filter((e) => e.objectKey === node.key);
  // 출처 데이터셋·속성은 응답에 없을 수 있다(문서 노드·속성 없는 노드) — 빈 값으로 맞춰 아래 분기를 단순하게 둔다(WD-382)
  const sourceDatasetIds = node.sourceDatasetIds ?? [];
  const propertyEntries = Object.entries(node.properties ?? {});
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

  // 본문(헤더·속성·관계 목록) — 도킹 패널과 모바일 모달이 같은 내용을 쓴다(WD-372).
  // 모달에서는 제목이 Radix DialogTitle이어야 스크린리더가 대화상자 이름으로 읽으므로 제목 요소만 바꿔 끼운다.
  const renderBody = (Heading: typeof DialogTitle | 'h3') => (
    <div className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="h-3 w-3 rounded-full" style={{ background: palette.color(node.type) }} />
          <Heading className="text-sm font-semibold">{node.type}</Heading>
        </div>
        <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onClose} aria-label="닫기">
          <X className="h-4 w-4" />
        </Button>
      </div>
      <div className="mb-2 text-sm">{node.name}</div>
      <div className="mb-3 break-all text-xs text-muted-foreground">{node.key}</div>
      {/* 출처: 표 투영 노드는 데이터셋, 문서 노드는 청크 수(WD-382). 두 경로로 모두 적재된 노드는 둘 다 보인다. */}
      {sourceDatasetIds.length > 0 && <DatasetProvenance datasetIds={sourceDatasetIds} />}
      {(sourceDatasetIds.length === 0 || node.sourceChunkCount > 0) && (
        <div className="mb-3 text-xs text-muted-foreground">출처 청크 {node.sourceChunkCount}개</div>
      )}
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
      {/* 노드에 적재된 속성(예: 피해액) — 예전엔 상세 어디에도 없었다(WD-382) */}
      {propertyEntries.length > 0 && (
        <>
          <div className="mb-1 text-xs font-medium uppercase text-muted-foreground">속성</div>
          <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs" data-testid="node-properties">
            {propertyEntries.map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted-foreground">{k}</dt>
                <dd className="break-all">{formatPropertyValue(v)}</dd>
              </div>
            ))}
          </dl>
        </>
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
  );

  // (WD-372) 좁은 화면: 화면 폭에 맞춘 모달로 띄운다. Radix Dialog가 바깥 탭·Esc 닫기와 포커스 가두기/복원을
  // 처리하고, 포털로 렌더되므로 그래프 캔버스는 폭을 빼앗기지 않는다. 리사이즈 핸들은 의미가 없어 두지 않는다.
  if (!docked) {
    return (
      <Dialog open onOpenChange={(open) => !open && onClose()}>
        <DialogContent
          showCloseButton={false}
          className="max-h-[85dvh] gap-0 overflow-y-auto p-0"
          data-testid="node-detail-drawer"
        >
          {/* 시각적 설명은 본문이 대신하므로 보조기기용 설명만 둔다(Radix aria-describedby 경고 방지). */}
          <DialogDescription className="sr-only">선택한 노드의 속성과 인접 관계</DialogDescription>
          {renderBody(DialogTitle)}
        </DialogContent>
      </Dialog>
    );
  }

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
      {renderBody('h3')}
    </aside>
  );
}
