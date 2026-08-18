import { Navigate, Outlet } from 'react-router-dom';

import { useAuth } from '../hooks/useAuth';
import { SelectTenantPage } from '../pages/SelectTenantPage';

export function ProtectedRoute() {
  const { isLoading, isAuthenticated, activeTenantId } = useAuth();

  if (isLoading) {
    return (
      <div className="flex min-h-dvh items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  // 세 번째 상태: 인증됐지만 테넌트 미선택. 여기서 막지 않으면 GUC 가 비어 있어 모든 API 가 403 이
  // 되고, 사용자는 원인 표시 없는 빈 화면을 본다. `LoginPage` 의 `<Navigate to="/">` 가 이 상태를
  // 그대로 `/` 로 밀어넣기 때문에, 모든 내부 라우트가 지나는 이 초크포인트가 유일한 방어 지점이다.
  //
  // 리다이렉트가 아니라 **직접 렌더링**한다 — 선택 화면을 라우트로 만들면 URL 로 우회할 수 있고
  // "선택되지 않은 채 앱 안에 있는" 순간이 생긴다.
  if (activeTenantId === null) {
    return <SelectTenantPage />;
  }

  return <Outlet />;
}
