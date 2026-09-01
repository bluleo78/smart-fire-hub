import cytoscape from 'cytoscape';
import edgehandles from 'cytoscape-edgehandles';
import { useTheme } from 'next-themes';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { contourForType, entityColorSet, graphChrome } from '@/lib/ontology-colors';
import { validateEntityTypeName, validateRelationName, validateTripleUniqueness } from '@/lib/ontology-validation';
import type { OntologySchema } from '@/types/ontology';

import GraphKeyboardList from './GraphKeyboardList';
import CanvasInlineInput from './model-editor/CanvasInlineInput';

// cytoscape.use는 확장을 cytoscape 모듈 자체에 등록한다(인스턴스가 아니라 라이브러리 전역) —
// 컴포넌트 effect 안에서 부르면 마운트할 때마다 같은 확장을 중복 등록해 "already registered" 류
// 경고나 예기치 않은 동작을 낳는다. 모듈이 처음 로드될 때 1회만 실행되도록 모듈 스코프에 둔다.
cytoscape.use(edgehandles);

// 편집 모드 선택 대상 — ModelOutline과 공유하는 형태(OntologyPage가 단일 selected state로 양쪽에 내려준다).
export interface SchemaGraphSelection {
  kind: 'entity' | 'relation';
  id: number;
}

interface Props {
  schema: OntologySchema;
  onTypeClick?: (type: string) => void;
  // editing이 없으면(read 모드) 아래 프롭들은 전혀 관여하지 않는다 — 기존 드릴다운 동작을 그대로 유지한다.
  editing?: boolean;
  selected?: SchemaGraphSelection | null;
  onSelectEntity?: (id: number) => void;
  onSelectRelation?: (id: number) => void;
  // 캔버스 드래그 관계 생성(S3 Task 2) — 이 컴포넌트는 read 모드에서도 쓰이는 유일한 캔버스라 API
  // 계층(mutations)에 직접 결합시키지 않는다. 대신 제스처 감지·CanvasInlineInput 렌더·로컬 검증
  // (validateRelationName/validateTripleUniqueness, 이미 가진 schema prop으로 충분)까지만 이
  // 컴포넌트가 맡고, 실제 저장은 onConnect에 위임한다.
  // 성공하면 true, 실패하면 false를 resolve한다(mutations.*가 이미 쓰는 "성공=결과/실패=undefined"
  // 규약을 boolean으로 좁힌 것) — 실패 시 CanvasInlineInput을 닫지 않아 사용자가 친 이름을 잃지
  // 않는다. OntologyPage가 실제 mutations.addRelation 호출과 성공 후 선택(setModelSelected)까지 맡는다.
  onConnect?: (subjectTypeId: number, objectTypeId: number, relationName: string) => Promise<boolean>;
  // 캔버스 인라인 리네임/생성(S3 Task 3) — onConnect와 동일한 이유·동일한 계약(성공 true/실패
  // false)으로 API 계층을 이 컴포넌트 밖(OntologyPage)에 둔다. 브리프 원안의 동기 시그니처
  // (`onCreateEntityAt?: (position) => void`)는 낡았다 — 이름 없이는 addEntityType을 부를 수 없다
  // (Task 2가 onConnect를 같은 이유로 이미 이 형태로 고쳤다).
  onRenameEntity?: (entityTypeId: number, nextName: string) => Promise<boolean>;
  onCreateEntityAt?: (name: string) => Promise<boolean>;
  // Delete 키 삭제(S3 Task 4) — 확인 여부(타입은 확인 다이얼로그, 관계는 즉시)가 OntologyPage마다
  // 다르고 그 다이얼로그(DeleteTypeConfirm)도 API 계층 안에 있으므로, 이 컴포넌트는 "삭제해 달라"는
  // 요청만 올려보낸다(onConnect/onRenameEntity/onCreateEntityAt과 동일한 경계). 실제 삭제(뮤테이션
  // 호출·확인 다이얼로그 오픈)는 OntologyPage의 몫이다.
  onRequestDeleteEntity?: (entityTypeId: number) => void;
  onRequestDeleteRelation?: (relationId: number) => void;
  // 좌측 타입 필터 패널(TypeFilterPanel)이 공유하는 활성 타입 집합(#411) — 빈 Set/미전달은 전체
  // 활성(TypeFilterPanel/InstanceGraph와 동일한 "빈 Set = 전체" 규약)이다. 인스턴스 탭과 달리 이
  // 컴포넌트는 이전까지 이 prop을 받지 않아, 칩을 꺼도 스키마 캔버스만 변화가 없었다(#411 원인).
  activeTypes?: Set<string>;
}

// 드래그로 막 이어진 두 타입 — 관계명을 아직 입력하지 않은 상태. CanvasInlineInput을 이 좌표에 띄운다.
// subjectName/objectName은 드롭 시점(ehcomplete)에 schema.entities에서 미리 조회해 둔다(리뷰 N-5) —
// 렌더 시점에 매번 다시 찾으면 그 사이 schema가 바뀌어 못 찾을 때 ''로 폴백하기 쉽고, 빈 문자열은
// validateTripleUniqueness의 비교를 전부 무력화해(둘 다 ''면 항상 매치) 서버 UNIQUE 제약 문구가
// 사용자에게 노출되는 경로를 연다. 드롭 시점에 못 찾으면 애초에 pendingConnection을 세우지 않는다.
interface PendingConnection {
  subjectTypeId: number;
  objectTypeId: number;
  subjectName: string;
  objectName: string;
  x: number;
  y: number;
}

// 더블클릭으로 리네임 중인 노드 — 커밋 시 값이 currentName과 같으면(브리프 설계 노트) 요청 없이
// 닫아야 하므로 원래 이름을 들고 있는다.
interface PendingRename {
  entityTypeId: number;
  currentName: string;
  x: number;
  y: number;
}

