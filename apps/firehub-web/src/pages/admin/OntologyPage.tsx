import { AlertCircle, Boxes, PanelLeft, PanelLeftClose, Pencil, Plus } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { SearchInput } from '@/components/ui/search-input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useOntologyById, useOntologyGraph, useOntologyList, useOntologySchema } from '@/hooks/queries/useOntology';
import { useAuth } from '@/hooks/useAuth';
import type { GraphNode } from '@/types/ontology';

import InstanceGraph from './components/InstanceGraph';
import NodeDetailDrawer from './components/NodeDetailDrawer';
import OntologyCreateDialog from './components/OntologyCreateDialog';
import OntologyEditDialog from './components/OntologyEditDialog';
import OntologyEmptyState from './components/OntologyEmptyState';
import OntologyManageDialog from './components/OntologyManageDialog';
import OntologySelect from './components/OntologySelect';
import OntologyStatusBanner from './components/OntologyStatusBanner';
import SchemaGraph from './components/SchemaGraph';
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

// 온톨로지 시각화 페이지 — 풀하이트 에디터 셸(툴바 + 좌측 타입 필터 + 캔버스 + 리사이즈 인스펙터), 읽기 전용.
export default function OntologyPage() {
  const { data: schema } = useOntologySchema();
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
  const [editOpen, setEditOpen] = useState(false); // 지식 모델 편집 다이얼로그(ADMIN 전용, ontology:write)
  // 편집을 열 때마다 증가시켜 다이얼로그를 리마운트한다. 닫힐 때는 바뀌지 않아야 한다 —
  // 닫는 순간 리마운트되면 포커스 복귀 이펙트가 끊겨 포커스가 <body>로 유실된다(#328).
  const [editSession, setEditSession] = useState(0);
  const { isAdmin } = useAuth();

  // 스키마 탭에서 보고 있는 온톨로지. 인스턴스 탭(Neo4j 적재 그래프)은 여전히 id=1 기반이므로
  // 이 선택은 스키마 탭에만 영향을 준다 — 여기까지 번지면 타입 필터가 조용히 어긋난다.
  const [selectedOntologyId, setSelectedOntologyId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [manageOpen, setManageOpen] = useState(false);
  const { data: ontologies } = useOntologyList('all');
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
  // archived는 서버가 409로 거부하므로 애초에 진입점을 보여주지 않는다.
  const canEdit = isAdmin && selectedOntology?.status !== 'archived';

  const nodesByKey = useMemo(() => new Map((graph?.nodes ?? []).map((n) => [n.key, n])), [graph]);

  // 인스펙터 관계 클릭 → 대상 노드를 인스펙터에 로드하고 캔버스에서 포커싱한다.
  const navigateTo = (key: string) => {
    const target = nodesByKey.get(key);
    if (!target) return;
    setSelected(target);
    setFocusKey(key);
  };

  // 범례 클릭 → 타입 필터 토글.
  const toggleType = (t: string) =>
    setActiveTypes((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });

  // 스키마 탭에서 타입 클릭 → 인스턴스 탭으로 이동하며 해당 타입만 필터(드릴다운 브리지).
  const drillDown = (type: string) => {
    setActiveTypes(new Set([type]));
    setTab('instance');
  };

  return (
    // 풀하이트 에디터 셸(디자인 시스템 Pattern D) — 고정 h-[600px] 대신 뷰포트를 채워 페이지 스크롤 클립을 없앤다.
    // Radix Tabs로 감싸 탭 a11y(role=tab)와 기존 E2E 셀렉터를 보존한다. 높이 체인 전 구간 min-h-0.
    <Tabs value={tab} onValueChange={setTab} className="flex h-full min-h-0 flex-col">
      {/* 툴바 — 좌: 패널 토글 + 제목 + 탭 전환, 우: 검색(인스턴스 탭 한정). */}
      {/* 좁은 폭(max-sm)에서는 툴바를 줄바꿈하고 높이를 풀어 가로 스크롤을 없앤다(#345 SC 1.4.10).
          sm 이상에서는 기존 한 줄 h-12 툴바 그대로. */}
      <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b px-3 py-1.5 sm:h-12 sm:flex-nowrap sm:py-0">
        <Button
          variant="ghost"
          size="icon"
          className="h-8 w-8"
          onClick={() => setFilterCollapsed((v) => !v)}
          aria-label={filterCollapsed ? '타입 필터 펼치기' : '타입 필터 접기'}
          aria-pressed={!filterCollapsed}
        >
          {filterCollapsed ? <PanelLeft className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
        </Button>
        <h1 className="text-sm font-semibold">지식그래프</h1>
        <TabsList>
          <TabsTrigger value="instance">그래프 탐색</TabsTrigger>
          <TabsTrigger value="schema">지식 모델</TabsTrigger>
        </TabsList>
        {/* 온톨로지 선택기(고정 220px)까지 들어오며 항목이 늘었다 — 이 그룹도 outer 툴바처럼
            max-sm에서 줄바꿈해야 320px 리플로우(#345)가 깨지지 않는다. */}
        <div className="ml-auto flex flex-wrap items-center justify-end gap-x-2 gap-y-1 sm:flex-nowrap">
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
          {/* 지식 모델 편집(ADMIN 전용) — 스키마 탭에서만 노출. 서버도 ontology:write(ADMIN)로 재검증한다.
              편집은 은퇴하지 않은 온톨로지에만 — archived는 서버가 409로 거부한다. */}
          {tab === 'schema' && canEdit && selectedSchema && (
            <Button
              variant="outline"
              size="sm"
              className="gap-1.5"
              // 편집 진입 시 최신 스키마를 먼저 확보한다(#301) — staleTime 5분 때문에 리마운트만으로는
              // 재조회가 일어나지 않아, 낡은 schemaVersion으로 편집을 시작하면 저장이 곧바로 409가 된다.
              // 재조회가 끝난 뒤 열어야 다이얼로그가 최신 원본으로 초기화된다(실패해도 캐시본으로 진행).
              // 편집 대상은 선택된 온톨로지(selectedSchema)이므로 재조회도 그 쿼리를 겨냥한다 —
              // 여기서 bare useOntologySchema(id=1)를 refetch하면 다른 온톨로지 편집 시 아무 효과가 없다.
              onClick={() =>
                void refetchSelectedSchema().finally(() => {
                  setEditSession((n) => n + 1);
                  setEditOpen(true);
                })
              }
            >
              <Pencil className="h-4 w-4" />
              편집
            </Button>
          )}
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
        {/* 좌측 타입 필터 패널 — resolution 그룹핑 + 개수 + 토글 필터(접기 가능).
            스키마 탭에서는 캔버스(selectedSchema)와 같은 온톨로지의 타입을 보여줘야 한다 — 그렇지 않으면
            선택된 온톨로지가 id=1이 아닐 때 캔버스와 필터 패널이 서로 다른 타입 어휘를 나란히 보여주게 된다.
            인스턴스 탭은 여전히 bare schema(id=1) — Neo4j 적재 그래프가 그 기반이라 건드리지 않는다. */}
        <TypeFilterPanel
          schema={tab === 'schema' ? selectedSchema : schema}
          graph={graph}
          activeTypes={activeTypes}
          onToggle={toggleType}
          onReset={() => setActiveTypes(new Set())}
          collapsed={filterCollapsed}
        />

        {/* 그래프 캔버스 + 인스펙터(도킹) 영역 — 드로어 도킹 검증용 testid 유지. */}
        <div className="flex min-w-0 flex-1 overflow-hidden" data-testid="instance-graph-panel">
          {/* min-w-0: flex 기본 min-width:auto 때문에 그래프가 안 줄어들어 인스펙터가 밖으로 잘리는 것을 막는다. */}
          <div className="relative min-w-0 flex-1">
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
                        onDefine={
                          canEdit
                            ? () => {
                                setEditSession((n) => n + 1);
                                setEditOpen(true);
                              }
                            : undefined
                        }
                      />
                    ) : (
                      <SchemaGraph schema={selectedSchema} onTypeClick={drillDown} />
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
        </div>
      </div>

      {/* key: 열릴 때만 증가하는 세션 번호로 리마운트해 폼 state를 최신 schema로 새로 시작한다
          (useEffect 동기화 대신). 닫힘 시에는 key가 그대로여야 포커스 복귀가 살아 있다(#328). */}
      {selectedSchema && effectiveOntologyId != null && (
        <OntologyEditDialog
          key={editSession}
          schema={selectedSchema}
          ontologyId={effectiveOntologyId}
          open={editOpen}
          onOpenChange={setEditOpen}
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
