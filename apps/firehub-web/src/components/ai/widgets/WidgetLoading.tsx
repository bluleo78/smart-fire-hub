import { Loader2 } from 'lucide-react';

import { Skeleton } from '../../ui/skeleton';

/**
 * WidgetShell 본문의 로딩 인디케이터 — 06-feedback-states.md §A.4 "Dashboard Widget Spinner".
 *
 * 무엇: 카드 내부 중앙에 Loader2 스피너 하나만 그린다.
 * 왜:   기존 "로딩 중..." 정적 텍스트는 모션 신호가 없어 "처리 중"과 "멈춤"이 구분되지 않았다(#434).
 *       문서 처방은 스피너 단독이라 눈에 보이는 텍스트는 두지 않고, 대신 role="status" +
 *       sr-only 라벨로 스크린리더 안내를 유지한다(텍스트를 없앤 대가를 접근성으로 치르지 않도록).
 *
 * 쓰는 곳: 위젯 본문 전체가 로딩 중이면서 최종 본문의 행 모양을 알 수 없는 경우
 *          (예: 통계·상태 블록). 행 목록처럼 최종 모양을 아는 경우엔 WidgetRowsSkeleton 을 쓴다.
 */
export function WidgetLoading({ label = '불러오는 중' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center py-6" role="status">
      <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      <span className="sr-only">{label}</span>
    </div>
  );
}

/**
 * WidgetShell 본문이 "행 목록"인 위젯의 스켈레톤.
 *
 * 무엇: 06-feedback-states.md §Skeleton 크기 규칙의 "테이블 행 = h-10 w-full" 을 rows 개만큼 쌓는다.
 * 왜:   최종 렌더 결과의 행 수·높이를 미리 아는 위젯에서는 스피너보다 스켈레톤이 레이아웃 시프트를
 *       줄인다(#434). WidgetSkeleton(위젯이 아직 mount 되기 전 Suspense fallback)과 달리
 *       자체 카드 테두리를 그리지 않는다 — 이미 WidgetShell 테두리 안이라 이중 테두리가 되면 안 된다.
 */
export function WidgetRowsSkeleton({ rows, label = '불러오는 중' }: { rows: number; label?: string }) {
  return (
    <div className="space-y-2 p-3" role="status">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-10 w-full" />
      ))}
      <span className="sr-only">{label}</span>
    </div>
  );
}
