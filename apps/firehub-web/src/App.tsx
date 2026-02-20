import { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider } from './hooks/AuthContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { AdminRoute } from './components/AdminRoute';
import { AppLayout } from './components/layout/AppLayout';
import { Toaster } from './components/ui/sonner';
import { Skeleton } from './components/ui/skeleton';

// Lazy-loaded pages
const LoginPage = lazy(() => import('./pages/LoginPage'));
const SignupPage = lazy(() => import('./pages/SignupPage'));
const HomePage = lazy(() => import('./pages/HomePage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const UserListPage = lazy(() => import('./pages/admin/UserListPage'));
const UserDetailPage = lazy(() => import('./pages/admin/UserDetailPage'));
const RoleListPage = lazy(() => import('./pages/admin/RoleListPage'));
const RoleDetailPage = lazy(() => import('./pages/admin/RoleDetailPage'));
const AuditLogListPage = lazy(() => import('./pages/admin/AuditLogListPage'));
const AISettingsPage = lazy(() => import('./pages/admin/AISettingsPage'));
const CategoryListPage = lazy(() => import('./pages/data/CategoryListPage'));
const DatasetListPage = lazy(() => import('./pages/data/DatasetListPage'));
const DatasetCreatePage = lazy(() => import('./pages/data/DatasetCreatePage'));
const DatasetDetailPage = lazy(() => import('./pages/data/DatasetDetailPage'));
const PipelineListPage = lazy(() => import('./pages/pipeline/PipelineListPage'));
const PipelineEditorPage = lazy(() => import('./pages/pipeline/PipelineEditorPage'));

function PageSkeleton() {
  return (
    <div className="space-y-6 p-6">
      <Skeleton className="h-8 w-64" />
      <Skeleton className="h-96 w-full" />
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Suspense fallback={<PageSkeleton />}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route element={<ProtectedRoute />}>
              <Route element={<AppLayout />}>
                <Route path="/" element={<HomePage />} />
                <Route path="/profile" element={<ProfilePage />} />
                <Route path="/data/categories" element={<CategoryListPage />} />
                <Route path="/data/datasets" element={<DatasetListPage />} />
                <Route path="/data/datasets/new" element={<DatasetCreatePage />} />
                <Route path="/data/datasets/:id" element={<DatasetDetailPage />} />
                <Route path="/pipelines" element={<PipelineListPage />} />
                <Route path="/pipelines/new" element={<PipelineEditorPage />} />
                <Route path="/pipelines/:id" element={<PipelineEditorPage />} />
                <Route path="/pipelines/:id/executions/:execId" element={<PipelineEditorPage />} />
                <Route element={<AdminRoute />}>
                  <Route path="/admin/users" element={<UserListPage />} />
                  <Route path="/admin/users/:id" element={<UserDetailPage />} />
                  <Route path="/admin/roles" element={<RoleListPage />} />
                  <Route path="/admin/roles/:id" element={<RoleDetailPage />} />
                  <Route path="/admin/audit-logs" element={<AuditLogListPage />} />
                  <Route path="/admin/ai-settings" element={<AISettingsPage />} />
                </Route>
              </Route>
            </Route>
          </Routes>
        </Suspense>
        <Toaster />
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
