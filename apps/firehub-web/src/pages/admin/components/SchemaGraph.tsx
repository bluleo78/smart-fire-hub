import cytoscape from 'cytoscape';
import { useTheme } from 'next-themes';
import { useEffect, useMemo, useRef } from 'react';

import { entityColorSet } from '@/lib/ontology-colors';
import type { OntologySchema } from '@/types/ontology';

import GraphKeyboardList from './GraphKeyboardList';

interface Props {
  schema: OntologySchema;
  onTypeClick?: (type: string) => void;
}

// 테마별 크롬 색상(엣지·라벨) — cytoscape 스타일시트는 CSS 변수를 못 읽으므로 리터럴로 계산한다.
function chrome(isDark: boolean) {
  return isDark
    ? { muted: '#8b847a', edge: '#3f3b36', bg: '#121110' }
    : { muted: '#6b665d', edge: '#d3cfc7', bg: '#ffffff' };
}

// 스키마 그래프 스타일시트 — 타입 노드는 data(bg/border/text)(테마별 tint·타입색·대비 텍스트), 엣지는 크롬 색.
function buildStylesheet(isDark: boolean): cytoscape.StylesheetJson {
  const c = chrome(isDark);
  return [
    {
      selector: 'node',
      style: {
        shape: 'round-rectangle',
        'background-color': 'data(bg)',
        'border-color': 'data(border)',
        'border-width': 1.5,
        label: 'data(label)',
        color: 'data(text)',
        'font-size': 12,
        'font-weight': 600,
        'text-valign': 'center',
        'text-halign': 'center',
        width: 'label',
        height: 'label',
        padding: '10px',
        'text-wrap': 'ellipsis',
        'text-max-width': '150px',
      },
    },
    {
      selector: 'edge',
      style: {
        width: 1.5,
        'line-color': c.edge,
        'target-arrow-color': c.edge,
        'target-arrow-shape': 'triangle',
        'arrow-scale': 0.9,
        'curve-style': 'bezier',
        label: 'data(label)',
        'font-size': 11,
        color: c.muted,
        'text-background-color': c.bg,
        'text-background-opacity': 0.8,
        'text-background-padding': '2px',
      },
    },
  ];
}

// 계층형(하향) 배치 — 소규모 타입 DAG이므로 내장 breadthfirst로 충분(신규 의존성 없음).
const BREADTHFIRST_LAYOUT = {
  name: 'breadthfirst',
  directed: true,
  spacingFactor: 1.3,
  padding: 40,
  fit: true,
} as unknown as cytoscape.LayoutOptions;

// 리사이즈 후 재맞춤 시 노드가 경계에 붙지 않도록 주는 여백(px).
const FIT_PADDING = 40;

