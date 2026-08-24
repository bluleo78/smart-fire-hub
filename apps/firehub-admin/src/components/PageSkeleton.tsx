import { Skeleton } from '@/components/ui/skeleton';

/**
 * lazy 페이지 로딩 중 폴백. `App.tsx`(로그인 화면)와 `AdminShell.tsx`(셸 내부 페이지들)가
 * 각자의 `<Suspense>` 경계에서 공유한다 — 두 경계가 서로 다른 트리 위치(셸 밖/셸 안)에
 * 있어야 하므로(셸 안쪽 경계가 없으면 페이지 전환마다 헤더·nav·계정 메뉴까지 통째로
 * 스켈레톤으로 바뀐다) `App.tsx` 안의 지역 함수로 두면 재사용할 수 없다.
 */
export function PageSkeleton() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-9 w-64" />
      <Skeleton className="h-96 w-full" />
    </div>
  );
}
