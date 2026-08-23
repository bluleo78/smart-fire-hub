import { Navigate, Outlet } from 'react-router-dom';

import { Skeleton } from '@/components/ui/skeleton';
import { useAuth } from '@/hooks/useAuth';

/** 인증 전이면 `/login` 으로 보낸다. 부팅 중(refresh 진행)에는 스켈레톤을 그린다. */
export function ProtectedRoute() {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="mx-auto max-w-5xl space-y-6 p-6 pt-10">
        <Skeleton className="h-9 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <Outlet />;
}
