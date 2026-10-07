import { Navigate, Outlet } from 'react-router-dom';

import { useMyPermissions } from '../hooks/queries/useMyPermissions';
import { useAuth } from '../hooks/useAuth';

/**
 * ADMIN 이거나 지정 권한 보유자만 통과(스펙 §5-1 — security:settings 보유자가 /admin/settings 에 들어올 수 있어야 한다).
 * 권한 조회가 끝나기 전에는 판단하지 않는다(로딩 중 리다이렉트로 깜빡이는 것 방지).
 */
export function AdminOrPermissionRoute({ permission }: { permission: string }) {
  const { isAdmin, isLoading } = useAuth();
  const { permissions, isLoading: permLoading } = useMyPermissions();
  if (isLoading || (!isAdmin && permLoading)) {
    return (
      <div className="flex min-h-dvh items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    );
  }
  if (!isAdmin && !permissions.has(permission)) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}
