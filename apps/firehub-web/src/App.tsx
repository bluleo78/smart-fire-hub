import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider } from './hooks/AuthContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { AdminRoute } from './components/AdminRoute';
import { AppLayout } from './components/layout/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { SignupPage } from './pages/SignupPage';
import { HomePage } from './pages/HomePage';
import { ProfilePage } from './pages/ProfilePage';
import { UserListPage } from './pages/admin/UserListPage';
import { UserDetailPage } from './pages/admin/UserDetailPage';
import { RoleListPage } from './pages/admin/RoleListPage';
import { RoleDetailPage } from './pages/admin/RoleDetailPage';
import { AuditLogListPage } from './pages/admin/AuditLogListPage';
import { CategoryListPage } from './pages/data/CategoryListPage';
import { DatasetListPage } from './pages/data/DatasetListPage';
import { DatasetCreatePage } from './pages/data/DatasetCreatePage';
import { DatasetDetailPage } from './pages/data/DatasetDetailPage';
import { PipelineListPage } from './pages/pipeline/PipelineListPage';
import { PipelineCreatePage } from './pages/pipeline/PipelineCreatePage';
import { PipelineDetailPage } from './pages/pipeline/PipelineDetailPage';
import { ExecutionDetailPage } from './pages/pipeline/ExecutionDetailPage';
import { Toaster } from './components/ui/sonner';

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
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
              <Route path="/pipelines/new" element={<PipelineCreatePage />} />
              <Route path="/pipelines/:id" element={<PipelineDetailPage />} />
              <Route path="/pipelines/:id/executions/:execId" element={<ExecutionDetailPage />} />
              <Route element={<AdminRoute />}>
                <Route path="/admin/users" element={<UserListPage />} />
                <Route path="/admin/users/:id" element={<UserDetailPage />} />
                <Route path="/admin/roles" element={<RoleListPage />} />
                <Route path="/admin/roles/:id" element={<RoleDetailPage />} />
                <Route path="/admin/audit-logs" element={<AuditLogListPage />} />
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
