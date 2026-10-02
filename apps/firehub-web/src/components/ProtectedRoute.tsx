import { Navigate, Outlet, useLocation } from 'react-router-dom';

import { useAuth } from '../hooks/useAuth';
import { SelectTenantPage } from '../pages/SelectTenantPage';

export function ProtectedRoute() {
  const { isLoading, isAuthenticated, activeTenantId, mustChangePassword } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div className="flex min-h-dvh items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    );
  }

  if (!isAuthenticated) {
    // 딥링크 보존(#675): 로그인 후 원래 접근하려던 경로로 되돌아갈 수 있도록
    // 현재 location을 state.from에 실어 전달한다. LoginPage가 로그인 성공 시 이를 읽는다.
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  // 비밀번호 변경 강제(WD-2): 임시 비밀번호를 아직 안 바꿨다. 테넌트 선택보다 먼저다(스펙 순서: 인증 →
  // 비밀번호 → 테넌트). 비밀번호 변경 API 는 인증만 요구하므로 테넌트 미선택 토큰으로도 된다.
  // 변경 전에는 AppLayout 이 부르는 API 가 전부 403 이므로 레이아웃을 그리기 전에 여기서 보낸다.
  if (mustChangePassword) {
    return <Navigate to="/change-password" replace />;
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
