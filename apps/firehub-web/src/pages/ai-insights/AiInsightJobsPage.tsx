import { Zap } from 'lucide-react';

import ProactiveJobListPage from './ProactiveJobListPage';

export default function AiInsightJobsPage() {
  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Zap className="h-6 w-6" />
        <div>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">스마트 작업</h1>
          <p className="text-sm text-muted-foreground mt-0.5">AI가 자동으로 실행하는 분석/요약 작업을 관리합니다</p>
        </div>
      </div>
      <ProactiveJobListPage />
    </div>
  );
}
