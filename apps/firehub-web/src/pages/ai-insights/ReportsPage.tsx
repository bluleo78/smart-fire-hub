import { FileText } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

import ReportListTab from './tabs/ReportListTab';
import ReportTemplatesTab from './tabs/ReportTemplatesTab';

/**
 * 리포트 화면 — 생성된 리포트 목록과 리포트 양식을 한 메뉴에서 관리한다.
 *
 * 탭 상태는 ProactiveJobDetailPage 와 동일하게 URL 쿼리(?tab=)로 유지해 새로고침·공유에도 보존한다.
 */
export default function ReportsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get('tab') ?? 'list';

  const handleTabChange = (value: string) => {
    setSearchParams({ tab: value }, { replace: true });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <FileText className="h-6 w-6" />
        <div>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">리포트</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            AI가 생성한 리포트를 열람하고 출력 양식을 관리합니다
          </p>
        </div>
      </div>

      <Tabs value={activeTab} onValueChange={handleTabChange}>
        <TabsList>
          <TabsTrigger value="list">생성된 리포트</TabsTrigger>
          <TabsTrigger value="templates">리포트 양식</TabsTrigger>
        </TabsList>

        <TabsContent value="list">
          <ReportListTab />
        </TabsContent>

        <TabsContent value="templates">
          <ReportTemplatesTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
