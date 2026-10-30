import cytoscape from 'cytoscape';
import euler from 'cytoscape-euler';
import expandCollapse from 'cytoscape-expand-collapse';
import fcose from 'cytoscape-fcose';
import { Loader2, Network, SearchX } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useEffect, useMemo, useRef, useState } from 'react';
import { flushSync } from 'react-dom';

import { useCanvasBackground } from '@/hooks/useCanvasBackground';
import { filterGraphByNode } from '@/lib/graph-scope';
import type { TypePalette } from '@/lib/ontology-colors';
import type { GraphData, GraphNode } from '@/types/ontology';

import GraphKeyboardList from './GraphKeyboardList';
import { buildStylesheet } from './instance-graph-stylesheet';

// 레이아웃 2종(euler/fcose) + expand-collapse(타입 묶기) 확장을 모듈 로드 시 1회 등록
// (중복 등록은 cytoscape가 무시). 둘을 모두 쓰는 이유는 아래 FCOSE_GROUPED_LAYOUT 주석 참고.
cytoscape.use(euler);
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
  // (#396) 타입 색 팔레트 — OntologyPage가 현재 온톨로지 타입 목록으로 한 번 만들어 내려준다.
  palette: TypePalette;
}

// 평탄(묶기 OFF) 모드의 레이아웃 옵션 — 코어 타입에 euler 옵션이 없어 단언(cast)으로 전달한다.
//
// [왜 fcose에서 euler로 바꿨나] fcose는 매 틱 모든 노드 쌍의 반발력을 계산해 O(n²)이라,
// 운영 그래프(3,053노드/7,313엣지)에서 배치에만 35.7초가 걸려 페이지가 40초 넘게 멈췄다.
// euler는 쿼드트리 Barnes-Hut 근사로 먼 노드 뭉치를 질량중심 하나로 묶어 O(n log n)이라
// 같은 데이터에서 4.1초로 떨어진다(측정: 500→1,000→3,053노드에서 fcose는 1.2/3.6/35.7초로
// 제곱 증가, euler는 3,000→30,000노드에서 4.3→6.9초). 근사라 배치 품질은 미세하게 낮지만,
// fcose의 35.7초짜리 결과는 아웃라이어 때문에 fit()이 끝까지 줌아웃해 실제로는 더 안 읽혔다.
//
// (#508) 체인형(사건→피해→원인→시설→장비→법규) 그래프가 대각선 하나로 붕괴하던 문제는
// fcose의 spectral(라플라시안 고유벡터) 초기 배치가 원인이었다. euler에는 spectral 단계 자체가
// 없어 이 경로가 아예 존재하지 않는다. 240노드 순수 체인으로 재검증한 결과(PCA 분산비, 1=원형
// /0=대각선 붕괴): fcose quality:'draft' 0.21(붕괴 재현), euler 0.98~0.99(전 엔진 중 최고).
// euler는 같은 위치에 겹친 노드를 내부적으로 밀어내는 가드가 있어, 시작 좌표 없이 전 노드를
// 원점에 겹쳐 둔 경우에도 0.99로 대칭을 스스로 깬다.
//
// randomize:false는 euler의 기본값이자 우리에게 필요한 동작이다 — 아래 positionsRef로 복원한
// 기존 좌표를 시작점으로 그대로 쓴다(true면 전부 무시하고 새로 흩뿌려 #496 좌표 유지가 깨진다).
//
// maxSimulationTime은 "수렴 시간"이 아니라 하드 캡이다 — 4초가 지나면 수렴 여부와 무관하게
// 중단한다. 위 4.1초/6.9초 측정치도 이 캡에 걸린 값이므로, 그래프가 더 커져도 배치 대기시간은
// 4초대에서 더 늘지 않는 대신 배치가 덜 다듬어진 상태로 끝난다.
const EULER_LAYOUT = {
  name: 'euler',
  animate: false,
  randomize: false,
  springLength: 90, // fcose의 idealEdgeLength와 동일한 목표 엣지 길이
  maxIterations: 1000,
  maxSimulationTime: 4000,
  padding: 32,
  fit: true,
} as unknown as cytoscape.LayoutOptions;

