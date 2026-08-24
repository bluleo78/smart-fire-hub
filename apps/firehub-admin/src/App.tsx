import { ThemeProvider } from 'next-themes';
import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';

import { AdminShell } from './components/AdminShell';
import { ProtectedRoute } from './components/ProtectedRoute';
import { Skeleton } from './components/ui/skeleton';
import { Toaster } from './components/ui/sonner';
import { AuthProvider } from './hooks/AuthContext';

const LoginPage = lazy(() => import('./pages/LoginPage'));
const TenantListPage = lazy(() => import('./pages/TenantListPage'));
const TenantCreatePage = lazy(() => import('./pages/TenantCreatePage'));
const TenantDetailPage = lazy(() => import('./pages/TenantDetailPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));

function PageSkeleton() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-9 w-64" />
      <Skeleton className="h-96 w-full" />
    </div>
  );
}

function App() {
  return (
    // 운영자 콘솔은 데스크톱 전용이고 테마 토글 UI 를 두지 않는다. 그래도 OS 다크 모드를
    // 따라가야 index.css 의 .dark 토큰이 살아난다.
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
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
                <Route
                  path="/tenants"
                  element={
                    <Suspense fallback={<PageSkeleton />}>
                      <TenantListPage />
                    </Suspense>
                  }
                />
                <Route
                  path="/tenants/new"
                  element={
                    <Suspense fallback={<PageSkeleton />}>
                      <TenantCreatePage />
                    </Suspense>
                  }
                />
                <Route
                  path="/tenants/:id"
                  element={
                    <Suspense fallback={<PageSkeleton />}>
                      <TenantDetailPage />
                    </Suspense>
                  }
                />
                <Route
                  path="/settings"
                  element={
                    <Suspense fallback={<PageSkeleton />}>
                      <SettingsPage />
                    </Suspense>
                  }
                />
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