// 빈 캔버스 더블클릭으로 만드는 중인 타입 — 좌표만 있으면 된다(이름은 아직 모른다).
interface PendingCreate {
  x: number;
  y: number;
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
export default function SchemaGraph({
  schema,
  onTypeClick,
  editing,
  selected,
  onSelectEntity,
  onSelectRelation,
  onConnect,
  onRenameEntity,
  onCreateEntityAt,
  onRequestDeleteEntity,
  onRequestDeleteRelation,
  activeTypes,
}: Props) {
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);
  const ehRef = useRef<ReturnType<cytoscape.Core['edgehandles']> | null>(null);
  // tap 핸들러가 최신 콜백을 참조하도록 ref로 보관(핸들러는 mount 시 1회만 바인딩).
  const onTypeClickRef = useRef(onTypeClick);
  const editingRef = useRef(editing);
  const onSelectEntityRef = useRef(onSelectEntity);
  const onSelectRelationRef = useRef(onSelectRelation);
  const onConnectRef = useRef(onConnect);
  const onRenameEntityRef = useRef(onRenameEntity);
  const onCreateEntityAtRef = useRef(onCreateEntityAt);
  // Delete 키 핸들러(mount 시 1회 바인딩, 아래)가 최신 콜백·선택 상태를 참조하기 위한 ref.
  const onRequestDeleteEntityRef = useRef(onRequestDeleteEntity);
  const onRequestDeleteRelationRef = useRef(onRequestDeleteRelation);
  const selectedRef = useRef(selected);
  // ehcomplete 핸들러(mount 시 1회 바인딩)가 드롭 시점의 최신 schema.entities를 참조하기 위한 ref
  // (리뷰 N-5) — 끝점 이름을 여기서 미리 확인해, 못 찾으면 pendingConnection 자체를 세우지 않는다.
  const schemaRef = useRef(schema);
  useEffect(() => {
    onTypeClickRef.current = onTypeClick;
    editingRef.current = editing;
    onSelectEntityRef.current = onSelectEntity;
    onSelectRelationRef.current = onSelectRelation;
    onConnectRef.current = onConnect;
    onRenameEntityRef.current = onRenameEntity;
    onCreateEntityAtRef.current = onCreateEntityAt;
    onRequestDeleteEntityRef.current = onRequestDeleteEntity;
    onRequestDeleteRelationRef.current = onRequestDeleteRelation;
    selectedRef.current = selected;
    schemaRef.current = schema;
  }, [
    onTypeClick,
    editing,
    onSelectEntity,
    onSelectRelation,
    onConnect,
    onRenameEntity,
    onCreateEntityAt,
    onRequestDeleteEntity,
    onRequestDeleteRelation,
    selected,
    schema,
  ]);

  // 드래그로 막 이어진 연결 — 관계명을 입력하는 CanvasInlineInput이 이 state가 있을 때만 뜬다.
  // 이중 제출 가드(submitting)는 커밋 중 입력을 잠가 같은 트리플이 두 번 POST되는 것을 막는다
  // (S2 최종 리뷰 I-2와 같은 결함 클래스, CreateRelationForm의 submitting과 동일한 이유).
  const [pendingConnection, setPendingConnection] = useState<PendingConnection | null>(null);
  // 더블클릭 리네임/생성(S3 Task 3) — pendingConnection과 별개 state다(제스처 트리거가 다르다:
  // ehcomplete vs dbltap). 세 pending 중 최대 하나만 열려 있어야 하므로(브리프 설계 노트 — 입력이
  // 열려 있는 동안 다른 캔버스 제스처가 개입하지 않게 하라) mount 시 1회 바인딩되는 dbltap
  // 핸들러들은 아래 pendingAnyRef로 셋을 한꺼번에 확인한다.
  const [pendingRename, setPendingRename] = useState<PendingRename | null>(null);
  const [pendingCreate, setPendingCreate] = useState<PendingCreate | null>(null);
  // submitting은 세 흐름(connect/rename/create)이 공유한다 — hasAnyPendingInput() 가드(세 dbltap/tap/
  // ehcomplete 핸들러 전부에 적용, 리뷰 I-1)가 상호 배타를 보장하므로 동시에 두 CanvasInlineInput이 뜨는
  // 경우가 없다. 흐름별로 나눌 이유가 없고, 나누면 "이중 제출 가드가 흐름마다 따로 있다 = 하나만 고치고
  // 나머지를 잊는다"는 실수 표면만 넓어진다.
  const [submitting, setSubmitting] = useState(false);
  // dbltap 핸들러(mount 시 1회 바인딩)가 "이미 다른 인라인 입력이 열려 있는가"를 최신 값으로 보기
  // 위한 ref 묶음 — 위 state들은 렌더 시점 값이라 mount 시 바인딩된 핸들러 클로저 안에서는 항상 최초
  // (null) 값으로 고정돼 있다.
  const pendingConnectionRef = useRef(pendingConnection);
  const pendingRenameRef = useRef(pendingRename);
  const pendingCreateRef = useRef(pendingCreate);
  useEffect(() => {
    pendingConnectionRef.current = pendingConnection;
    pendingRenameRef.current = pendingRename;
    pendingCreateRef.current = pendingCreate;
  }, [pendingConnection, pendingRename, pendingCreate]);
  const hasAnyPendingInput = () =>
    pendingConnectionRef.current != null || pendingRenameRef.current != null || pendingCreateRef.current != null;

