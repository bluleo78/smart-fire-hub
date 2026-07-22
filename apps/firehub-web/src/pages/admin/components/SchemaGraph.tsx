import cytoscape from 'cytoscape';
import { useTheme } from 'next-themes';
import { useEffect, useMemo, useRef } from 'react';

import { entityColorSet } from '@/lib/ontology-colors';
import type { OntologySchema } from '@/types/ontology';

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
  padding: 24,
  fit: true,
} as unknown as cytoscape.LayoutOptions;

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
    if (!containerRef.current) return;
    const cy = cytoscape({
      container: containerRef.current,
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
    return () => {
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

  return (
    <div className="h-full w-full" data-testid="schema-graph" data-node-count={schema.entities.length}>
      <div ref={containerRef} className="h-full w-full" />
    </div>
  );
}
