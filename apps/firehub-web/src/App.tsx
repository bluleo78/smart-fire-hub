import { lazy,Suspense } from 'react';
import { BrowserRouter, Route,Routes } from 'react-router-dom';

import { AdminRoute } from './components/AdminRoute';
import { AppLayout } from './components/layout/AppLayout';
import { ProtectedRoute } from './components/ProtectedRoute';
import { Skeleton } from './components/ui/skeleton';
import { Toaster } from './components/ui/sonner';
import { AuthProvider } from './hooks/AuthContext';

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
const ApiConnectionListPage = lazy(() => import('./pages/admin/ApiConnectionListPage'));
const ApiConnectionDetailPage = lazy(() => import('./pages/admin/ApiConnectionDetailPage'));
const CategoryListPage = lazy(() => import('./pages/data/CategoryListPage'));
const DatasetListPage = lazy(() => import('./pages/data/DatasetListPage'));
const DatasetCreatePage = lazy(() => import('./pages/data/DatasetCreatePage'));
const DatasetDetailPage = lazy(() => import('./pages/data/DatasetDetailPage'));
const PipelineListPage = lazy(() => import('./pages/pipeline/PipelineListPage'));
const PipelineEditorPage = lazy(() => import('./pages/pipeline/PipelineEditorPage'));
const QueryListPage = lazy(() => import('./pages/analytics/QueryListPage'));
const QueryEditorPage = lazy(() => import('./pages/analytics/QueryEditorPage'));
const ChartListPage = lazy(() => import('./pages/analytics/ChartListPage'));
const ChartBuilderPage = lazy(() => import('./pages/analytics/ChartBuilderPage'));
const DashboardListPage = lazy(() => import('./pages/analytics/DashboardListPage'));
const DashboardEditorPage = lazy(() => import('./pages/analytics/DashboardEditorPage'));

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
        <Routes>
          <Route path="/login" element={<Suspense fallback={<PageSkeleton />}><LoginPage /></Suspense>} />
          <Route path="/signup" element={<Suspense fallback={<PageSkeleton />}><SignupPage /></Suspense>} />
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
              <Route path="/analytics/queries" element={<QueryListPage />} />
              <Route path="/analytics/queries/new" element={<QueryEditorPage />} />
              <Route path="/analytics/queries/:id" element={<QueryEditorPage />} />
              <Route path="/analytics/charts" element={<ChartListPage />} />
              <Route path="/analytics/charts/new" element={<ChartBuilderPage />} />
              <Route path="/analytics/charts/:id" element={<ChartBuilderPage />} />
              <Route path="/analytics/dashboards" element={<DashboardListPage />} />
              <Route path="/analytics/dashboards/:id" element={<DashboardEditorPage />} />
              <Route element={<AdminRoute />}>
                <Route path="/admin/users" element={<UserListPage />} />
                <Route path="/admin/users/:id" element={<UserDetailPage />} />
                <Route path="/admin/roles" element={<RoleListPage />} />
                <Route path="/admin/roles/:id" element={<RoleDetailPage />} />
                <Route path="/admin/audit-logs" element={<AuditLogListPage />} />
                <Route path="/admin/ai-settings" element={<AISettingsPage />} />
                <Route path="/admin/api-connections" element={<ApiConnectionListPage />} />
                <Route path="/admin/api-connections/:id" element={<ApiConnectionDetailPage />} />
              </Route>
            </Route>
          </Route>
        </Routes>
        <Toaster />
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
