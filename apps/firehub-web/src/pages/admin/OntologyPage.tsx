import { AlertCircle, Boxes, PanelLeft, PanelLeftClose, PenLine, Plus } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { SearchInput } from '@/components/ui/search-input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useOntologyById, useOntologyGraph, useOntologyList } from '@/hooks/queries/useOntology';
import { useOntologyElementMutations } from '@/hooks/queries/useOntologyElement';
import { useAuth } from '@/hooks/useAuth';
import { affectedRelationsFor, isLastActiveEntityType } from '@/lib/ontology-validation';
import type { GraphNode } from '@/types/ontology';

import InstanceGraph from './components/InstanceGraph';
import DeleteTypeConfirm from './components/model-editor/DeleteTypeConfirm';
import EntityInspector from './components/model-editor/EntityInspector';
import ModelOutline from './components/model-editor/ModelOutline';
import RelationInspector from './components/model-editor/RelationInspector';
import SaveStatusChip from './components/model-editor/SaveStatusChip';
import NodeDetailDrawer from './components/NodeDetailDrawer';
import OntologyCreateDialog from './components/OntologyCreateDialog';
import OntologyEmptyState from './components/OntologyEmptyState';
import OntologyManageDialog from './components/OntologyManageDialog';
import OntologySelect from './components/OntologySelect';
import OntologyStatusBanner from './components/OntologyStatusBanner';
import SchemaGraph, { type SchemaGraphSelection } from './components/SchemaGraph';
import TypeFilterPanel from './components/TypeFilterPanel';

// 캔버스 로딩 중 표시하는 스켈레톤 — 컨테이너를 꽉 채워 레이아웃 시프트를 막는다.
function GraphLoading() {
  return <Skeleton className="h-full w-full" />;
}

// 캔버스 에러 상태 — 아이콘 + 메시지 + 재시도 버튼(하우스 error 패턴). 재시도는 해당 쿼리 refetch를 호출한다.
function GraphError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
      <AlertCircle className="h-10 w-10 text-destructive" />
      <p className="text-sm font-medium">{message}</p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        다시 시도
      </Button>
    </div>
  );
}

