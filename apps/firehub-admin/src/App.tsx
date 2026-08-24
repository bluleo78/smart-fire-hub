import { ThemeProvider } from 'next-themes';
import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';

import { AdminShell } from './components/AdminShell';
import { PageSkeleton } from './components/PageSkeleton';
import { ProtectedRoute } from './components/ProtectedRoute';
import { Toaster } from './components/ui/sonner';
import { AuthProvider } from './hooks/AuthContext';

const LoginPage = lazy(() => import('./pages/LoginPage'));
const TenantListPage = lazy(() => import('./pages/TenantListPage'));
const TenantCreatePage = lazy(() => import('./pages/TenantCreatePage'));
const TenantDetailPage = lazy(() => import('./pages/TenantDetailPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));

function App() {
  return (
    // 운영자 콘솔은 데스크톱 전용이고 테마 토글 UI 를 두지 않는다. 그래도 OS 다크 모드를
    // 따라가야 index.css 의 .dark 토큰이 살아난다.
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            {/*
              /login 은 셸 밖이라 자기 경계가 필요하다. 셸 안 라우트들의 경계는
              `AdminShell.tsx` 안, `<Outlet/>` 을 감싸는 자리 하나에 있다 — 다섯 개였던
              페이지별 `<Suspense>` 를 두 개(셸 밖 하나 + 셸 안 하나)로 합쳤다.
              `<Routes>` 트리 전체를 경계 하나로 감싸지 않는 이유: 그러면 가장 가까운
              경계가 헤더·nav·계정 메뉴를 그리는 `AdminShell` 보다 **위**에 있게 되어,
              페이지 전환마다 셸까지 통째로 스켈레톤으로 바뀐다.
            */}
            <Route
              path="/login"
              element={
                <Suspense fallback={<PageSkeleton />}>
                  <LoginPage />
                </Suspense>
              }
            />
            <Route element={<ProtectedRoute />}>
              <Route element={<AdminShell />}>
                <Route path="/" element={<Navigate to="/tenants" replace />} />
                {/*
                  "이 화면을 볼 자격"을 라우트 선언에 둔다 — 화면 본문 선검사(옛
                  TenantCreatePage)가 하던 일이다. 각 페이지의 쿼리 403 분기는 그대로
                  남아 있고, 이제는 라우트 게이트를 통과한 뒤 경합으로 서버가 그래도
                  403 을 주는 경우만 잡는 심층방어다.
                */}
                <Route
                  element={<ProtectedRoute requiredPermission="platform:tenant:read" deniedTitle="테넌트" />}
                >
                  <Route path="/tenants" element={<TenantListPage />} />
                  <Route path="/tenants/:id" element={<TenantDetailPage />} />
                </Route>
                <Route
                  element={
                    <ProtectedRoute requiredPermission="platform:tenant:create" deniedTitle="테넌트 생성" />
                  }
                >
                  <Route path="/tenants/new" element={<TenantCreatePage />} />
                </Route>
                <Route
                  element={
                    <ProtectedRoute requiredPermission="platform:settings:read" deniedTitle="플랫폼 설정" />
                  }
                >
                  <Route path="/settings" element={<SettingsPage />} />
                </Route>
              </Route>
            </Route>
          </Routes>
          <Toaster />
        </AuthProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}

export default App;
