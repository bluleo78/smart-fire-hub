import { useState } from 'react';
import { Outlet, Link, useLocation } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
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
} from 'lucide-react';
import { cn } from '../../lib/utils';

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
];

export function AppLayout() {
  const { isAdmin } = useAuth();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const isActive = (href: string) => {
    if (href === '/') return location.pathname === '/';
    return location.pathname.startsWith(href);
  };

  const sidebarContent = (
    <div className="flex h-full flex-col">
      <div className="flex h-14 items-center px-4">
        <Link to="/" className="text-lg font-semibold" onClick={() => setSidebarOpen(false)}>
          Smart Fire Hub
        </Link>
      </div>
      <Separator />
      <nav className="flex-1 space-y-1 px-2 py-4">
        {navItems.map((item) => (
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
        <div className="px-3 py-2">
          <p className="text-xs font-semibold uppercase text-muted-foreground">데이터</p>
        </div>
        {dataNavItems.map((item) => (
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

  return (
    <div className="flex min-h-screen">
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
      <div className="flex flex-1 flex-col min-w-0">
        {/* Header */}
        <header className="sticky top-0 z-30 flex h-14 items-center border-b bg-background px-4">
          <Button variant="ghost" size="icon" className="lg:hidden" onClick={() => setSidebarOpen(!sidebarOpen)}>
            {sidebarOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </Button>
          <span className="ml-2 text-lg font-semibold lg:hidden">Smart Fire Hub</span>
          <div className="flex-1" />
          <UserNav />
        </header>

        {/* Page content */}
        <main className="flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
