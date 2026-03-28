import { FileText } from 'lucide-react';

import ReportTemplatesTab from '../admin/ReportTemplatesTab';

export default function AiInsightTemplatesPage() {
  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <FileText className="h-6 w-6" />
        <div>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">리포트 양식</h1>
          <p className="text-sm text-muted-foreground mt-0.5">AI 리포트의 출력 구조를 정의합니다</p>
        </div>
      </div>
      <ReportTemplatesTab />
    </div>
  );
}
