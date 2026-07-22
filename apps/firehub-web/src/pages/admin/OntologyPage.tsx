import { AlertCircle, Boxes, PanelLeft, PanelLeftClose } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { SearchInput } from '@/components/ui/search-input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useOntologyGraph, useOntologySchema } from '@/hooks/queries/useOntology';
import type { GraphNode } from '@/types/ontology';

import InstanceGraph from './components/InstanceGraph';
import NodeDetailDrawer from './components/NodeDetailDrawer';
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
  const { data: schema, isLoading: isSchemaLoading, isError: isSchemaError, refetch: refetchSchema } = useOntologySchema();
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
      <div className="flex h-12 shrink-0 items-center gap-3 border-b px-3">
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
        <div className="ml-auto flex items-center gap-2">
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
        {/* 좌측 타입 필터 패널 — resolution 그룹핑 + 개수 + 토글 필터(접기 가능). */}
        <TypeFilterPanel
          schema={schema}
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
              {isSchemaError ? (
                <GraphError message="온톨로지를 불러오지 못했습니다." onRetry={() => refetchSchema()} />
              ) : isSchemaLoading || !schema ? (
                <GraphLoading />
              ) : (
                <SchemaGraph schema={schema} onTypeClick={drillDown} />
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
            />
          )}
        </div>
      </div>
    </Tabs>
  );
}