  // 요소(노드/엣지) — 노드 색은 테마·타입별 entityColorSet으로 미리 계산해 data에 싣는다.
  // 노드 id = entityTypeId(S3 Task 1): 예전엔 cy-id가 타입 "이름"이라 리네임 시 relations[].subject/object
  // remap이 entities[].type과 한 순간이라도 어긋나면 cytoscape가 add() 즉시 예외를 던져 PageErrorBoundary
  // 까지 크래시가 번지는 결함(S2 리뷰 C-2)이 있었다. id는 리네임으로 바뀌지 않으므로 이 이관으로 그
  // 크래시 부류 자체가 사라진다 — remap 코드(useOntologyElement.ts)는 ModelOutline 등 이름 표시 소비자를
  // 위해 그대로 둔다(이 컴포넌트만 이름 정합성 의존을 끊는다). 드릴다운(onTypeClick)은 여전히 타입
  // "이름"을 기대하므로 노드 tap 핸들러에서 data('label')로 따로 조회한다.
  // entityId/relationId(S2): 편집 모드 선택 동기화용 — id가 있는 정상 경로에서는 노드 id(entityTypeId)와
  // entityId 값이 같아지지만, 폴백 분기(`name:${type}`)에서는 entityId가 undefined라 서로 다르다.
  // 선택 동기화 effect가 쓰는 셀렉터(cy.nodes('[entityId = N]'))를 건드리면 회귀 원인이 두 갈래가
  // 되므로 필드는 유지한다.
  // 타입 필터(#411) — activeTypes가 비어 있거나 안 넘어오면 전체 활성(TypeFilterPanel/InstanceGraph와
  // 동일 규약). 캔버스(elements)뿐 아니라 대체 목록(keyboardItems)·data-node-count·aria-label도 같은
  // 집합을 참조해야 "칩은 꺼졌는데 목록/카운트는 그대로"인 반쪽 필터가 되지 않는다.
  // isTypeActive(type)은 "사용자가 이 타입을 껐는가"만 판단한다 — schema.entities에 그 타입이 실재
  // 하는지는 별개 문제(N-3 회귀 가드: 존재하지 않는 목적어를 가리키는 관계는 아래 elements의
  // "orphaned" 방어망이 따로 걸러 console.warn을 남긴다). 이 둘을 하나로 합치면(activeEntities에서
  // 파생한 이름 집합으로 관계를 거르면) 실재하지 않는 유령 타입이 활성 집합에 아예 없다는 이유로
  // orphaned 관계가 조용히 사라져 그 경고가 못 뜬다 — 실제로 이 회귀를 겪었다(ontology-editor.spec.ts
  // "N-3 회귀 가드").
  const isTypeActive = useCallback((type: string) => !activeTypes || activeTypes.size === 0 || activeTypes.has(type), [activeTypes]);
  const activeEntities = useMemo(() => schema.entities.filter((e) => isTypeActive(e.type)), [schema.entities, isTypeActive]);
  const activeRelations = useMemo(
    () => schema.relations.filter((r) => isTypeActive(r.subject) && isTypeActive(r.object)),
    [schema.relations, isTypeActive],
  );

  const elements = useMemo(() => {
    // 엔티티·관계를 여기서 먼저 걸러 두면 필터로 빠진 관계가 아래 "존재하지 않는 노드를 가리키는 관계"
    // 경고(orphaned)로 잘못 잡히지 않는다 — 그 경고는 실제 데이터 정합성 문제 전용이다.
    // id 해소 — 서버는 항상 채워 주지만 타입상 옵셔널이라(레거시 목 데이터) 폴백이 필요하다.
    // 폴백 id에 접두사를 붙이는 이유: 숫자 id와 섞였을 때 "3"(진짜 id)과 이름이 "3"인 타입이 충돌하는
    // 것을 막는다. Number()로 되돌릴 수 없는 형태여야 호출부가 조용히 잘못된 id를 보내지 않는다.
    const nodeIdFor = (id: number | undefined, type: string) => (id != null ? String(id) : `name:${type}`);
    const idByName = new Map(activeEntities.map((e) => [e.type, nodeIdFor(e.id, e.type)]));
    const nodes = activeEntities.map((e) => {
      // #377: 테두리는 base(500)가 아니라 윤곽선 색을 쓴다 — base는 라이트 tint 배경 위에서
      // Cause 2.15 / Equipment 2.54:1로 SC 1.4.11(3:1)에 미달한다.
      const { text, tint } = entityColorSet(e.type, isDark);
      return {
        data: {
          id: nodeIdFor(e.id, e.type),
          label: e.type,
          bg: tint,
          border: contourForType(e.type, isDark),
          text,
          entityId: e.id,
        },
      };
    });
    // 노드 id 집합 — 엣지의 source/target이 실제로 존재하는 노드를 가리키는지 여기서 검증한다.
    // entities/relations는 캐시 낙관적 갱신(useOntologyElement.ts)이 개별 업데이터마다 손으로 유지하는데,
    // 그중 하나라도 relations[].subjectTypeId/objectTypeId를 놓치면(S2 Task 4 리뷰 C-2·N-3, 관계
    // 편집이 S2 Task 5에서 이 표면을 다시 연다 — 이 파일에는 S3 Task 1~4 주석도 섞여 있어 슬라이스를
    // 명시한다, 최종 리뷰 M-7) 존재하지 않는 노드를 잇는 엣지가 생긴다. cytoscape는 그런 엣지를
    // add()하는 즉시 예외를 던져 PageErrorBoundary까지 크래시가 번진다 — 방어망은 "이런 데이터가 생기지
    // 않게" 하는 게 아니라(그건 각 업데이터의 몫) "생기더라도 페이지 전체가 죽지 않게" 거르는 것이다.
    const nodeIds = new Set(nodes.map((n) => n.data.id));
    const orphaned: string[] = [];
    const edges = activeRelations
      .map((r, i) => ({
        data: {
          id: `t${i}`,
          // subjectTypeId/objectTypeId가 있어도 그 id가 실제 노드 집합에 없으면(entities[].id가 비어
          // `name:` 폴백 노드로 렌더된 상황과 relations[]는 id가 채워진 상황이 섞이는 경우 — 실제
          // 발생 경로는 확인되지 않았지만 이관 전에는 양쪽 다 이름 기반이라 생기지 않던 조합이다) 이름
          // 폴백으로 재해소한다. 이 가드가 없으면 그런 조합에서 엣지가 전량 고아로 걸러진다.
          source: r.subjectTypeId != null && nodeIds.has(String(r.subjectTypeId)) ? String(r.subjectTypeId) : idByName.get(r.subject),
          target: r.objectTypeId != null && nodeIds.has(String(r.objectTypeId)) ? String(r.objectTypeId) : idByName.get(r.object),
          label: r.relation,
          relationId: r.id,
        },
        // 경고 문구용 — id만으로는 무엇이 걸러졌는지 사람이 추적하기 어려워 이름도 함께 들고 있는다.
        subjectName: r.subject,
        objectName: r.object,
      }))
      .filter((e) => {
        const valid = e.data.source != null && e.data.target != null && nodeIds.has(e.data.source) && nodeIds.has(e.data.target);
        if (!valid) orphaned.push(`${e.data.id}(${e.subjectName} → ${e.objectName})`);
        return valid;
      })
      .map(({ data }) => ({ data }));
    // 조용히 유실시키지 않는다 — 걸러진 엣지가 있으면 원인 추적이 가능하도록 흔적을 남긴다.
    if (orphaned.length > 0) {
      console.warn(
        `[SchemaGraph] 존재하지 않는 노드를 가리키는 관계 ${orphaned.length}건을 캔버스에서 제외했습니다: ${orphaned.join(', ')}`,
      );
    }
    return [...nodes, ...edges];
  }, [activeEntities, activeRelations, isDark]);

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
    // 인라인 입력(연결/리네임/생성)이 열려 있는 동안에는 개입하지 않는다(브리프 설계 노트). 평범한
    // blur 취소 구간(입력이 아직 응답을 기다리지 않는 상태)에서는 이 가드가 실제로는 발동하지 않는다
    // — mousedown이 tap보다 먼저 입력의 onBlur→onCancel→setPendingRename(null)을 그 자리에서 flush
    // 시켜, cytoscape의 'tap'이 도달할 때는 이미 pendingRenameRef.current가 null이기 때문이다(가드가
    // 있든 없든 "선택 전환"은 그대로 일어난다). 가드가 진짜로 막아 주는 구간은 disabled(제출 중)다 —
    // 그때는 CanvasInlineInput의 onBlur 핸들러(`!committingRef.current && !disabled`일 때만 onCancel)가
    // 취소를 발동시키지 않으므로 pendingRename이 살아 있고, 그 상태에서 다른 노드를 tap하면 이 가드가
    // 없으면 리네임 응답을 기다리는 도중에 선택이 갈아치워진다.
    cy.on('tap', 'node', (evt) => {
      if (hasAnyPendingInput()) return;
      if (editingRef.current) {
        const entityId = evt.target.data('entityId') as number | undefined;
        if (entityId != null) onSelectEntityRef.current?.(entityId);
        return;
      }
      // 드릴다운 소비자(OntologyPage.drillDown)는 타입 "이름"을 기대한다 — id() 대신 data('label')을
      // 넘긴다. 이 한 줄을 놓치면 인스턴스 탭이 숫자 id를 타입 이름으로 잘못 조회한다.
      onTypeClickRef.current?.(evt.target.data('label') as string);
    });
    // 관계 엣지 tap — 편집 모드에서만 의미가 있다(read 모드는 엣지에 상호작용이 없었다).
    cy.on('tap', 'edge', (evt) => {
      if (!editingRef.current) return;
      const relationId = evt.target.data('relationId') as number | undefined;
      if (relationId != null) onSelectRelationRef.current?.(relationId);
    });