// 타입 묶기(grouped) 모드 전용 레이아웃 — 이 모드에서만 fcose를 계속 쓴다.
//
// [왜 여기만 euler로 못 바꾸나] 묶기 모드는 타입마다 compound 부모(grp:<타입>)를 만들어 자식을
// 담는데, euler에는 compound 개념이 없다. euler는 비-부모 노드로만 body를 만들고 실제 엣지로만
// 스프링을 거는데, 우리 엣지는 노드↔노드라 grp: 부모에는 스프링이 하나도 걸리지 않는다. 결과적으로
// "같은 타입끼리 모이고 다른 타입과는 떨어지는" 힘이 없어 타입 박스가 서로 포개진다.
// 운영 그래프(3,053노드/7종 타입)로 측정한 타입 박스 겹침 비율(작은 쪽 면적 기준)과 접은 뒤
// 메타노드 최소 중심간 거리: euler 0.963 / 21쌍 중 21쌍이 절반 이상 겹침 / 47px,
// fcose 0.24 / 21쌍 중 5쌍 / 223px. euler 쪽은 접어도 덩어리가 한 점에 쌓여 읽을 수 없다.
// 시작 좌표를 타입별로 뭉쳐 주는 우회(타입 중심을 원 위에 배치)도 측정했으나 0.974로 오히려
// 나빴다 — 힘 계산에 타입 개념이 없어 4초 시뮬레이션이 엣지를 따라 다시 섞어버리기 때문이다.
// fcose는 compound를 인지해 그룹을 분리해 주므로 여기만 fcose를 유지한다. 다만 (WD-378) 운영 신고에서
// 3천 노드 묶기가 fcose만 39.6초 걸려 탭이 멈췄으므로, 자식까지 fcose로 배치하는 것은
// GROUPED_FULL_LAYOUT_MAX_NODES 이하 그래프로 한정하고 그 위는 타입별 요약 노드만 그린다(아래 주석 참고).
const FCOSE_GROUPED_LAYOUT = {
  name: 'fcose',
  animate: false,
  quality: 'default',
  randomize: false, // (#508) spectral 초기 배치 우회 — 위 EULER_LAYOUT 주석의 붕괴 사유와 동일
  nodeSeparation: 80,
  idealEdgeLength: 90,
  padding: 32,
  fit: true,
} as unknown as cytoscape.LayoutOptions;

// (WD-378) 묶기 모드에서 자식 노드까지 그려 fcose로 배치하고 expand-collapse로 접는 최대 노드 수.
// 이보다 크면 자식을 캔버스에 올리지 않고, 타입별 요약 노드(번들)와 타입 쌍별 요약 관계만 그린다.
//
// [왜 fcose 옵션 조정으로는 안 되나] fcose는 시간 상한이 없고 반복 횟수도 numIter와 무관하게
// max(노드 수×5, numIter)로 바닥이 정해진다(cose-base FDLayout.initSpringEmbedder) — 3천 노드면 최소 1만 5천 회라
// numIter를 낮춰도 줄지 않는다. quality:'draft'는 randomize:false와 함께 쓰면 fcose 내부에서 예외가 나고
// (spectral 결과 없이 relocateComponent 호출), randomize:true로 켜면 #508 대각선 붕괴 경로(spectral)를 다시 탄다.
// packComponents는 randomize:true에서만 동작한다. 즉 옵션만 낮춘 fcose로는 "상한"을 보장할 수 없다.
// [왜 expand-collapse도 안 쓰나] fcose를 빼고 자식을 바로 접어 봐도(3,071노드/5,086관계 측정) 접기만 3초였다 —
// expand-collapse는 자식을 하나씩 지우며 접힌 자식 컬렉션에 매번 합집합을 만들어(자식 수의 제곱) 느리고,
// 자식 관계 5천 개를 메타노드 사이 평행선으로 다시 걸어 그 뒤 검색 한 번의 다시 그리기에도 3초씩 걸렸다.
// 요약 노드는 O(노드+관계) 집계 한 번으로 캔버스에 타입 수만큼의 노드만 남겨, 그래프 모양과 무관하게 짧게 끝난다.
// 운영 신고(3천 노드)의 묶기 fcose가 39.6초였고 비용이 노드 수의 제곱에 가깝게 늘므로(위 평탄 측정 500→1,000노드
// 1.2→3.6초와 같은 경향) 500노드면 같은 모양의 그래프에서도 자식까지 배치하는 쪽이 약 1초 안팎일 것으로 추정한다
// (500노드 자체는 실측하지 않은 외삽값이다).
const GROUPED_FULL_LAYOUT_MAX_NODES = 500;

// 요약 노드끼리 원 위에서 떨어뜨릴 간격(px) — 34px 노드와 아래 라벨, 요약 관계 라벨이 서로 겹치지 않을 만큼.
const BUNDLE_RING_SPACING = 220;

