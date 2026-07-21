import { AlertCircle } from 'lucide-react';
import { useMemo, useState } from 'react';

import { Button } from '@/components/ui/button';
import { SearchInput } from '@/components/ui/search-input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useOntologyGraph, useOntologySchema } from '@/hooks/queries/useOntology';
import type { GraphNode } from '@/types/ontology';

import InstanceGraph from './components/InstanceGraph';
import NodeDetailDrawer from './components/NodeDetailDrawer';
import SchemaGraph from './components/SchemaGraph';
import TypeLegend from './components/TypeLegend';

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

// 온톨로지 시각화 관리 페이지 — 스키마/인스턴스 탭 + 공유 색상 범례(읽기 전용).
export default function OntologyPage() {
  const { data: schema, isLoading: isSchemaLoading, isError: isSchemaError, refetch: refetchSchema } = useOntologySchema();
  const { data: graph, isLoading: isGraphLoading, isError, refetch: refetchGraph } = useOntologyGraph();
  const [tab, setTab] = useState('schema');
  const [activeTypes, setActiveTypes] = useState<Set<string>>(new Set()); // 빈 Set = 전체
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<GraphNode | null>(null);

  const nodesByKey = useMemo(() => new Map((graph?.nodes ?? []).map((n) => [n.key, n])), [graph]);

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
    <div className="space-y-6">
      <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">온톨로지</h1>
      <TypeLegend schema={schema} graph={graph} activeTypes={activeTypes} onToggle={toggleType} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="schema">스키마</TabsTrigger>
          <TabsTrigger value="instance">인스턴스 그래프</TabsTrigger>
        </TabsList>
        <TabsContent value="schema" className="mt-6">
          <div className="h-[600px] rounded-lg border">
            {isSchemaError ? (
              <GraphError message="온톨로지를 불러오지 못했습니다." onRetry={() => refetchSchema()} />
            ) : isSchemaLoading || !schema ? (
              <GraphLoading />
            ) : (
              <SchemaGraph schema={schema} onTypeClick={drillDown} />
            )}
          </div>
        </TabsContent>
        <TabsContent value="instance" className="mt-6">
          <div className="flex gap-3">
            <SearchInput placeholder="이름 검색" value={search} onChange={setSearch} className="max-w-xs" />
          </div>
          <div className="mt-3 flex h-[600px] rounded-lg border overflow-hidden">
            <div className="flex-1">
              {isError ? (
                <GraphError message="그래프를 불러오지 못했습니다." onRetry={() => refetchGraph()} />
              ) : isGraphLoading || !graph ? (
                <GraphLoading />
              ) : (
                <InstanceGraph graph={graph} activeTypes={activeTypes} search={search} onNodeSelect={setSelected} />
              )}
            </div>
            <NodeDetailDrawer
              node={selected}
              edges={graph?.edges ?? []}
              nodesByKey={nodesByKey}
              onClose={() => setSelected(null)}
            />
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
