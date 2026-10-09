import { Lock } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * 차트 설정 가림 안내 — 조회자가 저장 쿼리의 데이터를 볼 수 없어 서버가 config 를 주지 않은 차트(WD-31②).
 * 대시보드 위젯 "열람 권한 없음"(DashboardWidgetCard)과 같은 muted 자물쇠 표현을 쓴다. 원본 이름은 노출하지 않는다.
 */
export function ChartConfigWithheldNotice({ className }: { className?: string }) {
  return (
    <div
      role="status"
      data-testid="chart-config-withheld"
      className={cn('flex flex-col items-center justify-center gap-1 text-center text-muted-foreground', className)}
    >
      <Lock className="h-5 w-5" aria-hidden="true" />
      <p className="text-sm">이 차트의 설정은 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다</p>
    </div>
  );
}