    // 노드 더블클릭 → 리네임 인라인 입력(S3 Task 3). 이벤트 이름은 추측이 아니라 cytoscape 소스
    // (node_modules/cytoscape/dist/cytoscape.cjs.js — 마우스 경로는 downHandler의 'dblclick'/'dbltap'/
    // 'vdblclick' 트리거, 터치 경로는 touchmoveHandler의 'dbltap'/'vdblclick' 트리거)에서 직접 확인했다.
    // 두 경로 모두 첫 번째 클릭에서 'tap'을 먼저 쏘고(위 tap 핸들러가 그대로 선택을 수행한다) 두 번째
    // 클릭이 cy.multiClickDebounceTime()(기본 250ms) 안에 들어오면 그 뒤에 'dbltap'을 추가로 쏜다 —
    // 즉 단일 tap 선택과 dbltap 리네임은 같은 제스처 안에서 서로 다른 시점에 발생해 충돌하지 않는다
    // (첫 tap이 이미 선택을 끝낸 뒤 dbltap이 리네임 입력을 연다 — 부수효과로 선택도 함께 이뤄질 뿐
    // 막을 이유가 없다).
    cy.on('dbltap', 'node', (evt) => {
      if (!editingRef.current) return;
      if (hasAnyPendingInput()) return;
      // onRenameEntity가 없는 소비자에서는 애초에 pendingRename을 세우지 않는다(리뷰 M-6) —
      // ehcomplete가 끝점 이름을 못 찾으면 pendingConnection을 세우지 않는 것과 같은 방침. 콜백이
      // 없는데 열어 버리면 Enter 시 handleRenameCommit이 committingRef만 세운 채 조용히 return해
      // onBlur 취소 경로가 죽고 입력이 캔버스에 눌러앉는다.
      if (!onRenameEntityRef.current) return;
      const entityId = evt.target.data('entityId') as number | undefined;
      // 폴백 노드(`name:` 접두사, id 없음)는 리네임 대상 API(entityTypeId)가 없다 — 애초에 열지 않는다.
      if (entityId == null) return;
      const label = evt.target.data('label') as string;
      const position = evt.target.renderedPosition();
      setPendingRename({ entityTypeId: entityId, currentName: label, x: position.x, y: position.y });
    });

    // 빈 캔버스 더블클릭 → 타입 추가 인라인 입력(S3 Task 3). 셀렉터 없이 core에 바인딩하면 노드
    // dbltap도 버블링돼 함께 들어오므로 evt.target === cy(배경 자체)일 때만 반응한다 — 흔한 cytoscape
    // "배경 클릭" 판별 패턴. evt.renderedPosition은 Event 클래스가 evt.position(모델 좌표)으로부터
    // zoom/pan을 적용해 자동 계산해 준다(cytoscape.cjs.js Event.prototype.recycle) — 노드의
    // renderedPosition()과 같은 좌표계라 CanvasInlineInput에 그대로 넘길 수 있다.
    cy.on('dbltap', (evt) => {
      if (!editingRef.current) return;
      if (evt.target !== cy) return;
      if (hasAnyPendingInput()) return;
      // onCreateEntityAt이 없는 소비자에서는 애초에 pendingCreate를 세우지 않는다(리뷰 M-6, 위
      // 노드 dbltap 핸들러와 동일한 이유).
      if (!onCreateEntityAtRef.current) return;
      setPendingCreate({ x: evt.renderedPosition.x, y: evt.renderedPosition.y });
    });

    // 드래그 관계 연결(S3 Task 2) — cy 인스턴스당 1회만 만든다(effect 자체가 mount 시 1회이므로
    // 이 안에서 만들면 재마운트마다 새로 생기지만 중복 등록되지는 않는다. cytoscape.use(edgehandles)
    // 쪽의 "모듈 스코프 1회" 요구와는 다른 문제 — 그건 라이브러리 등록, 이건 인스턴스 생성).
    const eh = cy.edgehandles({
      // 자기 자신에게 연결(재귀 관계)은 온톨로지에서 정상이고 백엔드 OntologyRules에도 막는 규칙이
      // 없다(직접 확인) — 프론트가 독자적으로 막지 않는다(브리프 설계 노트). id가 없는 폴백 노드
      // (`name:` 접두사, elements useMemo 참고)만 Number.isFinite로 걸러 연결 대상에서 뺀다 — 그런
      // 노드는 실제 entityTypeId가 없어 CreateRelationRequest를 만들 수 없다.
      canConnect: (source, target) => Number.isFinite(Number(source.id())) && Number.isFinite(Number(target.id())),
      // 드래그 중 보여줄 임시 엣지 — ehcomplete에서 즉시 remove()하므로 데이터 내용은 무관하다.
      edgeParams: () => ({ data: {} }),
    });
    ehRef.current = eh;

