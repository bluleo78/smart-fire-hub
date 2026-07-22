import cytoscape from 'cytoscape';
import expandCollapse from 'cytoscape-expand-collapse';
import fcose from 'cytoscape-fcose';
import { Network, SearchX } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useEffect, useMemo, useRef } from 'react';

import { colorForType } from '@/lib/ontology-colors';
import type { GraphData, GraphNode } from '@/types/ontology';

// fcose 레이아웃 + expand-collapse(타입 묶기) 확장을 모듈 로드 시 1회 등록(중복 등록은 cytoscape가 무시).
cytoscape.use(fcose);
cytoscape.use(expandCollapse);

// expand-collapse 확장이 Core에 추가하는 메서드 — 공식 타입이 없어 최소 형태로 선언한다.
type ExpandCollapseApi = { collapseAll(): void; expandAll(): void };
type CyWithExpandCollapse = cytoscape.Core & {
  expandCollapse(opts: Record<string, unknown>): ExpandCollapseApi;
};

interface Props {
  graph: GraphData;
  activeTypes: Set<string>;
  search: string;
  onNodeSelect: (n: GraphNode) => void;
  // 인스펙터의 관계 클릭 내비게이션 대상 — 값이 바뀌면 해당 노드를 선택+센터로 포커싱한다.
  focusKey?: string | null;
  // 타입 묶기 — true면 타입별 compound 부모로 묶고 접어(bundle) hairball을 줄인다.
  grouped?: boolean;
}

// 테마별 크롬 색상 — cytoscape 스타일시트는 CSS 변수를 못 읽으므로 리터럴로 계산한다.
// 노드 라벨은 노드 아래(캔버스 배경 위)에 놓이므로 배경과 대비되는 텍스트 색을 쓴다.
function chrome(isDark: boolean) {
  return isDark
    ? { label: '#e5e7eb', muted: '#8b847a', edge: '#3f3b36', ring: '#f1f5f9', surface: '#1b1917', bg: '#121110' }
    : { label: '#1b1a17', muted: '#6b665d', edge: '#d3cfc7', ring: '#0f172a', surface: '#ffffff', bg: '#ffffff' };
}

// cytoscape 스타일시트 생성 — 노드 색은 data(color)(타입색), 라벨/엣지 색은 테마 크롬에서.
function buildStylesheet(isDark: boolean): cytoscape.StylesheetJson {
  const c = chrome(isDark);
  return [
    {
      selector: 'node',
      style: {
        'background-color': 'data(color)',
        width: 34,
        height: 34,
        label: 'data(label)',
        color: c.label,
        'font-size': 11,
        'font-weight': 500,
        'text-valign': 'bottom',
        'text-halign': 'center',
        'text-margin-y': 4,
        'text-wrap': 'ellipsis',
        'text-max-width': '120px',
        'border-width': 2,
        'border-color': c.surface,
      },
    },
    { selector: 'node:selected', style: { 'border-color': c.ring, 'border-width': 3.5 } },
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
        'font-size': 9,
        color: c.muted,
        'text-background-color': c.bg,
        'text-background-opacity': 0.85,
        'text-background-padding': '2px',
      },
    },
    { selector: 'edge:selected', style: { 'line-color': c.ring, 'target-arrow-color': c.ring, width: 2 } },
    // hover 스포트라이트 — 이웃 아닌 요소는 흐리게(.faded), 호버 노드는 강조(.spotlight)해 hairball에서 초점을 살린다.
    { selector: '.faded', style: { opacity: 0.12, 'text-opacity': 0.12 } },
    { selector: '.spotlight', style: { 'border-color': c.ring, 'border-width': 3, 'font-weight': 700 } },
    // 타입 묶기 compound 부모 — 타입색 옅은 박스에 상단 라벨. 접힌 메타노드는 부모 스타일을 상속한다.
    {
      selector: ':parent',
      style: {
        shape: 'round-rectangle',
        'background-color': 'data(color)',
        'background-opacity': 0.08,
        'border-color': 'data(color)',
        'border-width': 1,
        label: 'data(label)',
        color: c.label,
        'font-size': 12,
        'font-weight': 700,
        'text-valign': 'top',
        'text-halign': 'center',
        padding: '12px',
      },
    },
  ];
}

// fcose 레이아웃 옵션 — 겹침 방지(nodeRepulsion/nodeSeparation)를 내장 제공한다.
// 코어 타입에 fcose 옵션이 없어 단언(cast)으로 전달한다.
const FCOSE_LAYOUT = {
  name: 'fcose',
  animate: false,
  quality: 'default',
  randomize: true,
  nodeSeparation: 80,
  idealEdgeLength: 90,
  padding: 32,
  fit: true,
} as unknown as cytoscape.LayoutOptions;