// 온톨로지 시각화 페이지 — 풀하이트 에디터 셸(툴바 + 좌측 타입 필터/아웃라인 + 캔버스 + 리사이즈
// 인스펙터). 인스턴스 탭(그래프 탐색)은 여전히 읽기 전용이지만, 스키마 탭은 이제 이 페이지가 요소
// 단위 편집기(수정 모드 토글, ModelOutline/EntityInspector/RelationInspector)를 직접 소유한다
// (M-1, S2 최종 리뷰 — 전체 문서 모달을 제거한 Task 6부터 더 이상 "읽기 전용"이 아니었다).
export default function OntologyPage() {
  // 온톨로지 목록 — 기본 온톨로지 id를 여기서 파생시킨다(아래 defaultOntologyId). schema/graph 훅보다
  // 먼저 선언해야 그 파생값을 바로 아래에서 쓸 수 있다.
  const { data: ontologies } = useOntologyList('all');
  // 인스턴스 탭(Neo4j 적재 그래프)의 타입 어휘·schemaVersion 비교 기준 — 그래프 탐색이 어느 온톨로지를
  // 보고 있는지와 무관하게 항상 기본 온톨로지 고정이다(레거시 bare 온톨로지 개념). 예전에는
  // useOntologySchema()(bare GET /ontology)로 읽었지만, 그 응답은 서버에서 늘 기본 온톨로지였다 —
  // useOntologyById(defaultOntologyId)로 바꿔도 같은 데이터를 같은 쿼리키로 읽을 뿐이다(S2 Step 1.5).
  // (리뷰 MIN-1) id를 1로 하드코딩하지 않는다 — OntologySummary.isDefault가 정확히 이 매직넘버를
  // 피하려고 서버가 계산해 내려주는 필드다(기본 온톨로지 판정 기준이 바뀌어도 프론트가 값을 다시
  // 선언할 필요가 없도록). 목록 로딩 전 한 틱은 null이지만 useOntologyById가 enabled 가드를 이미 갖고
  // 있어 안전하다.
  // 이 마이그레이션으로 useOntology.ts의 레거시 PUT 뮤테이션이 bare ['ontology'] 키를 무효화해 주던
  // 특례 분기가 더 이상 필요하지 않게 된다(이 페이지가 그 키를 아예 읽지 않으므로) — 그 분기 자체는
  // useOntology.ts 쪽에서 함께 정리했다(MIN-2).
  const defaultOntologyId = ontologies?.find((o) => o.isDefault)?.id ?? null;
  const { data: schema } = useOntologyById(defaultOntologyId);
  // 인스턴스 그래프(Neo4j 적재분)는 여전히 온톨로지 id로 스코프되지 않는 단일 엔드포인트다
  // (getGraph()에 id 파라미터가 없다) — 그래서 selectedOntologyId를 바꿔도 이 쿼리는 영향을 받지 않고,
  // 요소 단위 편집 뮤테이션도 이 키를 무효화할 이유가 없다(스키마 편집이 이미 적재된 그래프 노드를
  // 다시 쓰지는 않으므로 — Neo4j 재적재는 별도 임포트 파이프라인의 몫이다).
  const { data: graph, isLoading: isGraphLoading, isError, refetch: refetchGraph } = useOntologyGraph();

  // 탭 상태는 URL(:view)에서 파생 — 사이드바 '그래프 탐색'(explore)/'지식 모델'(model) 항목과 하이라이트를 동기화한다.
  // explore↔instance, model↔schema. view가 없거나 알 수 없으면 그래프 탐색(instance)으로 폴백.
  const { view } = useParams<{ view?: string }>();
  const navigate = useNavigate();
  const tab = view === 'model' ? 'schema' : 'instance';
  // 탭 클릭 → 해당 뷰 URL로 이동. 동일 라우트(:view 파라미터만 변경)이므로 리마운트 없이 필터/선택 state가 보존된다.
  const setTab = (next: string) => navigate(`/knowledge-graph/${next === 'schema' ? 'model' : 'explore'}`);
  const [activeTypes, setActiveTypes] = useState<Set<string>>(new Set()); // 빈 Set = 전체
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<GraphNode | null>(null);
  const [focusKey, setFocusKey] = useState<string | null>(null); // 관계 클릭 내비게이션 포커스 대상
  const [filterCollapsed, setFilterCollapsed] = useState(false); // 좌측 타입 필터 패널 접기
  const [grouped, setGrouped] = useState(false); // 타입 묶기(compound 번들)
  // 요소 단위 편집기(모드 토글) — 3-pane(아웃라인/캔버스/인스펙터) 편집 셸을 켠다. 전체 문서를
  // 왕복시키던 모달(OntologyEditDialog)은 Task 6에서 제거됐고, 이 토글이 유일한 편집 진입점이다.
  const [modelEditMode, setModelEditMode] = useState(false);
  // 편집기에서 선택된 요소(타입/관계) — 아웃라인 클릭과 캔버스 클릭이 이 하나의 state를 공유해 동기화된다.
  const [modelSelected, setModelSelected] = useState<SchemaGraphSelection | null>(null);
  // "새 관계 만들기" 폼이 열려 있는지(Task 5) — 새 관계는 끝점(주어/목적어)을 생성 시점에 정해야 해서
  // 기존 요소를 지목하는 modelSelected(항상 실존하는 id)와 같은 모델에 담을 수 없다. ModelOutline의
  // "관계 추가" 버튼이 이 state를 켠다.
  const [creatingRelation, setCreatingRelation] = useState(false);
  // "새 타입 만들기" 폼이 열려 있는지(S2 Task 6 백로그, Task 5 리뷰 M-8 이관) — creatingRelation과
  // 대칭. 전체 문서 모달이 유일한 엔티티 타입 생성 경로였는데, 그 모달을 지우면서 이 상태가 그
  // 자리를 대체한다.
  const [creatingEntity, setCreatingEntity] = useState(false);
  // 캔버스 Delete 키로 요청된 엔티티 타입 삭제 확인(S3 Task 4) — EntityInspector가 이미 같은
  // DeleteTypeConfirm을 트리거 버튼(entity-delete-trigger)으로 여는 인스턴스를 갖고 있지만, 그 트리거는
  // sm 미만에서 숨겨지거나(인스펙터 pane 자체가 hidden sm:block) 생성 폼 등 다른 내용을 보여주는 중일
  // 수 있어 캔버스 요청이 그 트리거에 기댈 수 없다 — controlled open(entity id 자체를 상태로 둔다)의
  // 별도 인스턴스를 둔다. 관계 삭제는 확인이 없어(브리프 §상호작용) 별도 상태가 필요 없다.
  const [canvasDeleteEntityId, setCanvasDeleteEntityId] = useState<number | null>(null);
  const { isAdmin } = useAuth();

  // 스키마 탭에서 보고 있는 온톨로지. 인스턴스 탭(Neo4j 적재 그래프)은 여전히 기본 온톨로지 기반이므로
  // 이 선택은 스키마 탭에만 영향을 준다 — 여기까지 번지면 타입 필터가 조용히 어긋난다.
  const [selectedOntologyId, setSelectedOntologyId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [manageOpen, setManageOpen] = useState(false);
  // 선택 중이던 온톨로지가 관리 다이얼로그에서 삭제되면 목록에서 사라진다 — selectedOntologyId가
  // 죽은 id를 그대로 들고 있으면 useOntologyById가 계속 404를 내 캔버스가 GraphError로 굳는다.
  // setState+effect 대신 파생 계산으로 처리한다: 객체를 먼저 찾고 그 id를 파생시키면, 목록에 없는
  // selectedOntologyId(삭제됨/미선택)는 find가 자연히 undefined를 반환해 폴백(첫 활성/첫 항목)으로
  // 넘어간다 — 존재 여부를 별도로 추적할 필요가 없다.
  const selectedOntology =
    ontologies?.find((o) => o.id === selectedOntologyId) ??
    ontologies?.find((o) => o.status === 'active') ??
    ontologies?.[0] ??
    null;
  const effectiveOntologyId = selectedOntology?.id ?? null;

  const {
    data: selectedSchema,
    isError: isSelectedSchemaError,
    refetch: refetchSelectedSchema,
  } = useOntologyById(effectiveOntologyId);

  // 편집 가능 여부 — 편집 버튼과 빈 상태 CTA가 동일 조건을 각각 조합하던 것을 하나로 합쳤다.
  // archived는 서버가 409로 거부하므로 애초에 진입점을 보여주지 않는다. 모드 토글도 같은 조건을 재사용한다.
  const canEdit = isAdmin && selectedOntology?.status !== 'archived';

  // 요소 단위 편집 뮤테이션(Task 1) — 이 훅은 OntologyPage에서 "단 한 번만" 호출하고 반환 객체
  // 전체를 인스펙터(Task 4/5)에 prop으로 내려준다. 인스펙터가 이 훅을 다시 호출하면 saveState가
  // 인스턴스마다 갈라져, 실제 뮤테이션은 인스펙터 쪽에서 일어나는데 이 saveState를 구독하는 툴바의
  // SaveStatusChip은 영원히 idle에 머문다 — 자동 저장이 조용히 침묵한 것처럼 보인다(Task 3 리뷰
  // IMP-1). effectiveOntologyId가 아직 null인 순간(목록 로딩 중)에도 훅 호출 자체는 규칙상 항상
  // 실행되어야 하므로 더미 id(-1)를 넣는다 — canEdit이 selectedOntology 존재를 전제하므로 실제
  // 저장 버튼/인스펙터는 그 시점엔 어차피 렌더되지 않아 무해하다.
  const elementMutations = useOntologyElementMutations(effectiveOntologyId ?? -1);
  const { saveState, retry, canRetry } = elementMutations;
  // 3-pane 요소 편집기가 실제로 보여야 하는지 — 스키마 탭 + 토글 켜짐 + 권한까지 모두 갖춰야 한다.
  // canEdit이 꺼지면(예: 관리 다이얼로그에서 archived로 전이) 토글 state와 무관하게 즉시 닫혀야 하므로
  // modelEditMode를 곱해 파생시킨다(별도 effect로 끄는 대신).
  const showEditor = tab === 'schema' && modelEditMode && canEdit;

  // 온톨로지를 바꾸면 이전 선택/편집 상태가 새 컨텍스트에 잘못 남지 않도록 초기화한다.
  // useEffect 대신 렌더 중 이전 값 비교(React 권장 패턴)로 처리한다 — setState-in-effect의
  // 연쇄 렌더 없이 같은 커밋 안에서 리셋이 반영된다.
  // 타입 삭제 확인 다이얼로그의 포커스 복귀 대상(M-2, Task 6 리뷰) — ModelOutline의 "타입 추가"
  // 버튼에 붙는다(DeleteTypeConfirm.tsx 주석 참고). 선택이 바뀌어도 리마운트되지 않는 안정적인
  // 대상이어야 하므로 훅 최상단에서 한 번만 만든다.
  const addEntityTypeButtonRef = useRef<HTMLButtonElement>(null);
  // 캔버스 Delete의 in-flight 중복 요청 차단(리뷰 I-2) — SchemaGraph의 keydown 가드는 e.repeat와
  // 모달 존재로 대부분을 막지만, 두 구멍이 남는다: (1) Radix AlertDialogAction은 클릭 즉시 다이얼로그를
  // 닫으므로(exit 애니메이션이 끝나면 DOM에서도 사라진다) DELETE 응답을 기다리는 동안은 "모달이 열려
  // 있다" 가드가 더 이상 성립하지 않는다. (2) 관계 삭제는 확인 다이얼로그 자체가 없어(브리프
  // §상호작용) 응답을 기다리는 내내 아무 모달 가드도 없다. 두 경우 모두 응답 대기 중 같은 대상에
  // 대한 재요청은 조용히 무시한다 — 키를 굳이 auto-repeat 없이 두 번 눌러도 마찬가지다.
  const deletingElementKeysRef = useRef<Set<string>>(new Set());
  const prevOntologyIdRef = useRef(effectiveOntologyId);
  if (prevOntologyIdRef.current !== effectiveOntologyId) {
    prevOntologyIdRef.current = effectiveOntologyId;
    if (modelEditMode) setModelEditMode(false);
    if (modelSelected) setModelSelected(null);
    if (creatingRelation) setCreatingRelation(false);
    if (creatingEntity) setCreatingEntity(false);
    if (canvasDeleteEntityId != null) setCanvasDeleteEntityId(null);
  }

  // (#412) activeTypes 자동 동기화 — TypeFilterPanel이 실제로 렌더링하는 스키마(탭에 따라
  // selectedSchema 또는 schema)의 타입 목록이 바뀌면, "부분 선택"(activeTypes가 빈 Set이 아닌)
  // 상태일 때만 새로 추가된 타입을 activeTypes에 합류시킨다. 그러지 않으면 사용자가 건드린 적
  // 없는 새 타입이 "구체화된 activeTypes 목록에 없다"는 이유만으로 첫 등장부터 꺼진 채 나타난다
  // (TypeFilterPanel.isActive는 activeTypes.size===0 || activeTypes.has(t)로 판정). 빈 Set(전체
  // 표시) 상태에서는 그대로 둔다 — 빈 Set 자체가 이미 "새 타입 포함 전체 표시"를 의미하므로 손댈
  // 필요가 없다. allTypes 정의(entities.map(e => e.type))는 TypeFilterPanel.tsx의 계산과 반드시
  // 같아야 새 타입 판별이 어긋나지 않는다.
  // 렌더 중 이전 값 비교 패턴(useEffect 대신, 위 prevOntologyIdRef와 동일한 취지)이지만, "이전 타입
  // 목록"을 diff에 실제로 써야 해서(단순 비교·리셋이 아니라 추가분 계산) ref가 아니라 state로 이전
  // 값을 들고 있는다 — react-hooks/refs 린트 규칙이 렌더 중 ref.current 값을 콜백/파생 계산에
  // 재사용하는 것을 금지하기 때문이다(순수 비교·대입만 허용). React 공식 문서의 "이전 props/state를
  // 저장" 패턴을 그대로 따른다.
  const filterSchema = tab === 'schema' ? selectedSchema : schema;
  const currentTypeNames = useMemo(() => (filterSchema?.entities ?? []).map((e) => e.type), [filterSchema]);
  const [prevTypeNames, setPrevTypeNames] = useState(currentTypeNames);
  if (currentTypeNames !== prevTypeNames) {
    setPrevTypeNames(currentTypeNames);
    const addedTypes = currentTypeNames.filter((t) => !prevTypeNames.includes(t));
    if (addedTypes.length > 0 && activeTypes.size > 0) {
      setActiveTypes(new Set([...activeTypes, ...addedTypes]));
    }
  }

  const nodesByKey = useMemo(() => new Map((graph?.nodes ?? []).map((n) => [n.key, n])), [graph]);

  // 인스펙터에 내려줄 선택된 엔티티 타입 — ModelOutline과 마찬가지로 id 없는 항목은 편집 대상이 될 수
  // 없으므로 걸러낸다(요소 단위 편집 API가 id로 대화한다).
  const selectedEntity =
    modelSelected?.kind === 'entity'
      ? selectedSchema?.entities.find(
          (e): e is typeof e & { id: number } => e.id === modelSelected.id && e.id != null,
        )
      : undefined;
  // 인스펙터에 내려줄 선택된 관계(Task 5) — 위 selectedEntity와 대칭. ModelOutline/SchemaGraph가
  // id 없는 관계를 이미 걸러내므로 여기서 찾은 관계는 항상 id를 가진다.
  const selectedRelation =
    modelSelected?.kind === 'relation'
      ? selectedSchema?.relations.find(
          (r): r is typeof r & { id: number } => r.id === modelSelected.id && r.id != null,
        )
      : undefined;

  // 아웃라인/캔버스에서 요소를 선택하면 "새 관계 만들기"/"새 타입 만들기" 폼이 열려 있어도 닫는다 —
  // 셋을 동시에 보여줄 자리가 없다(인스펙터는 한 번에 하나만 보여준다).
  const selectModelElement = (selection: SchemaGraphSelection) => {
    setCreatingRelation(false);
    setCreatingEntity(false);
    setModelSelected(selection);
  };

  // 인스펙터 관계 클릭 → 대상 노드를 인스펙터에 로드하고 캔버스에서 포커싱한다.
  const navigateTo = (key: string) => {
    const target = nodesByKey.get(key);
    if (!target) return;
    setSelected(target);
    setFocusKey(key);
  };

  // 범례 클릭 → 타입 필터 토글.
  // (#404) "빈 Set = 전체 표시" 관례와 "add/remove만 하는 toggle"이 결합하면, 기본(전체 표시)
  // 상태에서 이미 pressed인 버튼 하나를 클릭했을 때 "그것만 끔"이 아니라 "그것만 남기고 전부
  // 끔"이 되어버렸다(빈 Set에 클릭한 타입 하나만 add되므로). 이를 막으려면 빈 Set(전체 활성)
  // 상태에서의 첫 클릭은 "전체 타입 목록으로 채운 뒤 클릭한 타입만 제거"해야 사용자 기대("이
  // 타입 하나만 끈다")와 일치한다. allTypes는 TypeFilterPanel이 실제로 렌더링 중인(현재
  // 탭·스키마 기준) 전체 타입 목록을 그대로 넘겨준다 — 여기서 별도로 재계산하면 두 곳의
  // "전체 타입" 정의가 어긋날 위험이 있다.
  const toggleType = (t: string, allTypes: string[]) =>
    setActiveTypes((prev) => {
      const base = prev.size === 0 ? new Set(allTypes) : new Set(prev);
      if (base.has(t)) base.delete(t);
      else base.add(t);
      // 토글 결과 전체 타입이 다시 모두 포함되면 "전체 표시" 관례를 유지하기 위해 빈 Set으로 되돌린다.
      return base.size === allTypes.length ? new Set() : base;
    });

  // 스키마 탭에서 타입 클릭 → 인스턴스 탭으로 이동하며 해당 타입만 필터(드릴다운 브리지).
  const drillDown = (type: string) => {
    setActiveTypes(new Set([type]));
    setTab('instance');
  };

  // 캔버스 Delete 키가 요청한 엔티티 타입 삭제 확인 대상(S3 Task 4) — id만 상태로 들고 있고 실제
  // entity/affectedRelations는 매 렌더 selectedSchema에서 파생한다(동시 편집으로 그 사이 타입이
  // 사라지면 find가 undefined를 내 다이얼로그가 조용히 안 뜬다). affectedRelationsFor는
  // EntityInspector와 공유하는 계산이다(ontology-validation.ts, 리뷰 M-3).
  const canvasDeleteEntity =
    canvasDeleteEntityId != null ? selectedSchema?.entities.find((e) => e.id === canvasDeleteEntityId) : undefined;
  // entity.id가 아니라 canvasDeleteEntityId(상태로 들고 있던 number)를 쓴다 — EntityTypeDef.id는
  // 레거시 폴백(엔티티에 id가 없는 스키마)을 감안해 타입상 optional이라 여기서 타입 좁히기가 안 된다.
  const canvasDeleteAffectedRelations =
    canvasDeleteEntity && selectedSchema && canvasDeleteEntityId != null
      ? affectedRelationsFor(selectedSchema, canvasDeleteEntityId)
      : [];

  // 캔버스에서 Delete로 엔티티 타입 삭제를 요청 — EntityInspector의 isLastActiveType과 같은 조건
  // (isLastActiveEntityType, ontology-validation.ts, 리뷰 M-3)을 선반영한다(서버가 400으로 거부하는
  // "active 온톨로지의 마지막 타입" 삭제를 확인 다이얼로그까지 열어놓고 서버 문구로 되돌리면 이
  // 파일 전역 제약을 어긴다). 통과하면 확인 다이얼로그를 연다 — 실제 삭제는 그 다이얼로그의
  // onConfirm에서 일어난다(타입 삭제는 확인이 필수, 브리프 §상호작용).
  const requestDeleteEntity = (entityTypeId: number) => {
    if (!selectedSchema) return;
    if (isLastActiveEntityType(selectedSchema, selectedOntology?.status)) return;
    if (!selectedSchema.entities.some((e) => e.id === entityTypeId)) return;
    // 같은 타입에 대한 삭제가 이미 진행 중이면(확인 다이얼로그가 닫힌 뒤 응답을 기다리는 구간,
    // 리뷰 I-2) 다이얼로그를 다시 열지 않는다 — 다시 열면 사용자가 또 확인해 두 번째 DELETE가 나간다.
    if (deletingElementKeysRef.current.has(`entity:${entityTypeId}`)) return;
    setCanvasDeleteEntityId(entityTypeId);
  };

  // 캔버스에서 Delete로 관계 삭제를 요청 — 관계 삭제는 FK CASCADE로 함께 사라지는 것이 없어 확인
  // 없이 즉시 나간다(브리프 §상호작용, RelationInspector의 relation-delete-trigger와 동일 규칙).
  // 포커스 복귀는 EntityInspector/RelationInspector의 삭제 트리거와 같은 이유로 뮤테이션 성공 콜백
  // 안에서 명시적으로 옮긴다(#328류 — 캔버스는 포커스 대상이 아니므로 남아 있는 컨트롤인 아웃라인의
  // "타입 추가" 버튼으로 보낸다).
  const requestDeleteRelation = (relationId: number) => {
    // requestDeleteEntity와 대칭(리뷰 M-4) — 동시 편집으로 그 사이 관계가 이미 사라졌다면 조용히
    // 무시한다. 없어도 치명적이진 않다(서버가 404 → "이미 삭제된 요소입니다" 토스트로 설계된 경로를
    // 타므로) — 다만 이 검사가 없으면 같은 조작의 두 갈래(타입/관계)가 서로 다른 UX를 낸다.
    if (!selectedSchema?.relations.some((r) => r.id === relationId)) return;
    // 확인 다이얼로그가 없는 경로라 in-flight 차단이 유일한 중복 방어선이다(리뷰 I-2) — 없으면
    // 응답을 기다리는 사이 Delete를 다시 눌러 같은 관계에 두 번째 DELETE가 나간다.
    const key = `relation:${relationId}`;
    if (deletingElementKeysRef.current.has(key)) return;
    deletingElementKeysRef.current.add(key);
    void elementMutations.deleteRelation(relationId).then((result) => {
      if (!result) return;
      if (modelSelected?.kind === 'relation' && modelSelected.id === relationId) setModelSelected(null);
      addEntityTypeButtonRef.current?.focus();
    }).finally(() => {
      deletingElementKeysRef.current.delete(key);
    });
  };

  return (
    // 풀하이트 에디터 셸(디자인 시스템 Pattern D) — 고정 h-[600px] 대신 뷰포트를 채워 페이지 스크롤 클립을 없앤다.
    // Radix Tabs로 감싸 탭 a11y(role=tab)와 기존 E2E 셀렉터를 보존한다. 높이 체인 전 구간 min-h-0.
    <Tabs value={tab} onValueChange={setTab} className="flex h-full min-h-0 flex-col">
      {/* 툴바 — 좌: 패널 토글 + 제목 + 탭 전환, 우: 검색(인스턴스 탭 한정). */}
      {/* 좁은 폭(max-md)에서는 툴바를 줄바꿈하고 높이를 풀어 가로 스크롤을 없앤다(#345 SC 1.4.10).
          md 이상에서는 기존 한 줄 h-12 툴바 그대로. 원래 경계는 sm(640px)이었으나, 항목
          (아이콘+제목+탭 2개+검색/온톨로지 선택기+버튼들)이 640~768px 대역엔 실제로 한 줄에
          들어가지 못해 h1이 형제 요소에 밀려 세로로 찌그러졌다(#402) — md(768px)로 완화. */}
      <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b px-3 py-1.5 md:h-12 md:flex-nowrap md:py-0">
        {/* (리뷰 MIN-5) 편집 모드에서는 숨긴다 — ModelOutline이 TypeFilterPanel과 달리 collapsed prop을
            받지 않아, 편집 모드에서 이 버튼을 누르면 aria-pressed만 바뀌고 화면은 그대로였다(무력한 컨트롤).
            (#414) sm(640px) 미만에서는 TypeFilterPanel 자체가 collapsed 상태와 무관하게 항상 숨으므로
            이 토글도 함께 숨긴다 — 안 그러면 눌러도 아무 효과가 없는 죽은 컨트롤이 된다. */}
        {!showEditor && (
          <Button
            variant="ghost"
            size="icon"
            className="hidden h-8 w-8 sm:inline-flex"
            onClick={() => setFilterCollapsed((v) => !v)}
            aria-label={filterCollapsed ? '타입 필터 펼치기' : '타입 필터 접기'}
            aria-pressed={!filterCollapsed}
          >
            {filterCollapsed ? <PanelLeft className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
          </Button>
        )}
        {/* whitespace-nowrap 없으면 640~900px 대역에서 한글(CJK) 텍스트가 글자 단위로
            줄바꿈 기회를 허용해 min-content가 사실상 "1글자 폭"이 되고, 형제 요소(탭·아이콘)에
            밀려 h1이 세로로 찌그러진다(#402). shrink-0으로 다른 flex item에 밀려 줄어드는 것도 막는다. */}
        <h1 className="shrink-0 text-sm font-semibold whitespace-nowrap">지식그래프</h1>
        <TabsList>
          <TabsTrigger value="instance">그래프 탐색</TabsTrigger>
          <TabsTrigger value="schema">지식 모델</TabsTrigger>
        </TabsList>
        {/* 온톨로지 선택기(고정 220px)까지 들어오며 항목이 늘었다 — 이 그룹도 outer 툴바처럼
            max-md에서 줄바꿈해야 320px 리플로우(#345)와 640~900px 겹침(#402)이 깨지지 않는다. */}
        <div className="ml-auto flex flex-wrap items-center justify-end gap-x-2 gap-y-1 md:flex-nowrap">
          {/* 온톨로지 선택기 — 스키마 탭 전용. 비-ADMIN도 볼 수 있지만 관리 진입점은 없다. */}
          {tab === 'schema' && ontologies && ontologies.length > 0 && (
            <OntologySelect
              ontologies={ontologies}
              value={effectiveOntologyId}
              onChange={setSelectedOntologyId}
              onManage={isAdmin ? () => setManageOpen(true) : undefined}
            />
          )}
          {tab === 'schema' && isAdmin && (
            <Button variant="outline" size="sm" className="gap-1.5" onClick={() => setCreateOpen(true)}>
              <Plus className="h-4 w-4" />
              새 온톨로지
            </Button>
          )}
          {/* 요소 단위 편집기 모드 토글(S2) — 전체 문서를 왕복시키던 모달(OntologyEditDialog)은
              Task 6에서 제거됐고, 이 토글이 유일한 편집 진입점이다. 라벨은 "편집"이 아니라 "수정
              모드"를 그대로 유지한다 — 원래는 모달의 "편집" 버튼과 Playwright getByRole 부분 일치
              strict mode 충돌을 피하기 위한 이름이었지만, 지금은 순전히 하위 호환 때문이다: 다수의
              기존 E2E가 이미 "수정 모드" 텍스트로 이 버튼을 찾으므로 이름을 바꾸면 그 셀렉터가 전부
              깨진다. 같은 canEdit 조건을 재사용한다: 비-ADMIN·archived 온톨로지에는 노출되지 않는다. */}
          {tab === 'schema' && canEdit && selectedSchema && (
            <Button
              variant={modelEditMode ? 'secondary' : 'outline'}
              size="sm"
              className="gap-1.5"
              onClick={() => {
                // 껐다 다시 켰을 때 이전 세션의 선택이 새어 나오지 않도록 토글 자체에서 초기화한다
                // (온톨로지 전환 리셋과 별개 경로 — 저건 effectiveOntologyId가 바뀔 때만 돈다).
                setModelEditMode((v) => !v);
                setModelSelected(null);
                setCreatingRelation(false);
                setCreatingEntity(false);
              }}
              aria-pressed={modelEditMode}
            >
              <PenLine className="h-4 w-4" />
              수정 모드
            </Button>
          )}
          {/* showEditor 여부와 무관하게 항상 마운트한다(IMP-2) — 편집기를 껐다 켤 때 saveState가
              이미 idle이 아닌 채로 컴포넌트가 새로 생기면(리전과 초기 내용이 같은 순간에 나타나면)
              스크린리더가 그 내용을 낭독하지 않는다. state가 계속 idle이면 시각적으로도 아무 것도
              그리지 않으므로 편집 모드 밖에서 툴바에 잡음이 늘지 않는다. */}
          {/* 404 실패는 saveState를 'error'로 만들지만 재시도 대상이 아니다(대상이 이미 삭제됨) —
              canRetry로 그 경우만 걸러 dead 버튼을 그리지 않는다(Task 4 리뷰 I-2). */}
          <SaveStatusChip state={saveState} onRetry={canRetry ? retry : undefined} />
          {/* search-first: 검색을 캔버스 위 별도 줄이 아닌 툴바로 승격(인스턴스 탭에서만 의미 있음). */}
          {tab === 'instance' && (
            <>
              <SearchInput placeholder="이름 검색" value={search} onChange={setSearch} className="w-64" />
              {/* 타입 묶기 토글 — 타입별 compound 번들로 접어 밀집을 줄인다. */}
              <Button
                variant={grouped ? 'secondary' : 'ghost'}
                size="sm"
                className="gap-1.5"
                onClick={() => setGrouped((v) => !v)}
                aria-pressed={grouped}
              >
                <Boxes className="h-4 w-4" />
                타입 묶기
              </Button>
            </>
          )}
        </div>
      </div>

      {/* 본문 — [좌측 타입 필터] · [그래프 캔버스 + 인스펙터]. */}
      <div className="flex min-h-0 flex-1">
        {/* 좌측 패널 — 편집 모드(showEditor)에서는 타입 필터 대신 아웃라인(타입/관계 목록)을 보여준다.
            아웃라인은 selectedSchema만 다룬다(편집 대상이 항상 선택된 온톨로지이므로 bare schema 분기가 없다).
            읽기 모드는 기존 그대로: resolution 그룹핑 + 개수 + 토글 필터(접기 가능).
            스키마 탭에서는 캔버스(selectedSchema)와 같은 온톨로지의 타입을 보여줘야 한다 — 그렇지 않으면
            선택된 온톨로지가 기본 온톨로지가 아닐 때 캔버스와 필터 패널이 서로 다른 타입 어휘를 나란히
            보여주게 된다. 인스턴스 탭은 여전히 bare schema(기본 온톨로지) — Neo4j 적재 그래프가 그
            기반이라 건드리지 않는다. */}
        {showEditor && selectedSchema ? (
          <ModelOutline
            schema={selectedSchema}
            selected={modelSelected}
            onSelect={selectModelElement}
            mutations={elementMutations}
            onAddRelation={() => {
              setModelSelected(null);
              setCreatingEntity(false);
              setCreatingRelation(true);
            }}
            onAddEntityType={() => {
              setModelSelected(null);
              setCreatingRelation(false);
              setCreatingEntity(true);
            }}
            addEntityTypeButtonRef={addEntityTypeButtonRef}
          />
        ) : (
          <TypeFilterPanel
            schema={tab === 'schema' ? selectedSchema : schema}
            graph={graph}
            activeTypes={activeTypes}
            onToggle={toggleType}
            onReset={() => setActiveTypes(new Set())}
            collapsed={filterCollapsed}
            // (#413) 이름 검색 배지 반영 — 검색 UI가 인스턴스 탭에만 있으므로 그 탭에서만 전달한다.
            // 스키마 탭은 검색어 입력 자체가 불가능하니 항상 빈 문자열(=전체 개수)로 둔다.
            search={tab === 'instance' ? search : ''}
          />
        )}

        {/* 그래프 캔버스 + 인스펙터(도킹) 영역 — 드로어 도킹 검증용 testid 유지. */}
        <div className="flex min-w-0 flex-1 overflow-hidden" data-testid="instance-graph-panel">
          {/* min-w-[280px]: flex 기본 min-width:auto 때문에 그래프가 안 줄어들어 인스펙터가 밖으로
              잘리는 것을 막으면서(원래 min-w-0의 목적), 동시에 캔버스 자체가 280px 밑으로는 줄어들지
              않게 하한을 둔다(#403 방어 가드) — ModelOutline/인스펙터는 xl 미만에서 숨고, 읽기 모드의
              TypeFilterPanel도 이제(#414) sm 미만에서 함께 숨는다. 이 하한은 그 두 브레이크포인트
              사이에서 패널이 펼침 상태로 남아 있는 폭 대역(sm~xl)에 대한 추가 방어선이다. */}
          <div className="relative min-w-[280px] flex-1">
            <TabsContent value="schema" className="m-0 h-full">
              {isSelectedSchemaError ? (
                <GraphError message="온톨로지를 불러오지 못했습니다." onRetry={() => refetchSelectedSchema()} />
              ) : !selectedSchema || !selectedOntology ? (
                <GraphLoading />
              ) : (
                <div className="flex h-full flex-col p-3">
                  {/* 비-active 상태 안내 — active면 null을 반환해 아무것도 차지하지 않는다. */}
                  <OntologyStatusBanner ontology={selectedOntology} />
                  <div className="min-h-0 flex-1">
                    {selectedSchema.entities.length === 0 ? (
                      <OntologyEmptyState
                        // 전체 문서 모달이 사라졌으므로(Task 6) CTA("첫 타입 만들기")는 수정 모드를
                        // 켠다. S3 Task 4부터는 생성 폼(creatingEntity)까지 함께 열어 CTA 한 번으로
                        // 첫 타입을 만드는 곳까지 도달한다 — 모드만 켜면 사용자가 아웃라인의 "타입
                        // 추가"를 다시 찾아야 했다(OntologyEmptyState.tsx 주석 참고). 다만 인스펙터
                        // pane 자체가 `hidden sm:block`이라(리뷰 M-4) sm 미만 폭에서는 생성 폼이 열려도
                        // 화면에 보이지 않는다 — "수정 모드만 켜진" 것처럼 보인다. 이전 동작(모드만
                        // 켜짐)보다 나빠지진 않지만 CTA 문구가 약속한 결과와는 어긋난다는 점을
                        // 기록해 둔다.
                        onDefine={
                          canEdit
                            ? () => {
                                setModelEditMode(true);
                                setCreatingEntity(true);
                              }
                            : undefined
                        }
                      />
                    ) : (
                      <SchemaGraph
                        schema={selectedSchema}
                        onTypeClick={drillDown}
                        // 타입 필터 패널(#411) — 인스턴스 탭(InstanceGraph)에만 전달되던 activeTypes를
                        // 스키마 탭에도 흘려보낸다. 칩을 꺼서 필터를 걸면 스키마 캔버스에서도 해당
                        // 타입 노드/트리플이 실제로 숨어야 한다(그전엔 칩 상태만 바뀌고 캔버스는 무반응).
                        activeTypes={activeTypes}
                        editing={showEditor}
                        selected={modelSelected}
                        onSelectEntity={(id) => selectModelElement({ kind: 'entity', id })}
                        onSelectRelation={(id) => selectModelElement({ kind: 'relation', id })}
                        // 캔버스 핸들 드래그 관계 생성(S3 Task 2) — SchemaGraph는 read 모드에도 쓰이는
                        // 유일한 캔버스라 API 계층에 직접 묶지 않는다(팀 확인, task-2-brief 설계 노트
                        // 논의). 제스처 감지·인라인 입력·로컬 검증은 SchemaGraph가 맡고, 실제 저장은
                        // 여기서 mutations.addRelation을 호출해 처리한다 — RelationInspector의
                        // CreateRelationForm과 정확히 같은 API 호출·응답 처리(성공 시 id로 선택 전환).
                        // description은 빈 문자열로 시작한다(캔버스 드래그는 이름만 빠르게 정하는
                        // 경로라 브리프가 그렇게 설계했다) — 필요하면 선택된 인스펙터에서 이어 쓴다.
                        onConnect={async (subjectTypeId, objectTypeId, relationName) => {
                          const result = await elementMutations.addRelation({
                            subjectTypeId,
                            relation: relationName,
                            objectTypeId,
                            description: '',
                          });
                          if (result?.relation.id == null) return false;
                          // selectModelElement가 creatingRelation/creatingEntity/기존 modelSelected를
                          // 함께 정리해 준다 — 아웃라인·캔버스 클릭 선택과 동일한 사후 정리.
                          selectModelElement({ kind: 'relation', id: result.relation.id });
                          return true;
                        }}
                        // 캔버스 인라인 리네임(S3 Task 3) — EntityInspector의 이름 필드(useAutosaveText)와
                        // 정확히 같은 API 호출을 쓴다. 값이 안 바뀐 경우·중복 이름은 SchemaGraph가 이미
                        // 걸러(validateEntityTypeName) 여기 도달하지 않으므로, 이 콜백은 호출·응답 처리만
                        // 담당한다. 선택 상태는 건드리지 않는다 — 리네임은 이미 선택돼 있던(또는 아무것도
                        // 선택 안 된) 노드의 이름만 바꾸는 조작이라, 관계 생성·타입 생성과 달리 "결과를
                        // 이어서 편집하러 인스펙터를 열어야 한다"는 요구가 없다.
                        onRenameEntity={async (entityTypeId, nextName) => {
                          const result = await elementMutations.updateEntityType(entityTypeId, { type: nextName });
                          return result != null;
                        }}
                        // 빈 캔버스 더블클릭 생성(S3 Task 3) — description/naming 빈 문자열 + resolution
                        // 'embedding' 기본값은 CreateEntityTypeForm(EntityInspector.tsx)과 동일해야 한다는
                        // 브리프 설계 노트를 그대로 따른다(두 생성 경로가 다른 기본값을 쓰면 안 된다).
                        onCreateEntityAt={async (name) => {
                          const result = await elementMutations.addEntityType({
                            type: name,
                            description: '',
                            naming: '',
                            resolution: 'embedding',
                          });
                          if (result?.entityType.id == null) return false;
                          selectModelElement({ kind: 'entity', id: result.entityType.id });
                          return true;
                        }}
                        // 캔버스 Delete 키 삭제(S3 Task 4) — 확인 여부가 다르고(타입=확인 필수,
                        // 관계=즉시) 그 확인 다이얼로그도 API 계층 안에 있어 SchemaGraph는 요청만
                        // 올려보낸다. 실제 처리는 위 requestDeleteEntity/requestDeleteRelation(이
                        // 함수 스코프)이 맡는다.
                        onRequestDeleteEntity={requestDeleteEntity}
                        onRequestDeleteRelation={requestDeleteRelation}
                      />
                    )}
                  </div>
                </div>
              )}
            </TabsContent>
            <TabsContent value="instance" className="m-0 h-full">
              {isError ? (
                <GraphError message="그래프를 불러오지 못했습니다." onRetry={() => refetchGraph()} />
              ) : isGraphLoading || !graph ? (
                <GraphLoading />
              ) : (
                <InstanceGraph
                  graph={graph}
                  activeTypes={activeTypes}
                  search={search}
                  onNodeSelect={setSelected}
                  focusKey={focusKey}
                  grouped={grouped}
                />
              )}
            </TabsContent>
          </div>
          {/* 인스펙터는 인스턴스 탭에서만 도킹(스키마 탭은 노드 선택 개념 없음). */}
          {tab === 'instance' && (
            <NodeDetailDrawer
              node={selected}
              edges={graph?.edges ?? []}
              nodesByKey={nodesByKey}
              onClose={() => setSelected(null)}
              onNavigate={navigateTo}
              currentSchemaVersion={schema?.schemaVersion}
            />
          )}
          {/* 편집 모드 세 번째 pane — 타입/관계 인스펙터. 타입 선택은 Task 4의 EntityInspector, 관계
              선택·생성은 Task 5의 RelationInspector가 채운다.
              (리뷰 IMP-4, #403로 xl 완화) sm(640px) 미만뿐 아니라 태블릿·좁은 데스크톱 폭(sm~lg 대역,
              예 834px)까지 숨긴다 — w-64+w-80 고정폭 2개(576px)가 sm 기준에서는 640~1024px 사이 폭에서
              캔버스를 200px 안팎으로 짓눌러 스키마 그래프를 사실상 못 읽게 만들었다(#403). AppLayout
              사이드바(펼침 시 240px)까지 겹치면 1024px에서도 여전히 짓눌리므로(실측) xl(1280px) 이상에서만
              3-pane을 동시에 보여주고, 그 미만에서는 ModelOutline과 함께 숨겨 캔버스 전체 폭을 확보한다. */}
          {showEditor && (
            <div className="hidden w-80 shrink-0 overflow-y-auto border-l p-4 xl:block" data-testid="model-inspector">
              {selectedEntity && selectedSchema ? (
                <EntityInspector
                  key={selectedEntity.id}
                  schema={selectedSchema}
                  entity={selectedEntity}
                  mutations={elementMutations}
                  onDeleted={() => setModelSelected(null)}
                  restoreFocusRef={addEntityTypeButtonRef}
                  status={selectedOntology?.status}
                />
              ) : selectedRelation && selectedSchema ? (
                <RelationInspector
                  key={selectedRelation.id}
                  schema={selectedSchema}
                  relation={selectedRelation}
                  mutations={elementMutations}
                  onDeleted={() => setModelSelected(null)}
                  restoreFocusRef={addEntityTypeButtonRef}
                />
              ) : creatingRelation && selectedSchema ? (
                <RelationInspector
                  schema={selectedSchema}
                  relation={null}
                  mutations={elementMutations}
                  onCreated={(id) => {
                    setCreatingRelation(false);
                    setModelSelected({ kind: 'relation', id });
                  }}
                  onCancel={() => setCreatingRelation(false)}
                />
              ) : creatingEntity && selectedSchema ? (
                <EntityInspector
                  schema={selectedSchema}
                  entity={null}
                  mutations={elementMutations}
                  onCreated={(id) => {
                    setCreatingEntity(false);
                    setModelSelected({ kind: 'entity', id });
                  }}
                  onCancel={() => setCreatingEntity(false)}
                />
              ) : modelSelected ? (
                // 선택된 id가 더 이상 스키마에 없는 경우(동시 편집으로 삭제됨 등) — 어느 종류였는지
                // 구분할 수 없으므로 종류 무관 안내 문구로 대체한다.
                <p className="text-sm text-muted-foreground">선택한 요소를 찾을 수 없습니다.</p>
              ) : (
                <p className="text-sm text-muted-foreground">왼쪽에서 타입 또는 관계를 선택하세요.</p>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 캔버스 Delete 키로 요청된 엔티티 타입 삭제 확인(S3 Task 4) — EntityInspector의 트리거 기반
          인스턴스와 별개인 controlled 인스턴스다(위 canvasDeleteEntity 주석 참고). trigger를 넘기지
          않으므로 AlertDialog가 open/onOpenChange로만 제어된다(DeleteTypeConfirm.tsx 참고) — Cancel/
          Esc/바깥 클릭도 Radix가 onOpenChange(false)로 알려주므로 여기서 상태만 비우면 된다. */}
      {canvasDeleteEntity && (
        <DeleteTypeConfirm
          entity={canvasDeleteEntity}
          affectedRelations={canvasDeleteAffectedRelations}
          open
          onOpenChange={(o) => {
            if (!o) setCanvasDeleteEntityId(null);
          }}
          restoreFocusRef={addEntityTypeButtonRef}
          onConfirm={() => {
            // entity.id가 아니라 canvasDeleteEntityId(상태로 들고 있던 number)를 쓴다 — EntityTypeDef.id는
            // 레거시 폴백(엔티티에 id가 없는 스키마)을 감안해 타입상 optional이지만, 이 다이얼로그는
            // requestDeleteEntity가 이미 id 있는 엔티티만 세운 상태라 실질적으로 항상 존재한다.
            const entityTypeId = canvasDeleteEntityId;
            if (entityTypeId == null) return;
            // in-flight 중복 방지(리뷰 I-2) — AlertDialogAction은 클릭 즉시 다이얼로그를 닫으므로
            // (canvasDeleteEntityId는 응답이 올 때까지 그대로 남지만) DELETE 응답을 기다리는 동안
            // requestDeleteEntity가 다시 불려도 다이얼로그를 또 열지 않는다(위 requestDeleteEntity의
            // 같은 키 검사). 여기서는 그 사이 이 onConfirm 자체가 다시 호출되는 경로(예: 같은
            // AlertDialogAction을 빠르게 두 번 클릭)까지 막는다.
            const key = `entity:${entityTypeId}`;
            if (deletingElementKeysRef.current.has(key)) return;
            deletingElementKeysRef.current.add(key);
            void elementMutations.deleteEntityType(entityTypeId).then((result) => {
              if (!result) return;
              // 포커스를 먼저 옮긴 뒤 나머지 상태를 정리한다 — EntityInspector의 entity-delete-trigger와
              // 같은 순서(#328류 회피, DeleteTypeConfirm.tsx restoreFocusRef 주석 참고).
              addEntityTypeButtonRef.current?.focus();
              setCanvasDeleteEntityId(null);
              if (modelSelected?.kind === 'entity' && modelSelected.id === entityTypeId) setModelSelected(null);
            }).finally(() => {
              deletingElementKeysRef.current.delete(key);
            });
          }}
        />
      )}

      {/* 생성 성공 시 새 온톨로지를 곧바로 선택 상태로 만든다 — 사용자가 다시 찾아 고르지 않아도 되게. */}
      <OntologyCreateDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(id) => setSelectedOntologyId(id)}
      />

      {/* 관리 다이얼로그 — 행 클릭 시 해당 온톨로지를 선택 상태로 만들고 닫는다. */}
      <OntologyManageDialog
        open={manageOpen}
        onOpenChange={setManageOpen}
        onSelect={(id) => setSelectedOntologyId(id)}
      />
    </Tabs>
  );
}