    // 드롭 완료 — 임시 엣지를 즉시 지운다(캔버스의 진실 원본은 서버 응답 → 캐시 → elements
    // useMemo다. 지우지 않으면 성공 시 엣지가 두 개가 되고, 취소·실패 시 서버에 없는 엣지가 남는다).
    cy.on('ehcomplete', (_evt, sourceNode: cytoscape.NodeSingular, targetNode: cytoscape.NodeSingular, addedEdge: cytoscape.EdgeSingular) => {
      addedEdge.remove();
      // 다른 인라인 입력(리네임/생성)이 이미 열려 있으면 세 번째를 겹쳐 띄우지 않는다(리뷰 I-1) —
      // 특히 disabled(제출 중) 구간은 CanvasInlineInput의 onBlur 핸들러가 취소를 꺼 둬(committingRef
      // 조건) 사용자가 그 사이 드래그로 관계를 그어도 임시 엣지 제거만으로는 이 경로를 막지 못한다.
      if (hasAnyPendingInput()) return;
      const subjectTypeId = Number(sourceNode.id());
      const objectTypeId = Number(targetNode.id());
      // canConnect가 이미 걸렀어야 하지만(폴백 노드 배제), 그 규칙이 나중에 느슨해지더라도 이
      // 시점에서 다시 한번 방어한다 — CreateRelationRequest는 숫자 id가 필수다.
      if (!Number.isFinite(subjectTypeId) || !Number.isFinite(objectTypeId)) return;
      // 끝점 이름을 여기서 미리 확인한다(리뷰 N-5) — schema.entities에 없으면(동시 편집으로 그
      // 사이 타입이 삭제된 등 드문 레이스) pendingConnection 자체를 세우지 않는다. 렌더 시점에 매번
      // 다시 찾다가 ''로 폴백하면 validateTripleUniqueness가 ''끼리 비교해 통과해 버려 서버 UNIQUE
      // 제약 문구가 노출되는 경로가 열린다.
      const subjectName = schemaRef.current.entities.find((e) => e.id === subjectTypeId)?.type;
      const objectName = schemaRef.current.entities.find((e) => e.id === objectTypeId)?.type;
      if (!subjectName || !objectName) return;
      // 여기서는 관계명을 아직 모른다(CanvasInlineInput 커밋 시점에야 정해진다) — onConnect는
      // "저장" 콜백이라 이 시점엔 부르지 않는다. 다른 패널을 닫는 등의 부수효과가 필요하면
      // OntologyPage가 onConnect의 성공 경로 안에서 처리한다(브리프 설계 노트).
      const position = targetNode.renderedPosition();
      setPendingConnection({ subjectTypeId, objectTypeId, subjectName, objectName, x: position.x, y: position.y });
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
      eh.destroy();
      ehRef.current = null;
      cy.destroy();
      cyRef.current = null;
    };
    // 생성은 1회만 — 요소/스타일은 별도 effect에서 갱신한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Delete 키 삭제(S3 Task 4) — 캔버스는 aria-hidden canvas라 포커스 대상이 아니므로(cytoscape
  // tap으로 노드/엣지를 "선택"할 뿐 실제 DOM 포커스는 옮기지 않는다) 키 리스너를 document에 건다.
  // 리스너를 무조건 걸어 두는 대신 아래 네 가드로 "지금 이 Delete/Backspace가 캔버스 선택을
  // 겨냥한 것"인지 매번 판별한다 — 하나라도 놓치면 엉뚱한 곳(인스펙터 입력 등)에서 파괴적 동작이
  // 발화하는 결함(이 저장소가 실제로 겪은 부류)이 된다:
  //   1. 편집 모드가 아니면 무시 — read 모드는 애초에 삭제 개념이 없다.
  //   2. hasAnyPendingInput()이 true면 무시 — 인라인 입력(연결/리네임/생성) 커밋 중 Delete가 그
  //      입력 안의 텍스트 편집이 아니라 캔버스 삭제로 새는 것을 막는다(tap/dbltap/ehcomplete와
  //      동일한 상호 배타 가드, 세 방향 모두 이미 이 함수를 쓴다).
  //   3. document.activeElement가 input/textarea/contenteditable이면 무시 — 인스펙터(EntityInspector/
  //      RelationInspector) 입력에서 Backspace로 글자를 지울 때 캔버스 선택까지 함께 지워지는
  //      결함(팀이 이 저장소에서 사전에 겪은 패턴)을 막는다.
  //   4. 포커스가 앱 루트(#root) 밖에 가 있으면 무시(최종 리뷰 라운드 I-1) — 처음엔 "위험한 role을
  //      열거"하는 덴리스트였다(role="alertdialog"만 → role="dialog" 추가, 리뷰 C-1). 그런데 이
  //      페이지엔 편집 모드에서도 항상 떠 있는 툴바의 온톨로지 선택 드롭다운(Radix Select →
  //      role="listbox")처럼 다이얼로그가 아닌 오버레이도 있어 덴리스트가 다시 샜다 — role을
  //      아무리 추가해도 다음 Radix 컴포넌트(DropdownMenu의 role="menu" 등)가 또 새는 구조다.
  //      그래서 판정을 뒤집는다. 첫 시도는 "캔버스 자신의 서브트리 밖이면 무시"였는데, 이건
  //      "수정 모드" 토글처럼 캔버스 밖의 평범한 툴바 버튼에 포커스가 남아 있는(클릭 후 포커스가
  //      거기 머무는) 정상 상황까지 막아 버렸다(실측 — 이 시도로 기존 통과 테스트 4개가 깨졌다).
  //      이 앱(Vite React)의 모든 오버레이는 Radix Portal이 열고, 그 Portal은 container를 넘기지
  //      않는 한 document.body의 직계 자식으로 붙는다 — index.html의 앱 루트(#root) 밖이다(이
  //      페이지의 shadcn Dialog/AlertDialog/Select 어디도 container를 오버라이드하지 않는다,
  //      직접 확인). #root 안(=포털이 아닌 통상 앱 콘텐츠)인지로 판정하면 "포털 오버레이만" 정확히
  //      걸러지고, 툴바 버튼·캔버스 자신의 서브트리(GraphKeyboardList 포함)는 전부 #root 안이라
  //      영향받지 않는다. #root가 없는 극단적 상황(테스트 더미 DOM 등)에서는 안전 쪽으로 판정을
  //      건너뛴다.
  // 선택 대상 자체가 없으면(selectedRef.current == null) 당연히 아무 것도 하지 않는다.
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Delete' && e.key !== 'Backspace') return;
      // auto-repeat 무시(리뷰 I-2) — 키를 누르고 있으면 브라우저가 초당 수십 회 keydown을 반복
      // 발화한다. 막지 않으면 관계는 첫 DELETE만 성공하고 나머지는 404(이미 삭제됨) 토스트로,
      // 타입은 다이얼로그가 다시 열렸다 확인되며 두 번째 DELETE로 이어진다 — 성공한 단일 조작이
      // 오류 더미로 보인다.
      if (e.repeat) return;
      if (!editingRef.current) return;
      if (hasAnyPendingInput()) return;
      const active = document.activeElement;
      if (active) {
        const tag = active.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || (active as HTMLElement).isContentEditable) return;
      }
      // 가드 ④(포함 판정) — body이거나(캔버스 tap은 실제 DOM 포커스를 옮기지 않으므로 보통 이
      // 값이다) 앱 루트(#root) 안일 때만 통과한다. #root 밖(document.body 직계의 Radix Portal)에
      // 포커스가 있으면 무시한다.
      const appRoot = document.getElementById('root');
      if (active && active !== document.body && appRoot && !appRoot.contains(active)) return;
      const sel = selectedRef.current;
      if (!sel) return;
      // Backspace의 브라우저 기본 동작(뒤로 가기 등)이 여기까지 도달한 경우에만 막는다 — 위 가드를
      // 하나라도 못 넘기면 이 preventDefault 자체가 실행되지 않으므로 인스펙터 입력의 정상적인
      // Backspace 텍스트 삭제는 전혀 방해받지 않는다.
      e.preventDefault();
      if (sel.kind === 'entity') onRequestDeleteEntityRef.current?.(sel.id);
      else onRequestDeleteRelationRef.current?.(sel.id);
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
    // mount 시 1회만 바인딩 — 최신 콜백/선택 상태는 위 ref들을 통해 참조한다(다른 mount-once
    // 핸들러들과 동일한 패턴). 핸들러 내부가 참조하는 건 ref들과 hasAnyPendingInput()(컴포넌트
    // 스코프 함수지만 그 내부도 ref만 읽는다)뿐이라 exhaustive-deps가 별도 경고를 내지 않는다 —
    // 다른 컴포넌트 스코프의 state/prop을 직접 참조하는 코드를 이 핸들러에 추가하면 그 값은
    // mount 시점 클로저에 고정되어(stale) 이 근거가 더 이상 성립하지 않는다.
  }, []);

  // 요소·스타일 갱신(스키마/테마 변경) → 색 재계산 반영 후 재배치.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.style(buildStylesheet(isDark));
    cy.elements().remove();
    cy.add(elements);
    if (activeEntities.length > 0) cy.layout(BREADTHFIRST_LAYOUT).run();
  }, [elements, isDark, activeEntities.length]);

  // read 모드에서는 cy의 tap-자체선택을 꺼 둔다(리뷰 MIN-3) — cytoscape는 autounselectify가 꺼져
  // 있으면(기본값) tap만으로도 스스로 :selected를 건다. 노드는 read 모드에서 드릴다운으로 TabsContent가
  // 언마운트돼 가려지지만, 엣지는 read 모드에 상호작용이 없어(위 tap 핸들러가 editing이 아니면 그냥
  // early-return) tap해도 아무 반응이 없어야 정상인데, cy 자체 선택 때문에 :selected 링(위 edge:selected
  // 스타일)이 남는다. editing일 때만 tap-선택을 허용한다.
  useEffect(() => {
    cyRef.current?.autounselectify(!editing);
  }, [editing]);

  // 드래그 관계 연결은 편집 모드에서만 켠다(브리프 설계 노트 — autounselectify effect와 관심사가
  // 달라 합치지 않는다: 저건 cy 자체 선택 토글, 이건 edgehandles 제스처 토글). enableDrawMode()가
  // 켜지면 노드 tap이 곧 엣지 그리기 시작점이 된다 — tapstart를 가로챌 뿐 이후 'tap' 이벤트는 그대로
  // 발생하므로(cytoscape가 별개로 합성) 위 노드 tap 핸들러의 선택/드릴다운은 방해받지 않는다(직접
  // 확인: 소스의 addCytoscapeListeners가 tapstart에서만 drawMode를 검사하고, gesture 완료 판정은
  // autoungrabify와 무관한 별도의 grabbingNode 플래그를 쓴다 — 이 값은 cy 'drag'/'free' 이벤트로만
  // 바뀌는데 노드가 애초에 autoungrabify(true)라 그 이벤트 자체가 발생하지 않아 항상 false로 남는다.
  // 즉 autoungrabify(true)와 edgehandles 드래그 시작은 서로 간섭하지 않는다). read 모드로 꺼지면
  // disableDrawMode()로 즉시 막아 핸들 자체가 나타나지 않게 한다(브리프 테스트 4).
  // pendingConnection/pendingRename/pendingCreate 정리도 이 effect 안에서 함께 한다(리뷰 I-3, S3
  // Task 3에서 리네임·생성으로 확장) — draw mode 토글의 직접적인 부산물이라 관심사가 다르지 않다
  // (브리프의 "autounselectify effect와 합치지 마라"는 그 effect 이야기지, draw mode와 그 부산물
  // 정리를 나누라는 뜻이 아니다). editing이 사용자 조작 없이도 꺼질 수 있다(archived 전환, 다른
  // 온톨로지 선택 등, OntologyPage의 canEdit) — 그때 입력이 열려 있으면 읽기 모드 캔버스 위에
  // 인라인 입력이 남고, onConnect/onRenameEntity/onCreateEntityAt은 showEditor와 무관하게 항상
  // 내려가 있어 그 상태에서 Enter를 치면 읽기 모드에서 요청이 나간다. 렌더 조건(아래 JSX)도 editing을
  // 함께 검사해 두 겹으로 막는다.
  useEffect(() => {
    // pending 정리는 edgehandles 인스턴스 존재와 무관하다(리뷰 M-5) — 아래 eh null 체크보다 앞에 둔다.
    // 오늘은 eh와 cy가 같은 mount effect에서 함께 만들어져 도달 불가능한 경로지만, 나중에 edgehandles
    // 생성이 조건부가 되거나 실패하면 이 순서가 "읽기 모드 캔버스 위에 인라인 입력이 남는" 결함을
    // 조용히 되살릴 수 있다.
    if (!editing) {
      setPendingConnection(null);
      setPendingRename(null);
      setPendingCreate(null);
    }
    const eh = ehRef.current;
    if (!eh) return;
    if (editing) {
      eh.enableDrawMode();
    } else {
      eh.disableDrawMode();
    }
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
  // 캔버스(elements)와 같은 activeEntities/activeRelations를 참조한다 — 타입 필터(#411)가 걸리면
  // 캔버스에서 사라진 타입·관계가 이 대체 목록에도 똑같이 사라져야 SR 사용자가 "필터가 안 먹힌다"고
  // 오인하지 않는다(InstanceGraph의 대체 목록·검색 동기화 원칙과 동일, #405/#404 계열).
  const keyboardItems = useMemo(() => {
    const degree = new Map<string, number>();
    for (const r of activeRelations) {
      degree.set(r.subject, (degree.get(r.subject) ?? 0) + 1);
      degree.set(r.object, (degree.get(r.object) ?? 0) + 1);
    }
    return activeEntities.map((e) => ({
      id: e.type,
      label: `${e.type} — 관계 ${degree.get(e.type) ?? 0}개`,
    }));
  }, [activeEntities, activeRelations]);

  // CanvasInlineInput 커밋 — 검증 순서는 validateRelationName → validateTripleUniqueness(브리프 설계
  // 노트). 로컬 검증이 실패하면 CanvasInlineInput이 이 함수를 아예 부르지 않으므로(validate prop이
  // 먼저 걸러낸다) 여기 도달했다면 이미 통과한 값이다 — 저장 자체(네트워크 호출)는 onConnect(부모,
  // OntologyPage)의 몫이다. 이 컴포넌트는 read 모드에서도 쓰이는 유일한 캔버스라 API 계층에
  // 직접 묶지 않는다(팀 확인).
  const handleConnectCommit = (value: string) => {
    const connect = onConnectRef.current;
    if (!pendingConnection || !connect) return;
    const { subjectTypeId, objectTypeId } = pendingConnection;
    setSubmitting(true);
    void connect(subjectTypeId, objectTypeId, value)
      .then((ok) => {
        // 실패(false)면 pendingConnection을 그대로 둔다 — 입력이 다시 활성화되어(finally에서
        // submitting=false) 사용자가 같은 값으로 재시도할 수 있다. 같은 CanvasInlineInput 인스턴스가
        // 유지되므로(remount 아님) 그 컴포넌트 자신이 disabled: true→false 전환에서 committingRef를
        // 되돌려야 "바깥 클릭 = 취소"가 다시 살아난다(C-1 수정, CanvasInlineInput.tsx 참고) — 여기서는
        // 그 계약이 지켜진다고 가정할 뿐 이 함수가 직접 보장하지 않는다. 에러 토스트는 onConnect 구현
        // (mutations.addRelation 내부 runMutation)이 이미 띄운다.
        if (ok) setPendingConnection(null);
      })
      .catch(() => {
        // onConnect(현재 OntologyPage 구현)는 runMutation이 모든 예외를 삼켜 reject하지 않지만,
        // 다른 onConnect 구현이 물리면 reject할 수 있다(리뷰 N-6) — unhandled rejection만 막는다.
        // pendingConnection은 그대로 둬 위와 동일하게 재시도 가능한 상태를 유지한다.
      })
      .finally(() => setSubmitting(false));
  };

  // 리네임 인라인 입력 커밋 — validate prop(validateEntityTypeName, excludeName=currentName)이 이미
  // blank·중복을 걸렀으므로 여기 도달했다면 통과한 값이다. 브리프 설계 노트: "값이 기존과 같으면
  // 요청 없이 닫는다" — CanvasInlineInput의 trimmed 값과 currentName을 비교한다(둘 다 trim된 문자열,
  // CanvasInlineInput.commit이 trim 후 넘긴다). 이 분기가 없으면 사용자가 값을 안 바꾸고 Enter만 쳐도
  // 매번 PATCH가 나간다 — 특히 blur 자동 커밋 계열(useAutosaveText)과 달리 이 흐름은 Enter=명시적
  // 제출이라 "안 바꿨다"는 신호가 값 비교 말고는 없다.
  const handleRenameCommit = (value: string) => {
    const rename = onRenameEntityRef.current;
    if (!pendingRename || !rename) return;
    if (value === pendingRename.currentName) {
      setPendingRename(null);
      return;
    }
    setSubmitting(true);
    void rename(pendingRename.entityTypeId, value)
      .then((ok) => {
        // 실패 시 pendingRename을 유지한다 — handleConnectCommit과 동일한 이유(사용자가 친 이름을
        // 잃지 않고 재시도할 수 있어야 한다). 서버 중복 검사 실패(#302류 동시 편집 레이스)가 여기
        // 해당한다 — validate prop은 렌더마다 새로 만들어지는 클로저라 커밋 시점의 최신 schema를
        // 보므로 "마운트 시점 목록으로 고정된다"는 뜻은 아니다. 그래도 서버가 최종 방어선인 이유는
        // 레이스의 창이 다르다: 로컬 검증을 통과한 "순간"과 서버가 그 PATCH를 실제로 처리하는
        // "순간" 사이에 다른 세션이 같은 이름으로 타입을 만들 수 있다.
        if (ok) setPendingRename(null);
      })
      .catch(() => {
        // handleConnectCommit과 동일한 이유 — unhandled rejection만 막는다.
      })
      .finally(() => setSubmitting(false));
  };

  // 빈 캔버스 더블클릭 생성 커밋 — CreateEntityTypeForm(EntityInspector.tsx)과 같은 기본값
  // (description/naming: '', resolution: 'embedding')을 쓴다(브리프 설계 노트 — 두 생성 경로가 다른
  // 기본값을 쓰면 안 된다). description/naming 빈 문자열은 서버가 거부하지 않는다 — OntologyRules.
  // validateEntityTypeCommon은 null만 차단하고(#305) blank는 막지 않는다(firehub-api/.../OntologyRules.java
  // 직접 확인). 성공 시 selectModelElement로 선택 전환하는 것은 OntologyPage(onCreateEntityAt 구현)의
  // 몫이다 — CreateRelationForm/handleConnectCommit과 같은 이유로 이 컴포넌트는 선택 상태를 모른다.
  const handleCreateCommit = (value: string) => {
    const create = onCreateEntityAtRef.current;
    if (!pendingCreate || !create) return;
    setSubmitting(true);
    void create(value)
      .then((ok) => {
        if (ok) setPendingCreate(null);
      })
      .catch(() => {
        // handleConnectCommit과 동일한 이유 — unhandled rejection만 막는다.
      })
      .finally(() => setSubmitting(false));
  };

  return (
    // relative: 대체 목록이 포커스 시 캔버스 위 오버레이로 뜨는 기준 박스이자(리뷰 N-2)
    // CanvasInlineInput의 좌표계 원점이기도 하다 — renderedPosition()이 이 박스 기준이라, 입력을
    // 다른 곳(예: 부모 OntologyPage)에 렌더하면 좌표가 어긋난다. CanvasInlineInput을 이 컴포넌트
    // 안에서 렌더해야 하는 진짜 이유가 이거다.
    <div className="relative h-full w-full" data-testid="schema-graph" data-node-count={activeEntities.length}>
      {/* canvas는 대체 텍스트가 없어 접근성 트리에서 제외 — 텍스트 대체물은 GraphKeyboardList가 담당한다. */}
      <div ref={containerRef} className="h-full w-full" aria-hidden="true" />

      {/* 드래그 관계 연결 인라인 입력(S3 Task 2) — ehcomplete가 채운 pendingConnection이 있을 때만
          뜬다. editing도 함께 검사한다(리뷰 I-3) — [editing] effect가 꺼질 때 pendingConnection을
          비워 주지만, 렌더 조건에서도 한 번 더 막아 두 겹으로 방어한다(사용자 조작 없이 editing이
          꺼지는 경로가 있다 — canEdit이 폴링/무효화로 바뀔 수 있다). */}
      {editing && pendingConnection && (
        <CanvasInlineInput
          x={pendingConnection.x}
          y={pendingConnection.y}
          label="관계명"
          placeholder="관계명"
          disabled={submitting}
          data-testid="canvas-connect-input"
          onCommit={handleConnectCommit}
          onCancel={() => setPendingConnection(null)}
          validate={(value) =>
            validateRelationName(value, pendingConnection.subjectName, pendingConnection.objectName) ??
            validateTripleUniqueness(schema.relations, pendingConnection.subjectName, value, pendingConnection.objectName)
          }
        />
      )}

      {/* 노드 더블클릭 리네임 인라인 입력(S3 Task 3) — dbltap 핸들러가 채운 pendingRename이 있을
          때만 뜬다. editing 이중 방어(위 pendingConnection과 동일한 이유). initialValue로 현재 이름을
          프리필해 더블클릭 직후 바로 전체 선택 상태로 편집을 시작할 수 있게 한다(CanvasInlineInput의
          mount 시 select() 재사용). */}
      {editing && pendingRename && (
        <CanvasInlineInput
          x={pendingRename.x}
          y={pendingRename.y}
          initialValue={pendingRename.currentName}
          label="타입 이름"
          placeholder="타입 이름"
          disabled={submitting}
          data-testid="canvas-rename-input"
          onCommit={handleRenameCommit}
          onCancel={() => setPendingRename(null)}
          validate={(value) =>
            validateEntityTypeName(
              value,
              schema.entities.map((e) => e.type),
              pendingRename.currentName,
            )
          }
        />
      )}

      {/* 빈 캔버스 더블클릭 생성 인라인 입력(S3 Task 3) — dbltap 핸들러(evt.target === cy)가 채운
          pendingCreate가 있을 때만 뜬다. editing 이중 방어(위와 동일한 이유). excludeName을 넘기지
          않는다 — 새 타입이라 제외할 자기 자신이 없다(CreateEntityTypeForm의 validateEntityTypeName
          호출과 동일). */}
      {editing && pendingCreate && (
        <CanvasInlineInput
          x={pendingCreate.x}
          y={pendingCreate.y}
          label="새 타입 이름"
          placeholder="타입 이름"
          disabled={submitting}
          data-testid="canvas-create-input"
          onCommit={handleCreateCommit}
          onCancel={() => setPendingCreate(null)}
          validate={(value) =>
            validateEntityTypeName(
              value,
              schema.entities.map((e) => e.type),
            )
          }
        />
      )}

      {/* 키보드·스크린리더 전용 타입 목록(#326). editing이면 Enter가 인스펙터 선택(onSelectEntity)으로
          가야 한다 — 그대로 onTypeClick(드릴다운)에 묶어 두면 마우스는 선택, 키보드는 인스턴스 탭으로
          이탈해 편집기가 닫혀 버린다(리뷰 IMP-3, 같은 컨트롤이 모드에 따라 반대로 동작하는 오동작). */}
      <GraphKeyboardList
        label={`지식 모델 타입 ${activeEntities.length}개, 관계 ${activeRelations.length}개`}
        items={keyboardItems}
        onActivate={
          editing
            ? (type) => {
                // 상호 배타 불변식의 네 번째 방향(최종 리뷰 라운드 M-2) — tap/두 dbltap/ehcomplete/
                // Delete는 전부 hasAnyPendingInput()으로 인라인 입력 커밋 중 개입을 막는데, 이
                // 목록의 Enter 활성화만 그 가드가 빠져 있었다. 커밋 중(disabled) 구간에 키보드로
                // 다른 타입을 선택하면 인스펙터가 갈아치워지고, 그 뒤 커밋이 실패하면
                // CanvasInlineInput의 재활성화 effect가 사용자 조작 없이 포커스를 입력으로 되돌려
                // 빼앗는다 — 마우스 tap 경로는 이미 이 가드로 막혀 있던 바로 그 상황이다.
                if (hasAnyPendingInput()) return;
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
