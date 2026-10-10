import { Lock } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * 차트 설정 가림 안내 — 조회자가 저장 쿼리의 데이터를 볼 수 없어 서버가 config 를 주지 않은 차트(WD-31②).
 * 대시보드 위젯 "열람 권한 없음"(DashboardWidgetCard)과 같은 muted 자물쇠 + 제목·설명 구조를 쓴다. 원본 이름은 노출하지 않는다.
 * 정식 안내는 한 곳(빌더 좌측 패널)에만 둔다 — 정적 안내라 role="status" 를 붙이지 않는다(디자이너 리뷰: 중복 낭독 방지).
 * 설명 문장은 스펙 바이트 고정이라 마침표를 붙이지 않는다.
 */
export function ChartConfigWithheldNotice({ className }: { className?: string }) {
  return (
    <div
      data-testid="chart-config-withheld"
      className={cn('flex flex-col items-center justify-center gap-1 text-center text-muted-foreground', className)}
    >
      <Lock className="h-5 w-5" aria-hidden="true" />
      <p className="text-sm font-medium">설정 잠김</p>
      <p className="text-xs">이 차트의 설정은 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다</p>
    </div>
  );
}