// 요약 노드 배치 — 타입 수(보통 10개 미만)만큼의 노드를 원 위에 등간격으로 둔다(첫 타입이 12시 방향).
// 노드 수가 타입 수로 고정돼 힘 계산이 필요 없고, 결정적이라 매번 같은 자리에 나타난다.
// (6개 요약 노드에 fcose를 돌려 보니 한 직선 위에 늘어서는 #508 증상이 재현돼 쓰지 않는다.)
function bundleRingPositions(ids: string[]): Map<string, { x: number; y: number }> {
  const k = ids.length;
  const radius = k > 1 ? Math.max(BUNDLE_RING_SPACING / (2 * Math.sin(Math.PI / k)), BUNDLE_RING_SPACING * 0.75) : 0;
  return new Map(
    ids.map((id, i) => {
      const angle = -Math.PI / 2 + (2 * Math.PI * i) / k;
      return [id, { x: radius * Math.cos(angle), y: radius * Math.sin(angle) }];
    }),
  );
}

// 요약 화면 요소 — 타입별 노드(라벨에 노드 수)와 타입 쌍별 관계 하나(라벨에 관계 타입과 개수).
// 같은 타입 안의 관계는 요약 노드의 자기 루프가 되므로 그리지 않는다.
function buildBundleSummary(nodes: GraphNode[], edges: GraphData['edges']) {
  const typeOf = new Map(nodes.map((n) => [n.key, n.type]));
  const counts = new Map<string, number>();
  for (const n of nodes) counts.set(n.type, (counts.get(n.type) ?? 0) + 1);
  const pairs = new Map<string, { source: string; target: string; count: number; relTypes: Set<string> }>();
  for (const e of edges) {
    const a = typeOf.get(e.subjectKey);
    const b = typeOf.get(e.objectKey);
    if (!a || !b || a === b) continue;
    // 방향은 첫 관계 기준으로 고정하고, 반대 방향 관계도 같은 쌍으로 센다(선 하나만 그린다).
    const key = a < b ? `${a}|${b}` : `${b}|${a}`;
    const pair = pairs.get(key) ?? { source: `grp:${a}`, target: `grp:${b}`, count: 0, relTypes: new Set<string>() };
    pair.count += 1;
    pair.relTypes.add(e.type);
    pairs.set(key, pair);
  }
  return {
    types: [...counts.entries()].map(([type, count]) => ({ type, count })),
    links: [...pairs.entries()].map(([key, p]) => ({
      id: `agg:${key}`,
      source: p.source,
      target: p.target,
      label: `${[...p.relTypes].join(', ')} (${p.count.toLocaleString()})`,
    })),
  };
}

// 캔버스에 보이는 실제 노드 좌표를 캐시에 담는다(묶기 부모 grp:* 자체는 제외).
// 접힌 메타노드의 자식은 그래프에서 빠져 collapsedChildren에 보관되고 좌표는 접기 직전 값 그대로다 —
// 접은 뒤 메타노드가 움직였다면(드래그) 그 이동량만큼 자식 좌표도 옮겨 담아야 다시 그릴 때 그 자리를
// 지킨다(expand-collapse가 펼칠 때 쓰는 보정과 동일: 현재 위치 - position-before-collapse).
function captureCanvasPositions(cy: cytoscape.Core, cache: Map<string, { x: number; y: number }>) {
  cy.nodes().forEach((n) => {
    if (!n.data('isGroup')) {
      cache.set(n.id(), { x: n.position('x'), y: n.position('y') });
      return;
    }
    const before = n.data('position-before-collapse') as { x: number; y: number } | null | undefined;
    const children = n.data('collapsedChildren') as cytoscape.Collection | null | undefined;
    if (!before || !children) return;
    const dx = n.position('x') - before.x;
    const dy = n.position('y') - before.y;
    children.nodes().forEach((c) => {
      cache.set(c.id(), { x: c.position('x') + dx, y: c.position('y') + dy });
    });
  });
}

// expand-collapse로 접기 직전에 compound 부모의 위치·크기를 자식 기준으로 갱신한다. cytoscape는 부모 좌표를
// 그리기 단계에서 늦게 계산하므로, 레이아웃 없이 요소를 넣자마자 접으면 부모가 아직 (0,0)이라
// 메타노드가 전부 원점에 겹친다(접기는 그 부모 좌표를 메타노드 자리로 쓴다). 공식 타입에 없는 메서드라 단언한다.
function collapseAllGroups(cy: cytoscape.Core, api: ExpandCollapseApi | null) {
  if (!api) return;
  (cy.nodes() as unknown as { updateCompoundBounds(force?: boolean): void }).updateCompoundBounds(true);
  api.collapseAll();
}

// 캐시된 좌표가 없는(=새로 나타난) 노드의 힘-기반 리파인 시작점 — 원점 근처에 겹쳐 놓으면
// 대칭을 못 깨고 뭉칠 수 있어, 노드 수에 비례해 넓게 흩뿌린다. 위 0.99처럼 euler의 겹침 가드만으로도
// 동작은 하지만, 시작점이 흩어져 있을수록 4초 캡 안에서 더 잘 수렴한다.
function randomScatterPosition(nodeCount: number): { x: number; y: number } {
  const spread = 120 * Math.sqrt(Math.max(nodeCount, 1));
  return { x: (Math.random() - 0.5) * spread, y: (Math.random() - 0.5) * spread };
}

