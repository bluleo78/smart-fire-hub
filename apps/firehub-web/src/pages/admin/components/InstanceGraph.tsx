import cytoscape from 'cytoscape';
import euler from 'cytoscape-euler';
import expandCollapse from 'cytoscape-expand-collapse';
import fcose from 'cytoscape-fcose';
import { Network, SearchX } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useEffect, useMemo, useRef } from 'react';

import { useCanvasBackground } from '@/hooks/useCanvasBackground';
import { filterGraphByNode } from '@/lib/graph-scope';
import type { TypePalette } from '@/lib/ontology-colors';
import type { GraphData, GraphNode } from '@/types/ontology';

import GraphKeyboardList from './GraphKeyboardList';
import { buildStylesheet } from './instance-graph-stylesheet';

// 레이아웃 2종(euler/fcose) + expand-collapse(타입 묶기) 확장을 모듈 로드 시 1회 등록
// (중복 등록은 cytoscape가 무시). 둘을 모두 쓰는 이유는 아래 pickLayout 주석 참고.
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
// fcose는 compound를 인지해 그룹을 분리해 주므로, 묶기 모드의 9.6초(평탄 모드 35.7초와 달리
// 감당 가능한 수준)를 받아들이고 여기만 fcose를 유지한다.
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

// 모드에 맞는 레이아웃 선택 — 묶기 ON이면 compound를 인지하는 fcose, OFF면 10배 빠른 euler.
function pickLayout(grouped: boolean | undefined): cytoscape.LayoutOptions {
  return grouped ? FCOSE_GROUPED_LAYOUT : EULER_LAYOUT;
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
// 평탄 모드는 euler·묶기 모드는 fcose를 쓴다(선택 근거는 pickLayout 위 주석 참고).
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

  // 필터 결과(요소) 갱신 → 요소 교체 후 모드에 맞는 레이아웃(pickLayout)으로 재배치.
  // grouped면 타입별 compound 부모로 묶는다.
  // (#496) graph 참조가 바뀐 "진짜 새 데이터셋" 로드일 때만 전체 랜덤 재배치를 하고,
  // 검색/타입 필터처럼 같은 graph에서 파생된 부분집합만 바뀐 경우엔 기존 노드 위치를 그대로 복원한다.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const isNewGraph = prevGraphRef.current !== graph;
    prevGraphRef.current = graph;
    if (isNewGraph) {
      // 새 데이터셋 로드 — 이전 그래프의 좌표는 의미가 없으므로 캐시를 비운다.
      positionsRef.current.clear();
    } else {
      // 필터/묶기 등으로 인한 재렌더 — 지우기 전에 현재 캔버스에 실제로 보이는 좌표를 캐시에 반영한다
      // (드래그는 React state를 거치지 않고 cy를 직접 변형하므로, 여기서 캡처해야 최신 값을 놓치지 않는다).
      cy.nodes().forEach((n) => {
        if (!n.data('isGroup')) positionsRef.current.set(n.id(), { x: n.position('x'), y: n.position('y') });
      });
    }
    cy.elements().remove();
    // 타입 묶기 ON: 화면에 존재하는 타입마다 compound 부모 노드를 만들고 각 노드에 parent를 부여한다.
    const parents = grouped
      ? [...new Set(filteredNodes.map((n) => n.type))].map((type) => ({
          data: { id: `grp:${type}`, label: type, isGroup: true, color: palette.color(type) },
        }))
      : [];
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
        // 붕괴 없이 빈 자리를 찾아가게 한다.
        position: positionsRef.current.get(n.key) ?? randomScatterPosition(filteredNodes.length),
      })),
      ...filteredEdges.map((e, i) => ({
        data: { id: `e${i}`, source: e.subjectKey, target: e.objectKey, label: e.type },
      })),
    ]);
    // 좌표 캐시에 없던(=새로 나타난) 노드가 있으면 그 노드만 배치할 최소한의 레이아웃이 필요하다.
    const hasNewNodes = filteredNodes.some((n) => !positionsRef.current.has(n.key));
    const needsLayout = filteredNodes.length > 0 && (isNewGraph || hasNewNodes || grouped);
    if (needsLayout) {
      // 묶기 모드에서는 잠그지 않는다 — 타입별로 노드를 다시 모아야 하는데 기존 좌표로 전부
      // 잠그면 레이아웃이 한 노드도 못 움직여, 평탄 모드에서 타입과 무관하게 흩어져 있던 배치가
      // 그대로 굳는다. 그러면 타입 박스끼리 포개져 접었을 때 메타노드가 한 점에 쌓인다
      // (운영 3,053노드 측정: 잠그면 박스 겹침 0.995·메타노드 최소 거리 16px에 14초가 걸리지만,
      // 안 잠그면 0.284·389px에 9.6초). 묶기 모드는 개별 노드가 번들 안에 접혀 보이지 않으므로
      // 개별 드래그 위치를 지킬 실익도 없다. 평탄 모드에서는 기존대로 잠가 #496을 보장한다.
      if (!isNewGraph && !grouped) {
        // 기존 위치가 있던 노드는 잠가 레이아웃이 건드리지 못하게 한다 — 결과적으로 새 노드만
        // 배치 대상이 되는 partial-layout이 되어, 드래그로 옮긴 위치가 유지된다.
        cy.nodes().forEach((n) => {
          if (!n.data('isGroup') && positionsRef.current.has(n.id())) n.lock();
        });
      }
      // (#508) 두 레이아웃 모두 randomize:false라 spectral 초기 배치를 타지 않는다 — 위 cy.add에서
      // 복원한 기존 좌표(또는 신규 노드의 흩뿌림 좌표)를 시작점으로 삼아 힘-기반 리파인만 수행한다.
      // 묶기 ON일 때만 compound를 인지하는 fcose를 쓰는 이유는 FCOSE_GROUPED_LAYOUT 주석 참고.
      const layout = cy.layout(pickLayout(grouped));
      layout.one('layoutstop', () => {
        cy.nodes().unlock();
        // 배치 완료 후, 묶기 모드면 모든 타입 부모를 접어 번들(메타노드)로 축약한다.
        if (grouped) {
          expandCollapseRef.current?.collapseAll();
          cy.fit(undefined, FIT_PADDING);
        }
      });
      layout.run();
    }
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
