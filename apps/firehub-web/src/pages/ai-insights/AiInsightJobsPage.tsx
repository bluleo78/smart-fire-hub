import { Zap } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useCanAdminProactive } from '@/hooks/queries/useProactiveMessages';

import { WorkspaceJobsTable } from './components/WorkspaceJobsTable';
import ProactiveJobListPage from './ProactiveJobListPage';

/** 「워크스페이스 전체」 보기의 주소 값 — 새로고침·링크 공유 시에도 같은 보기로 열리게 URL 에 둔다. */
const WORKSPACE_VIEW = 'workspace';

export default function AiInsightJobsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  // 관리자(proactive:admin)에게만 「워크스페이스 전체」 보기를 둔다 (WD-192).
  // 작성자가 정지·제거되거나 권한을 잃어 아무도 정리할 수 없던 작업을 관리자가 끄거나 지울 수 있게 한다.
  const { canAdmin } = useCanAdminProactive();
  const view = canAdmin && searchParams.get('view') === WORKSPACE_VIEW ? WORKSPACE_VIEW : 'mine';

  const handleViewChange = (next: string) => {
    setSearchParams(next === WORKSPACE_VIEW ? { view: WORKSPACE_VIEW } : {});
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Zap className="h-6 w-6" />
        <div>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">스마트 작업</h1>
          <p className="text-sm text-muted-foreground mt-0.5">AI가 자동으로 실행하는 분석/요약 작업을 관리합니다</p>
        </div>
      </div>
      {canAdmin ? (
        <Tabs value={view} onValueChange={handleViewChange}>
          <TabsList>
            <TabsTrigger value="mine">내 작업</TabsTrigger>
            <TabsTrigger value={WORKSPACE_VIEW}>워크스페이스 전체</TabsTrigger>
          </TabsList>
          <TabsContent value="mine">
            <ProactiveJobListPage />
          </TabsContent>
          <TabsContent value={WORKSPACE_VIEW}>
            <WorkspaceJobsTable />
          </TabsContent>
        </Tabs>
      ) : (
        <ProactiveJobListPage />
      )}
    </div>
  );
}