// 리사이즈 후 재맞춤 시 노드가 경계에 붙지 않도록 주는 여백(px).
const FIT_PADDING = 40;

// 인스턴스 그래프 캔버스 — 개체·관계를 Cytoscape.js(Canvas)로 렌더링하며, 레이아웃은 모드에 따라
// 평탄 모드는 euler·묶기 모드는 fcose를 쓴다(선택 근거는 FCOSE_GROUPED_LAYOUT 주석 참고). 단 (WD-378) 묶기 모드에서
// 노드가 GROUPED_FULL_LAYOUT_MAX_NODES를 넘으면 자식 없이 타입별 요약 노드만 원 위에 그린다.
// activeTypes(빈 Set이면 전체)·search(이름 부분일치, 대소문자 무시)로 필터링하며, 노드 tap 시 상세 드로어 오픈을 위임한다.
// 캔버스는 DOM 노드가 없으므로 테스트/디버그를 위해 컨테이너에 data-node-count를 노출하고, dev에서 cy 인스턴스를 window에 싣는다.
export default function InstanceGraph({ graph, activeTypes, search, onNodeSelect, focusKey, grouped, palette }: Props) {
  const { resolvedTheme } = useTheme();
  // (#507) 실제 페이지 배경(--background)을 읽어 엣지 라벨 배경(text-background-color)에 반영 —
  // 테마 컬러(indigo/ocean/sunset)마다 다른 실제 배경과 리터럴 크롬 색이 어긋나던 문제 수정.
  const canvasBackground = useCanvasBackground();
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  const expandCollapseRef = useRef<ExpandCollapseApi | null>(null); // 타입 묶기 API
  // tap 핸들러가 최신 콜백·노드맵을 참조하도록 ref로 보관(핸들러는 mount 시 1회만 바인딩).
  const onSelectRef = useRef(onNodeSelect);
  const nodeMapRef = useRef<Map<string, GraphNode>>(new Map());
  // 생성 effect(mount 1회)가 최초 스타일시트를 만들 때만 쓰는 팔레트 참조 — 이후 팔레트가 바뀌면
  // 아래 테마/팔레트 effect가 스타일시트를 통째로 다시 적용하므로 stale해질 여지가 없다.
  const paletteRef = useRef(palette);
  // (#496) 노드 id → 마지막 캔버스 좌표 캐시. 검색/타입 필터로 요소를 지웠다 다시 그릴 때
  // 이 좌표를 복원해, 사용자가 드래그로 옮긴 위치가 필터 조작만으로 초기화되지 않게 한다.
  const positionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  // 직전 렌더의 graph 참조 — 참조가 바뀌면(온톨로지 전환 등 실제 새 데이터셋 로드) "완전히 새로운
  // 그래프"로 간주해 좌표 캐시를 비우고 전체를 새로 배치한다. 참조가 그대로면(검색/타입 필터/
  // 묶기 토글 등 파생 상태만 바뀐 경우) 기존 좌표를 유지한다.
  const prevGraphRef = useRef<GraphData | null>(null);
  // (WD-378) 묶기 모드 전용 좌표 캐시 — 묶기 레이아웃이 타입별로 모아 둔 자식 노드 좌표.
  // 평탄 캐시(positionsRef)와 분리하는 이유: ① 묶기 중 검색/필터가 바뀌어도 레이아웃을 다시 돌리지 않고
  // 이 좌표로 복원해야 타입 박스가 다시 섞이지 않는다(평탄 좌표로 복원하면 타입이 뒤섞여 메타노드가 겹친다).
  // ② 묶기를 끄면 예전처럼 묶기 전 평탄 배치로 돌아가야 하므로, 평탄 캐시를 묶기 좌표로 덮어쓰면 안 된다.
  const groupedPositionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  // 캔버스에 "실제로" 그려져 있는 그래프·모드. 묶기 재배치는 프레임을 양보한 뒤 실행되므로(아래 effect),
  // 예약이 취소되면 React 상태(grouped)와 캔버스가 어긋난다. 좌표 캡처는 반드시 캔버스 기준으로 해야
  // 평탄 좌표가 묶기 캐시로(또는 그 반대로) 새어 들어가지 않는다.
  const canvasGraphRef = useRef<GraphData | null>(null);
  const canvasGroupedRef = useRef(false);
  // 캔버스의 묶기 화면이 요약 노드 화면(큰 그래프, GROUPED_FULL_LAYOUT_MAX_NODES 주석)인지. 요약 화면에는 자식이
  // 없으므로 좌표 캐시도 요약 노드(grp:<타입>) 단위로 따로 둔다 — 드래그로 옮긴 요약 노드 자리를 필터 변경에도 지킨다.
  const canvasSummaryRef = useRef(false);
  const bundlePositionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  // (WD-378) 묶기 레이아웃 진행 표시 — 묶기 배치는 메인 스레드를 동기로 점유하므로, 시작 전에 프레임을 양보해
  // 이 표시를 먼저 그린 뒤 배치한다(그렇지 않으면 탭이 멈춘 채 아무 안내도 없다).
  const [layingOut, setLayingOut] = useState(false);

  // 타입 토글·검색 필터 적용(activeTypes 비어 있으면 전체 표시).
  const { filteredNodes, filteredEdges } = useMemo(() => {
    const q = search.trim().toLowerCase();
    // 노드 필터 → 양끝 생존 엣지만 유지하는 공용 규칙은 filterGraphByNode에 있다(OntologyPage의
    // 온톨로지 스코프 필터와 동일한 패턴을 여기서도 재사용).
    const { nodes, edges } = filterGraphByNode(
      graph,
      (n) => (activeTypes.size === 0 || activeTypes.has(n.type)) && (q === '' || n.name.toLowerCase().includes(q)),
    );
    return { filteredNodes: nodes, filteredEdges: edges };
  }, [graph, activeTypes, search]);

  const nodeMap = useMemo(() => new Map(filteredNodes.map((n) => [n.key, n])), [filteredNodes]);

  // 캔버스 텍스트 대체 목록(#326) — 필터·검색 결과와 동일한 노드만 노출한다(숨긴 노드가 SR에 새면 안 됨).
  // 라벨에 타입·인접 관계 수를 함께 담아, 시각으로만 알 수 있던 정보를 키보드/SR 사용자도 얻게 한다.
  // (#405) degree는 반드시 graph.edges(필터되지 않은 전체)로 계산한다 — filteredEdges로 계산하면
  // "상대 노드가 검색어에 매칭되지 않아 화면에서 사라짐"과 "이 노드가 관계가 없음"이 뒤섞여, 실제로는
  // 관계가 있는 노드도 검색 필터링만으로 "관계 0개"처럼 보이게 된다. filteredEdges는 캔버스에 실제로
  // 그릴 엣지 선정에만 쓴다(아래 cy.add 이펙트).
  const keyboardItems = useMemo(() => {
    const degree = new Map<string, number>();
    for (const e of graph.edges) {
      degree.set(e.subjectKey, (degree.get(e.subjectKey) ?? 0) + 1);
      degree.set(e.objectKey, (degree.get(e.objectKey) ?? 0) + 1);
    }
    return filteredNodes.map((n) => ({
      id: n.key,
      label: `${n.name} (${n.type}) — 관계 ${degree.get(n.key) ?? 0}개`,
    }));
  }, [filteredNodes, graph.edges]);

  // 대체 목록 항목 활성화 → 캔버스 tap과 동일한 선택 경로.
  const selectByKey = (key: string) => {
    const raw = nodeMap.get(key);
    if (raw) onNodeSelect(raw);
  };

  useEffect(() => {
    onSelectRef.current = onNodeSelect;
  }, [onNodeSelect]);
  useEffect(() => {
    paletteRef.current = palette;
  }, [palette]);
  useEffect(() => {
    nodeMapRef.current = nodeMap;
  }, [nodeMap]);

  // cy 인스턴스 생성(mount 시 1회) — tap 핸들러 바인딩 + dev용 window 노출. unmount 시 파기.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const cy = cytoscape({
      container,
      style: buildStylesheet(resolvedTheme === 'dark', paletteRef.current, canvasBackground),
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
    // 테스트/디버그용: canvas라 DOM 셀렉터가 없으므로 dev 서버와 E2E 빌드(preview 포함)에서만 cy를 노출한다(WD-257).
    if (import.meta.env.DEV || __E2E__) {
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
      if (import.meta.env.DEV || __E2E__) delete (window as unknown as { __ontologyCy?: cytoscape.Core }).__ontologyCy;
      cy.destroy();
      cyRef.current = null;
    };
    // 생성은 1회만 — 이후 요소/스타일은 별도 effect에서 갱신한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 필터 결과(요소) 갱신 → 요소 교체 후 모드에 맞는 레이아웃(평탄 euler / 묶기 fcose)으로 재배치.
  // grouped면 타입별 compound 부모로 묶는다(큰 그래프는 타입별 요약 노드).
  // (#496) graph 참조가 바뀐 "진짜 새 데이터셋" 로드일 때만 전체 랜덤 재배치를 하고,
  // 검색/타입 필터처럼 같은 graph에서 파생된 부분집합만 바뀐 경우엔 기존 노드 위치를 그대로 복원한다.
  // (WD-378) 묶기 모드도 같은 원칙을 따른다 — 재배치는 새 그래프·묶기 켜기·새 노드 등장 때만 하고,
  // 묶기 중 검색/타입 필터 변경은 묶기 좌표 캐시로 복원한 뒤 다시 접기만 한다.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const isNewGraph = prevGraphRef.current !== graph;
    prevGraphRef.current = graph;
    if (isNewGraph) {
      // 새 데이터셋 로드 — 이전 그래프의 좌표는 의미가 없으므로 캐시를 모두 비운다.
      positionsRef.current.clear();
      groupedPositionsRef.current.clear();
      bundlePositionsRef.current.clear();
    } else if (canvasGraphRef.current === graph) {
      // 필터/묶기 등으로 인한 재렌더 — 지우기 전에 현재 캔버스에 실제로 보이는 좌표를 캐시에 반영한다
      // (드래그는 React state를 거치지 않고 cy를 직접 변형하므로, 여기서 캡처해야 최신 값을 놓치지 않는다).
      // 캔버스가 보여 주는 화면 종류(평탄/묶기/요약)의 캐시에만 담는다 — 서로 덮어쓰지 않는다.
      if (canvasSummaryRef.current) {
        cy.nodes().forEach((n) => {
          bundlePositionsRef.current.set(n.id(), { x: n.position('x'), y: n.position('y') });
        });
      } else {
        captureCanvasPositions(cy, canvasGroupedRef.current ? groupedPositionsRef.current : positionsRef.current);
      }
    }

    // 이번에 그릴 화면 종류와 재배치 필요 여부를 정한다.
    // - 평탄: 평탄 캐시에 없던(=새로 나타난) 노드가 있을 때만 배치(#496).
    // - 묶기(같은 그래프의 묶기 화면이 이미 떠 있음): 화면 종류를 유지하고, 그 캐시에 없는 노드(요약 화면은 새 타입)가
    //   나타났을 때만 재배치한다. 재배치하게 되면 그때 노드 수로 화면 종류를 다시 고른다.
    // - 묶기를 막 켰거나 새 그래프: 항상 재배치하고 노드 수로 화면 종류를 고른다.
    // (WD-378) 예전에는 grouped면 항상 재배치해, 검색어 한 글자·타입 필터 토글마다 3천 노드 fcose가 처음부터 다시 돌았다.
    const hasNodes = filteredNodes.length > 0;
    const large = filteredNodes.length > GROUPED_FULL_LAYOUT_MAX_NODES;
    const groupedCanvasShown = !isNewGraph && canvasGraphRef.current === graph && canvasGroupedRef.current;
    let summary = false;
    let needsLayout: boolean;
    if (!grouped) {
      needsLayout = hasNodes && (isNewGraph || filteredNodes.some((n) => !positionsRef.current.has(n.key)));
    } else if (groupedCanvasShown && canvasSummaryRef.current) {
      summary = true;
      needsLayout = hasNodes && filteredNodes.some((n) => !bundlePositionsRef.current.has(`grp:${n.type}`));
    } else if (groupedCanvasShown) {
      needsLayout = hasNodes && filteredNodes.some((n) => !groupedPositionsRef.current.has(n.key));
      summary = needsLayout && large;
    } else {
      needsLayout = hasNodes;
      summary = large;
    }

    // 캔버스 요소를 현재 필터 결과로 교체한다. 묶기 재배치는 프레임 양보 뒤에 이 함수를 부르므로,
    // 예약이 취소되면 캔버스는 이전 화면을 그대로 유지한다(canvas*Ref도 그대로).
    // 수천 개 요소의 제거·추가는 batch로 묶어 요소마다 스타일·경계 재계산이 일어나지 않게 한다.
    const renderElements = () => {
      cy.batch(() => {
        cy.elements().remove();
        if (summary) {
          // 요약 화면 — 자식은 올리지 않는다. 좌표는 요약 캐시에서 복원하고, 없으면 아래 레이아웃이 정한다.
          const { types, links } = buildBundleSummary(filteredNodes, filteredEdges);
          cy.add([
            ...types.map(({ type, count }) => ({
              data: {
                id: `grp:${type}`,
                label: `${type} (${count.toLocaleString()})`,
                type,
                isGroup: true,
                color: palette.color(type),
              },
              position: bundlePositionsRef.current.get(`grp:${type}`) ?? { x: 0, y: 0 },
            })),
            ...links.map((l) => ({ data: l })),
          ]);
          return;
        }
        // 타입 묶기 ON: 화면에 존재하는 타입마다 compound 부모 노드를 만들고 각 노드에 parent를 부여한다.
        const parents = grouped
          ? [...new Set(filteredNodes.map((n) => n.type))].map((type) => ({
              data: { id: `grp:${type}`, label: type, isGroup: true, color: palette.color(type) },
            }))
          : [];
        const modeCache = grouped ? groupedPositionsRef.current : positionsRef.current;
        cy.add([
          ...parents,
          ...filteredNodes.map((n) => ({
            data: {
              id: n.key,
              label: n.name,
              type: n.type,
              color: palette.color(n.type),
              parent: grouped ? `grp:${n.type}` : undefined,
            },
            // 캐시된 좌표가 있으면 그 자리에 다시 배치 — 없으면(새로 나타난 노드) (#508) 원점에 겹쳐
            // 놓지 않고 랜덤 흩뿌림 좌표에서 시작시켜, 아래 레이아웃(힘 기반 리파인)이 대칭
            // 붕괴 없이 빈 자리를 찾아가게 한다. 묶기 모드는 묶기 좌표가 없으면 평탄 좌표에서 출발한다.
            position:
              modeCache.get(n.key) ??
              (grouped ? positionsRef.current.get(n.key) : undefined) ??
              randomScatterPosition(filteredNodes.length),
          })),
          ...filteredEdges.map((e, i) => ({
            data: { id: `e${i}`, source: e.subjectKey, target: e.objectKey, label: e.type },
          })),
        ]);
      });
      canvasGraphRef.current = graph;
      canvasGroupedRef.current = !!grouped;
      canvasSummaryRef.current = summary;
    };

    if (!needsLayout) {
      renderElements();
      // 재배치 없이 좌표만 복원한 묶기(compound) 화면 — 레이아웃 종료 콜백을 타지 않으므로 여기서 직접 다시 접는다.
      // 화면 맞춤(fit)은 하지 않는다: 평탄 모드의 필터 변경과 같이 사용자가 보던 뷰포트를 유지한다.
      if (grouped && !summary && hasNodes) collapseAllGroups(cy, expandCollapseRef.current);
      return;
    }

    if (!grouped) {
      renderElements();
      if (!isNewGraph) {
        // 기존 위치가 있던 노드는 잠가 레이아웃이 건드리지 못하게 한다 — 결과적으로 새 노드만
        // 배치 대상이 되는 partial-layout이 되어, 드래그로 옮긴 위치가 유지된다(#496).
        cy.nodes().forEach((n) => {
          if (positionsRef.current.has(n.id())) n.lock();
        });
      }
      // (#508) randomize:false라 spectral 초기 배치를 타지 않는다 — 위에서 복원한 기존 좌표(또는 신규 노드의
      // 흩뿌림 좌표)를 시작점으로 삼아 힘-기반 리파인만 수행한다. 평탄 모드(euler)는 4초 하드 캡이 있어
      // 기존처럼 즉시 동기 실행한다.
      const layout = cy.layout(EULER_LAYOUT);
      layout.one('layoutstop', () => cy.nodes().unlock());
      layout.run();
      return;
    }

    // 묶기 재배치. 묶기 모드에서는 노드를 잠그지 않는다 — 타입별로 노드를 다시 모아야 하는데 기존 좌표로 전부
    // 잠그면 레이아웃이 한 노드도 못 움직여, 평탄 모드에서 타입과 무관하게 흩어져 있던 배치가
    // 그대로 굳는다. 그러면 타입 박스끼리 포개져 접었을 때 메타노드가 한 점에 쌓인다
    // (운영 3,053노드 측정: 잠그면 박스 겹침 0.995·메타노드 최소 거리 16px에 14초가 걸리지만,
    // 안 잠그면 0.284·389px에 9.6초). 묶기 모드는 개별 노드가 번들 안에 접혀 보이지 않으므로
    // 개별 드래그 위치를 지킬 실익도 없다.
    const runGroupedLayout = () => {
      renderElements();
      if (summary) {
        // 큰 그래프: 요약 노드를 원 위에 등간격으로 둔다(bundleRingPositions 주석). preset 레이아웃으로 적용해
        // 다른 경로와 똑같이 layoutstop·fit을 거치게 한다.
        const ring = bundleRingPositions(cy.nodes().map((n) => n.id()));
        const layout = cy.layout({
          name: 'preset',
          positions: (n: cytoscape.NodeSingular) => ring.get(n.id()) ?? { x: 0, y: 0 },
          animate: false,
          fit: true,
          padding: FIT_PADDING,
        } as cytoscape.LayoutOptions);
        layout.one('layoutstop', () => {
          bundlePositionsRef.current.clear();
          cy.nodes().forEach((n) => {
            bundlePositionsRef.current.set(n.id(), { x: n.position('x'), y: n.position('y') });
          });
        });
        layout.run();
        return;
      }
      // 작은 그래프: compound를 인지하는 fcose로 자식까지 배치한 뒤 접는다(FCOSE_GROUPED_LAYOUT 주석 참고).
      const layout = cy.layout(FCOSE_GROUPED_LAYOUT);
      layout.one('layoutstop', () => {
        // 접기 전에 자식 좌표를 묶기 캐시에 담아 둔다 — 접고 나면 자식이 그래프에서 빠져 cy.nodes()로 읽을 수 없다.
        captureCanvasPositions(cy, groupedPositionsRef.current);
        // 배치 완료 후 모든 타입 부모를 접어 번들(메타노드)로 축약한다.
        collapseAllGroups(cy, expandCollapseRef.current);
        cy.fit(undefined, FIT_PADDING);
      });
      layout.run();
    };

    // (WD-378) 묶기 배치(요소 교체·접기·레이아웃)는 animate:false로 메인 스레드를 끝날 때까지 점유한다. 바로 실행하면
    // 「배치 중…」 표시가 그려질 틈이 없으므로, 표시 상태를 올리는 프레임과 그것이 실제로 칠해지는 프레임을
    // 하나씩 양보한 뒤 실행한다: rAF①에서 flushSync로 상태를 올려 그 자리에서 DOM에 커밋하고(이 프레임의 칠하기에
    // 포함된다), rAF②가 오면 그 프레임이 칠해진 것이므로 setTimeout(0)으로 다음 태스크에서 배치를 시작한다.
    // flushSync를 쓰는 이유: 일반 setState는 React가 별도 태스크에서 커밋하는데, 부하가 크면 그 태스크가 rAF②
    // 뒤로 밀려 표시가 칠해지기 전에 배치가 시작될 수 있다(E2E에서 실제로 재현됨).
    // 실행 전에 필터가 또 바뀌거나 언마운트되면 cleanup이 예약을 취소해, 지나간 필터 결과로
    // 배치·접기가 뒤늦게 도는 일을 막는다(그동안 캔버스는 이전 화면을 반투명 막 아래에 유지한다).
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      flushSync(() => setLayingOut(true));
      raf2 = requestAnimationFrame(() => {
        timer = setTimeout(() => {
          if (cancelled) return;
          try {
            runGroupedLayout();
          } finally {
            // 레이아웃 종료 콜백(접기·화면 맞춤 포함)은 run() 안에서 동기로 끝나므로 여기서 표시를 내린다.
            setLayingOut(false);
          }
        }, 0);
      });
    });
    return () => {
      cancelled = true;
      cancelAnimationFrame(raf1);
      cancelAnimationFrame(raf2);
      clearTimeout(timer);
      setLayingOut(false);
    };
  }, [filteredNodes, filteredEdges, grouped, palette, graph]);

  // 테마 전환 → 스타일시트만 갱신(레이아웃은 유지해 노드 위치가 흔들리지 않게 한다).
  useEffect(() => {
    cyRef.current?.style(buildStylesheet(resolvedTheme === 'dark', palette, canvasBackground));
  }, [resolvedTheme, palette, canvasBackground]);

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
      {/* Cytoscape 캔버스 마운트 지점 — 항상 렌더해 cy 라이프사이클을 단순하게 유지한다.
          canvas는 대체 텍스트가 없어 접근성 트리에서 제외하고, 아래 GraphKeyboardList가 텍스트 대체물을 담당한다. */}
      <div ref={containerRef} className="h-full w-full" aria-hidden="true" />

      {/* 키보드·스크린리더 전용 노드 목록(#326) — 포커스 시 오버레이로 드러난다. */}
      <GraphKeyboardList
        label={`지식그래프 노드 ${filteredNodes.length}개, 관계 ${filteredEdges.length}개`}
        items={keyboardItems}
        onActivate={selectByKey}
        data-testid="instance-graph-node-list"
      />

      {/* (WD-378) 묶기 레이아웃 진행 표시 — 캔버스를 반투명 막으로 덮어 배치 전의 펼쳐진 중간 상태를 가리고,
          가운데에 스피너+문구를 둔다(디자인 시스템 06 위젯 스피너 + 상태 알림 접근성 role=status/aria-live).
          배치 중에는 메인 스레드가 막혀 JS 애니메이션은 멈추지만, CSS transform 회전은 컴포지터에서 돌아 스피너가 계속 돈다. */}
      {layingOut && (
        <div
          className="absolute inset-0 flex items-center justify-center bg-background/70"
          data-testid="instance-graph-laying-out"
        >
          <div
            role="status"
            aria-live="polite"
            className="flex items-center gap-2 rounded-md border bg-background px-3 py-2 text-sm text-muted-foreground shadow-sm"
          >
            <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
            배치 중…
          </div>
        </div>
      )}

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
