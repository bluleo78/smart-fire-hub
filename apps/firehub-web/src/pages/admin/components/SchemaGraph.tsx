import cytoscape from 'cytoscape';
import { useTheme } from 'next-themes';
import { useEffect, useMemo, useRef } from 'react';

import { contourForType, entityColorSet, graphChrome } from '@/lib/ontology-colors';
import type { OntologySchema } from '@/types/ontology';

import GraphKeyboardList from './GraphKeyboardList';

// 편집 모드 선택 대상 — ModelOutline과 공유하는 형태(OntologyPage가 단일 selected state로 양쪽에 내려준다).
export interface SchemaGraphSelection {
  kind: 'entity' | 'relation';
  id: number;
}

interface Props {
  schema: OntologySchema;
  onTypeClick?: (type: string) => void;
  // editing이 없으면(read 모드) 아래 4개 prop은 전혀 관여하지 않는다 — 기존 드릴다운 동작을 그대로 유지한다.
  editing?: boolean;
  selected?: SchemaGraphSelection | null;
  onSelectEntity?: (id: number) => void;
  onSelectRelation?: (id: number) => void;
}

// 스키마 그래프 스타일시트 — 타입 노드는 data(bg/border/text)(테마별 tint·윤곽선·대비 텍스트), 엣지는 크롬 색.
// 크롬 색은 InstanceGraph와 공유하는 단일 소스(ontology-colors.ts)에서 가져온다 (#377).
function buildStylesheet(isDark: boolean): cytoscape.StylesheetJson {
  const c = graphChrome(isDark);
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
    // 편집 모드 선택 강조 — cytoscape 내장 :selected 상태를 그대로 쓴다(InstanceGraph와 동일 패턴).
    // read 모드는 selected를 절대 넘기지 않으므로(.select()가 호출되지 않으므로) 이 규칙은 조용히 무관하다.
    { selector: 'node:selected', style: { 'border-color': c.ring, 'border-width': 3 } },
    { selector: 'edge:selected', style: { 'line-color': c.ring, 'target-arrow-color': c.ring, width: 2.5 } },
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
// editing prop(S2)에 따라 두 모드로 갈린다 — 꺼져 있으면 기존대로 노드 tap 시 onTypeClick으로
// 드릴다운을 위임하고, 켜져 있으면 노드/엣지 tap이 onSelectEntity/onSelectRelation으로 인스펙터
// 선택을 이끈다(M-1, S2 최종 리뷰 — "읽기 전용"은 더 이상 이 컴포넌트 전체를 설명하지 않는다).
// canvas라 DOM 노드가 없으므로 컨테이너에 data-node-count를 노출하고, dev에서 cy를 window에 싣는다.
export default function SchemaGraph({ schema, onTypeClick, editing, selected, onSelectEntity, onSelectRelation }: Props) {
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  // tap 핸들러가 최신 콜백을 참조하도록 ref로 보관(핸들러는 mount 시 1회만 바인딩).
  const onTypeClickRef = useRef(onTypeClick);
  const editingRef = useRef(editing);
  const onSelectEntityRef = useRef(onSelectEntity);
  const onSelectRelationRef = useRef(onSelectRelation);
  useEffect(() => {
    onTypeClickRef.current = onTypeClick;
    editingRef.current = editing;
    onSelectEntityRef.current = onSelectEntity;
    onSelectRelationRef.current = onSelectRelation;
  }, [onTypeClick, editing, onSelectEntity, onSelectRelation]);

  // 요소(노드/엣지) — 노드 색은 테마·타입별 entityColorSet으로 미리 계산해 data에 싣는다.
  // entityId/relationId(S2): 편집 모드 선택 동기화용. cy-id 자체는 read 모드(onTypeClick 드릴다운)가
  // 타입 "이름"을 필요로 해서 바꿀 수 없으므로, 요소 단위 편집 API가 쓰는 숫자 id는 별도 data 필드로 얹는다.
  const elements = useMemo(() => {
    const nodes = schema.entities.map((e) => {
      // #377: 테두리는 base(500)가 아니라 윤곽선 색을 쓴다 — base는 라이트 tint 배경 위에서
      // Cause 2.15 / Equipment 2.54:1로 SC 1.4.11(3:1)에 미달한다.
      const { text, tint } = entityColorSet(e.type, isDark);
      return {
        data: { id: e.type, label: e.type, bg: tint, border: contourForType(e.type, isDark), text, entityId: e.id },
      };
    });
    // 노드 id 집합 — 엣지의 source/target(타입 "이름")이 실제로 존재하는 노드를 가리키는지 여기서
    // 검증한다. entities/relations는 캐시 낙관적 갱신(useOntologyElement.ts)이 개별 업데이터마다
    // 손으로 유지하는데, 그중 하나라도 relations[].subject/object를 새 이름으로 remap하지 못하면
    // (Task 4 리뷰 C-2·N-3, 관계 편집이 Task 5에서 이 표면을 다시 연다) 존재하지 않는 노드를 잇는
    // 엣지가 생긴다. cytoscape는 그런 엣지를 add()하는 즉시 예외를 던져 PageErrorBoundary까지
    // 크래시가 번진다 — 방어망은 "이런 데이터가 생기지 않게" 하는 게 아니라(그건 각 업데이터의 몫)
    // "생기더라도 페이지 전체가 죽지 않게" 거르는 것이다.
    const nodeIds = new Set(nodes.map((n) => n.data.id));
    const orphaned: string[] = [];
    const edges = schema.relations
      .map((r, i) => ({
        data: { id: `t${i}`, source: r.subject, target: r.object, label: r.relation, relationId: r.id },
      }))
      .filter((e) => {
        const valid = nodeIds.has(e.data.source) && nodeIds.has(e.data.target);
        if (!valid) orphaned.push(`${e.data.id}(${e.data.source} → ${e.data.target})`);
        return valid;
      });
    // 조용히 유실시키지 않는다 — 걸러진 엣지가 있으면 원인 추적이 가능하도록 흔적을 남긴다.
    if (orphaned.length > 0) {
      console.warn(
        `[SchemaGraph] 존재하지 않는 노드를 가리키는 관계 ${orphaned.length}건을 캔버스에서 제외했습니다: ${orphaned.join(', ')}`,
      );
    }
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
      autoungrabify: true, // 편집 모드에서도 노드 위치는 조작 대상이 아님 — 레이아웃은 항상 breadthfirst가 계산한다
    });
    // 타입 노드 tap — 편집 모드면 인스펙터 선택(숫자 id), 아니면 기존 드릴다운(타입 이름)으로 분기.
    cy.on('tap', 'node', (evt) => {
      if (editingRef.current) {
        const entityId = evt.target.data('entityId') as number | undefined;
        if (entityId != null) onSelectEntityRef.current?.(entityId);
        return;
      }
      onTypeClickRef.current?.(evt.target.id());
    });
    // 관계 엣지 tap — 편집 모드에서만 의미가 있다(read 모드는 엣지에 상호작용이 없었다).
    cy.on('tap', 'edge', (evt) => {
      if (!editingRef.current) return;
      const relationId = evt.target.data('relationId') as number | undefined;
      if (relationId != null) onSelectRelationRef.current?.(relationId);
    });
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

  // read 모드에서는 cy의 tap-자체선택을 꺼 둔다(리뷰 MIN-3) — cytoscape는 autounselectify가 꺼져
  // 있으면(기본값) tap만으로도 스스로 :selected를 건다. 노드는 read 모드에서 드릴다운으로 TabsContent가
  // 언마운트돼 가려지지만, 엣지는 read 모드에 상호작용이 없어(위 tap 핸들러가 editing이 아니면 그냥
  // early-return) tap해도 아무 반응이 없어야 정상인데, cy 자체 선택 때문에 :selected 링(위 edge:selected
  // 스타일)이 남는다. editing일 때만 tap-선택을 허용한다.
  useEffect(() => {
    cyRef.current?.autounselectify(!editing);
  }, [editing]);

  // 선택 동기화(편집 모드) — 아웃라인 클릭으로 selected가 바뀌면 캔버스도 같은 요소를 :selected로 표시한다.
  // InstanceGraph의 focusKey 동기화와 동일 패턴(cy 내장 select/unselect). read 모드는 selected를 넘기지
  // 않으므로(undefined) 매번 unselect만 하고 끝난다 — 위 autounselectify(true)와 합쳐져 read 모드에서는
  // 어떤 요소도 :selected가 되지 않는다(기존 동작 무변경).
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.$(':selected').unselect();
    if (!selected) return;
    const target =
      selected.kind === 'entity' ? cy.nodes(`[entityId = ${selected.id}]`) : cy.edges(`[relationId = ${selected.id}]`);
    target.select();
  }, [selected, elements]);

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

      {/* 키보드·스크린리더 전용 타입 목록(#326). editing이면 Enter가 인스펙터 선택(onSelectEntity)으로
          가야 한다 — 그대로 onTypeClick(드릴다운)에 묶어 두면 마우스는 선택, 키보드는 인스턴스 탭으로
          이탈해 편집기가 닫혀 버린다(리뷰 IMP-3, 같은 컨트롤이 모드에 따라 반대로 동작하는 오동작). */}
      <GraphKeyboardList
        label={`지식 모델 타입 ${schema.entities.length}개, 관계 ${schema.relations.length}개`}
        items={keyboardItems}
        onActivate={
          editing
            ? (type) => {
                const id = schema.entities.find((e) => e.type === type)?.id;
                if (id != null) onSelectEntity?.(id);
              }
            : onTypeClick
        }
        data-testid="schema-graph-type-list"
      />
    </div>
  );
}