// 온톨로지 스키마 다이어그램 — 타입 노드 + 허용 트리플 엣지를 Cytoscape(계층형)로 배치한다.
// 읽기 전용 — 노드 tap 시 onTypeClick으로 드릴다운을 위임한다.
// canvas라 DOM 노드가 없으므로 컨테이너에 data-node-count를 노출하고, dev에서 cy를 window에 싣는다.
export default function SchemaGraph({ schema, onTypeClick }: Props) {
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  // tap 핸들러가 최신 콜백을 참조하도록 ref로 보관(핸들러는 mount 시 1회만 바인딩).
  const onTypeClickRef = useRef(onTypeClick);
  useEffect(() => {
    onTypeClickRef.current = onTypeClick;
  }, [onTypeClick]);

  // 요소(노드/엣지) — 노드 색은 테마·타입별 entityColorSet으로 미리 계산해 data에 싣는다.
  const elements = useMemo(() => {
    const nodes = schema.entities.map((e) => {
      const { base, text, tint } = entityColorSet(e.type, isDark);
      return { data: { id: e.type, label: e.type, bg: tint, border: base, text } };
    });
    const edges = schema.relations.map((r, i) => ({
      data: { id: `t${i}`, source: r.subject, target: r.object, label: r.relation },
    }));
    return [...nodes, ...edges];
  }, [schema, isDark]);

  // cy 인스턴스 생성(mount 시 1회) — tap 핸들러 바인딩 + dev용 window 노출. unmount 시 파기.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const cy = cytoscape({
      container,
      style: buildStylesheet(isDark),
      minZoom: 0.2,
      maxZoom: 2.5,
      wheelSensitivity: 0.2,
      autoungrabify: true, // 읽기 전용: 노드 드래그 금지
    });
    // 타입 노드 tap → 드릴다운 위임(최신 콜백은 ref에서).
    cy.on('tap', 'node', (evt) => onTypeClickRef.current?.(evt.target.id()));
    cyRef.current = cy;
    if (import.meta.env.DEV) {
      (window as unknown as { __ontologySchemaCy?: cytoscape.Core }).__ontologySchemaCy = cy;
    }

    // 컨테이너 크기 변화(창 리사이즈·사이드바 토글) 대응 — 최초 fit 이후 재측정·재맞춤이 없으면 잘린다.
    let fitTimer: ReturnType<typeof setTimeout> | undefined;
    const ro = new ResizeObserver(() => {
      const c = cyRef.current;
      if (!c) return;
      c.resize(); // 캔버스 픽셀 크기를 컨테이너에 즉시 재동기화
      clearTimeout(fitTimer);
      fitTimer = setTimeout(() => {
        if (c.elements().length > 0) c.fit(undefined, FIT_PADDING); // 디바운스 후 여백 포함 재맞춤
      }, 150);
    });
    ro.observe(container);

    return () => {
      ro.disconnect();
      clearTimeout(fitTimer);
      if (import.meta.env.DEV) delete (window as unknown as { __ontologySchemaCy?: cytoscape.Core }).__ontologySchemaCy;
      cy.destroy();
      cyRef.current = null;
    };
    // 생성은 1회만 — 요소/스타일은 별도 effect에서 갱신한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 요소·스타일 갱신(스키마/테마 변경) → 색 재계산 반영 후 재배치.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.style(buildStylesheet(isDark));
    cy.elements().remove();
    cy.add(elements);
    if (schema.entities.length > 0) cy.layout(BREADTHFIRST_LAYOUT).run();
  }, [elements, isDark, schema.entities.length]);

  // 캔버스 텍스트 대체 목록(#326) — 타입별 인접 관계 수를 라벨에 담아 드릴다운을 키보드로도 가능하게 한다.
  const keyboardItems = useMemo(() => {
    const degree = new Map<string, number>();
    for (const r of schema.relations) {
      degree.set(r.subject, (degree.get(r.subject) ?? 0) + 1);
      degree.set(r.object, (degree.get(r.object) ?? 0) + 1);
    }
    return schema.entities.map((e) => ({
      id: e.type,
      label: `${e.type} — 관계 ${degree.get(e.type) ?? 0}개`,
    }));
  }, [schema]);

  return (
    // relative: 대체 목록이 포커스 시 캔버스 위 오버레이로 뜨는 기준 박스.
    <div className="relative h-full w-full" data-testid="schema-graph" data-node-count={schema.entities.length}>
      {/* canvas는 대체 텍스트가 없어 접근성 트리에서 제외 — 텍스트 대체물은 GraphKeyboardList가 담당한다. */}
      <div ref={containerRef} className="h-full w-full" aria-hidden="true" />

      {/* 키보드·스크린리더 전용 타입 목록(#326) — onTypeClick이 있을 때만 활성화(드릴다운) 가능. */}
      <GraphKeyboardList
        label={`지식 모델 타입 ${schema.entities.length}개, 관계 ${schema.relations.length}개`}
        items={keyboardItems}
        onActivate={onTypeClick}
        data-testid="schema-graph-type-list"
      />
    </div>
  );
}
