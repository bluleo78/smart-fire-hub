import { useState, lazy, Suspense } from 'react';
import { Outlet, Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { Skeleton } from '../ui/skeleton';
import { Button } from '../ui/button';
import { Separator } from '../ui/separator';
import { UserNav } from './UserNav';
import {
  Home,
  Users,
  Shield,
  Menu,
  X,
  Database,
  GitBranch,
  Tag,
  FileText,
  Bot,
  Plug,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { AIProvider, useAI } from '../ai/AIProvider';
import { AIToggleButton } from '../ai/AIToggleButton';

const AISidePanel = lazy(() => import('../ai/AISidePanel').then(mod => ({ default: mod.AISidePanel })));
const AIFloating = lazy(() => import('../ai/AIFloating').then(mod => ({ default: mod.AIFloating })));
const AIFullScreen = lazy(() => import('../ai/AIFullScreen').then(mod => ({ default: mod.AIFullScreen })));

interface NavItem {
  label: string;
  href: string;
  icon: React.ReactNode;
  adminOnly?: boolean;
}

const navItems: NavItem[] = [
  { label: '홈', href: '/', icon: <Home className="h-4 w-4" /> },
];

const dataNavItems: NavItem[] = [
  { label: '카테고리', href: '/data/categories', icon: <Tag className="h-4 w-4" /> },
  { label: '데이터셋', href: '/data/datasets', icon: <Database className="h-4 w-4" /> },
  { label: '파이프라인', href: '/pipelines', icon: <GitBranch className="h-4 w-4" /> },
];

const adminNavItems: NavItem[] = [
  { label: '사용자 관리', href: '/admin/users', icon: <Users className="h-4 w-4" />, adminOnly: true },
  { label: '역할 관리', href: '/admin/roles', icon: <Shield className="h-4 w-4" />, adminOnly: true },
  { label: '감사 로그', href: '/admin/audit-logs', icon: <FileText className="h-4 w-4" />, adminOnly: true },
  { label: 'AI 설정', href: '/admin/ai-settings', icon: <Bot className="h-4 w-4" />, adminOnly: true },
  { label: 'API 연결', href: '/admin/api-connections', icon: <Plug className="h-4 w-4" />, adminOnly: true },
];

function PageSkeleton() {
  return (
    <div className="space-y-6 p-6">
      <Skeleton className="h-8 w-64" />
      <Skeleton className="h-96 w-full" />
    </div>
  );
}

function AppLayoutInner() {
  const { isAdmin } = useAuth();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { isOpen: aiOpen, mode: aiMode, setMode: setAIMode } = useAI();

  const isActive = (href: string) => {
    if (href === '/') return location.pathname === '/';
    return location.pathname.startsWith(href);
  };

  const handleNavClick = () => {
    setSidebarOpen(false);
    if (aiOpen && aiMode === 'fullscreen') {
      setAIMode('side');
    }
  };

  const sidebarContent = (
    <div className="flex h-full flex-col">
      <div className="flex h-14 items-center px-4">
        <Link to="/" className="text-lg font-semibold" onClick={handleNavClick}>
          Smart Fire Hub
        </Link>
      </div>
      <Separator />
      <nav className="flex-1 space-y-1 px-2 py-4">
        {navItems.map((item) => (
          <Link
            key={item.href}
            to={item.href}
            onClick={handleNavClick}
            className={cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
              isActive(item.href)
                ? 'bg-accent text-accent-foreground'
                : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
            )}
          >
            {item.icon}
            {item.label}
          </Link>
        ))}
        <div className="px-3 py-2">
          <p className="text-xs font-semibold uppercase text-muted-foreground">데이터</p>
        </div>
        {dataNavItems.map((item) => (
          <Link
            key={item.href}
            to={item.href}
            onClick={handleNavClick}
            className={cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
              isActive(item.href)
                ? 'bg-accent text-accent-foreground'
                : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
            )}
          >
            {item.icon}
            {item.label}
          </Link>
        ))}
        {isAdmin && (
          <>
            <div className="px-3 py-2">
              <p className="text-xs font-semibold uppercase text-muted-foreground">관리</p>
            </div>
            {adminNavItems.map((item) => (
              <Link
                key={item.href}
                to={item.href}
                onClick={() => setSidebarOpen(false)}
                className={cn(
                  'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                  isActive(item.href)
                    ? 'bg-accent text-accent-foreground'
                    : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
                )}
              >
                {item.icon}
                {item.label}
              </Link>
            ))}
          </>
        )}
      </nav>
    </div>
  );

  const showFullscreen = aiOpen && aiMode === 'fullscreen';

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Mobile sidebar overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-50 w-64 border-r bg-background transition-transform lg:static lg:translate-x-0',
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        )}
      >
        {sidebarContent}
      </aside>

      {/* Main content */}
      <div className="flex flex-1 flex-col min-w-0 min-h-0">
        {/* Header */}
        <header className="sticky top-0 z-30 flex h-14 items-center border-b bg-background px-4">
          <Button variant="ghost" size="icon" className="lg:hidden" onClick={() => setSidebarOpen(!sidebarOpen)}>
            {sidebarOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </Button>
          <span className="ml-2 text-lg font-semibold lg:hidden">Smart Fire Hub</span>
          <div className="flex-1" />
          <div className="flex items-center gap-2">
            <AIToggleButton />
            <UserNav />
          </div>
        </header>

        {/* Page content + AI panel */}
        <div className="flex flex-1 min-h-0">
          {/* Main page content */}
          {showFullscreen ? (
            <div className="flex-1 flex">
              <Suspense fallback={<div className="flex-1 bg-background" />}>
                <AIFullScreen />
              </Suspense>
            </div>
          ) : (
            <main className="flex-1 p-6 overflow-auto min-w-0">
              <Suspense fallback={<PageSkeleton />}>
                <Outlet />
              </Suspense>
            </main>
          )}

          {/* Side panel mode */}
          {aiMode === 'side' && (
            <Suspense fallback={<div className="w-80 border-l bg-background" />}>
              <AISidePanel />
            </Suspense>
          )}
        </div>
      </div>

      {/* Floating mode */}
      {aiMode === 'floating' && (
        <Suspense fallback={null}>
          <AIFloating />
        </Suspense>
      )}
    </div>
  );
}

export function AppLayout() {
  return (
    <AIProvider>
      <AppLayoutInner />
    </AIProvider>
  );
}