// 리사이즈 후 재맞춤 시 노드가 경계에 붙지 않도록 주는 여백(px).
const FIT_PADDING = 40;

// 인스턴스 그래프 캔버스 — 개체·관계를 Cytoscape.js(Canvas, fcose 레이아웃)로 렌더링한다.
// activeTypes(빈 Set이면 전체)·search(이름 부분일치, 대소문자 무시)로 필터링하며, 노드 tap 시 상세 드로어 오픈을 위임한다.
// 캔버스는 DOM 노드가 없으므로 테스트/디버그를 위해 컨테이너에 data-node-count를 노출하고, dev에서 cy 인스턴스를 window에 싣는다.
export default function InstanceGraph({ graph, activeTypes, search, onNodeSelect, focusKey, grouped }: Props) {
  const { resolvedTheme } = useTheme();
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  const expandCollapseRef = useRef<ExpandCollapseApi | null>(null); // 타입 묶기 API
  // tap 핸들러가 최신 콜백·노드맵을 참조하도록 ref로 보관(핸들러는 mount 시 1회만 바인딩).
  const onSelectRef = useRef(onNodeSelect);
  const nodeMapRef = useRef<Map<string, GraphNode>>(new Map());

  // 타입 토글·검색 필터 적용(activeTypes 비어 있으면 전체 표시).
  const { filteredNodes, filteredEdges } = useMemo(() => {
    const q = search.trim().toLowerCase();
    const nodes = graph.nodes.filter(
      (n) => (activeTypes.size === 0 || activeTypes.has(n.type)) && (q === '' || n.name.toLowerCase().includes(q)),
    );
    const visible = new Set(nodes.map((n) => n.key));
    // 엣지는 양쪽 노드가 모두 보일 때만 표시.
    const edges = graph.edges.filter((e) => visible.has(e.subjectKey) && visible.has(e.objectKey));
    return { filteredNodes: nodes, filteredEdges: edges };
  }, [graph, activeTypes, search]);

  const nodeMap = useMemo(() => new Map(filteredNodes.map((n) => [n.key, n])), [filteredNodes]);

  useEffect(() => {
    onSelectRef.current = onNodeSelect;
  }, [onNodeSelect]);
  useEffect(() => {
    nodeMapRef.current = nodeMap;
  }, [nodeMap]);

  // cy 인스턴스 생성(mount 시 1회) — tap 핸들러 바인딩 + dev용 window 노출. unmount 시 파기.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const cy = cytoscape({
      container,
      style: buildStylesheet(resolvedTheme === 'dark'),
      minZoom: 0.2,
      maxZoom: 2.5,
      wheelSensitivity: 0.2,
    });
    // 노드 tap → 원본 GraphNode를 찾아 상세 드로어 오픈 위임(최신 값은 ref에서).
    cy.on('tap', 'node', (evt) => {
      const raw = nodeMapRef.current.get(evt.target.id());
      if (raw) onSelectRef.current(raw);
    });
    // hover 스포트라이트 — 호버 노드의 닫힌 이웃(자신+인접 노드/엣지) 외 요소를 흐리게 한다.
    cy.on('mouseover', 'node', (evt) => {
      const keep = evt.target.closedNeighborhood();
      cy.elements().difference(keep).addClass('faded');
      evt.target.addClass('spotlight');
    });
    cy.on('mouseout', 'node', () => {
      cy.elements().removeClass('faded spotlight');
    });
    cyRef.current = cy;
    // 타입 묶기(compound 접기/펼치기) API 초기화 — layoutBy null로 접을 때 자동 재배치는 하지 않는다(위치 유지).
    expandCollapseRef.current = (cy as CyWithExpandCollapse).expandCollapse({
      layoutBy: null,
      fisheye: false,
      animate: false,
      undoable: false,
    });
    // 테스트/디버그용: canvas라 DOM 셀렉터가 없으므로 dev 환경에서만 cy를 노출한다.
    if (import.meta.env.DEV) {
      (window as unknown as { __ontologyCy?: cytoscape.Core }).__ontologyCy = cy;
    }

    // 컨테이너 크기 변화(상세 드로어 도킹·창 리사이즈·사이드바 토글) 대응.
    // cytoscape는 최초 레이아웃에서만 fit하므로, 리사이즈 후 재측정·재맞춤하지 않으면 내용이 잘린다.
    let fitTimer: ReturnType<typeof setTimeout> | undefined;
    const ro = new ResizeObserver(() => {
      const c = cyRef.current;
      if (!c) return;
      c.resize(); // 캔버스 픽셀 크기를 컨테이너에 즉시 재동기화
      clearTimeout(fitTimer);
      // 잦은 리사이즈 이벤트를 디바운스한 뒤 여백을 포함해 다시 맞춘다.
      fitTimer = setTimeout(() => {
        if (c.elements().length > 0) c.fit(undefined, FIT_PADDING);
      }, 150);
    });
    ro.observe(container);

    return () => {
      ro.disconnect();
      clearTimeout(fitTimer);
      if (import.meta.env.DEV) delete (window as unknown as { __ontologyCy?: cytoscape.Core }).__ontologyCy;
      cy.destroy();
      cyRef.current = null;
    };
    // 생성은 1회만 — 이후 요소/스타일은 별도 effect에서 갱신한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 필터 결과(요소) 갱신 → 요소 교체 후 fcose 재배치. grouped면 타입별 compound 부모로 묶는다.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.elements().remove();
    // 타입 묶기 ON: 화면에 존재하는 타입마다 compound 부모 노드를 만들고 각 노드에 parent를 부여한다.
    const parents = grouped
      ? [...new Set(filteredNodes.map((n) => n.type))].map((type) => ({
          data: { id: `grp:${type}`, label: type, isGroup: true, color: colorForType(type) },
        }))
      : [];
    cy.add([
      ...parents,
      ...filteredNodes.map((n) => ({
        data: {
          id: n.key,
          label: n.name,
          type: n.type,
          color: colorForType(n.type),
          parent: grouped ? `grp:${n.type}` : undefined,
        },
      })),
      ...filteredEdges.map((e, i) => ({
        data: { id: `e${i}`, source: e.subjectKey, target: e.objectKey, label: e.type },
      })),
    ]);
    if (filteredNodes.length > 0) {
      const layout = cy.layout(FCOSE_LAYOUT);
      // 배치 완료 후, 묶기 모드면 모든 타입 부모를 접어 번들(메타노드)로 축약한다.
      if (grouped) {
        layout.one('layoutstop', () => {
          expandCollapseRef.current?.collapseAll();
          cy.fit(undefined, FIT_PADDING);
        });
      }
      layout.run();
    }
  }, [filteredNodes, filteredEdges, grouped]);

  // 테마 전환 → 스타일시트만 갱신(레이아웃은 유지해 노드 위치가 흔들리지 않게 한다).
  useEffect(() => {
    cyRef.current?.style(buildStylesheet(resolvedTheme === 'dark'));
  }, [resolvedTheme]);

  // 관계 내비게이션 포커스 — focusKey 변경 시 해당 노드를 선택하고 화면 중앙으로 이동한다.
  // getElementById는 셀렉터 파싱이 없어 콜론(:) 포함 키도 안전하다(cy.$('#..')는 이스케이프 필요).
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy || !focusKey) return;
    const node = cy.getElementById(focusKey);
    if (node.empty()) return;
    cy.$(':selected').unselect();
    node.select();
    cy.animate({ center: { eles: node }, zoom: Math.max(cy.zoom(), 1) }, { duration: 300 });
  }, [focusKey]);

  return (
    <div className="relative h-full w-full" data-testid="instance-graph" data-node-count={filteredNodes.length}>
      {/* Cytoscape 캔버스 마운트 지점 — 항상 렌더해 cy 라이프사이클을 단순하게 유지한다. */}
      <div ref={containerRef} className="h-full w-full" />

      {/* 적재된 그래프가 아예 없는 빈 상태(하우스 empty 패턴). */}
      {graph.nodes.length === 0 && (
        <div
          className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-background text-center"
          data-testid="instance-graph-empty"
        >
          <Network className="h-10 w-10 text-muted-foreground" />
          <p className="text-sm font-medium">적재된 그래프가 없습니다.</p>
        </div>
      )}

      {/* 그래프는 있으나 필터/검색 결과가 0건인 경우 — 안내 메시지 오버레이. */}
      {graph.nodes.length > 0 && filteredNodes.length === 0 && (
        <div
          className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-background text-center"
          data-testid="instance-graph-no-results"
        >
          <SearchX className="h-10 w-10 text-muted-foreground" />
          <p className="text-sm font-medium">조건에 맞는 노드가 없습니다.</p>
        </div>
      )}
    </div>
  );
}
