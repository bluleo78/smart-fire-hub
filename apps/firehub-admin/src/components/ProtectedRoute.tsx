import { Navigate, Outlet } from 'react-router-dom';

import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuth } from '@/hooks/useAuth';

interface ProtectedRouteProps {
  /**
   * 이 하위 라우트를 보려면 필요한 권한. 없으면(undefined) 인증 여부만 본다 —
   * `App.tsx` 최상위 게이트가 이 모드로 쓰인다.
   *
   * "이 화면을 볼 자격"이라는 라우트 수준 사실을 여기 한 곳에 모은다 — 전에는 페이지
   * 본문 선검사(예: 옛 `TenantCreatePage`)·쿼리 403 본문 교체·뮤테이션 403 토스트
   * 네 곳에 흩어져 있었다. 쿼리 403 분기는 지우지 않는다 — 라우트 게이트를 통과한 뒤
   * 권한이 회수되는 경합(게이트 이후 서버가 그래도 403 을 주는 상황)을 잡는 **심층방어**로
   * 남는다.
   */
  requiredPermission?: string;
  /** 권한 없음 화면에 얹을 제목. 없으면 배너만 그린다. */
  deniedTitle?: string;
}

/**
 * 인증/권한 게이트. 인증 전이면 `/login` 으로 보낸다. 부팅 중(refresh 진행)에는 스켈레톤을
 * 그린다. `requiredPermission` 이 주어지고 그 권한이 없으면 라우트 자체를 열지 않고
 * 권한 배너로 대체한다 — 본문이 잠깐이라도 그려졌다 사라지는 깜빡임이 없다.
 */
export function ProtectedRoute({ requiredPermission, deniedTitle }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, hasPermission } = useAuth();

  if (isLoading) {
    return (
      <div className="mx-auto max-w-5xl space-y-6 p-6 pt-10">
        <Skeleton className="h-9 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }
  if (!isAuthenticated) return <Navigate to="/login" replace />;

  if (requiredPermission && !hasPermission(requiredPermission)) {
    return (
      <div className="space-y-6">
        {deniedTitle && (
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">{deniedTitle}</h1>
        )}
        <PermissionDeniedBanner />
      </div>
    );
  }

  return <Outlet />;
}
