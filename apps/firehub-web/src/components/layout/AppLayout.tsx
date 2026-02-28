import type { LucideIcon } from 'lucide-react';
import {
  BarChart3,
  Bot,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  Database,
  FileText,
  Flame,
  GitBranch,
  Home,
  LayoutDashboard,
  Menu,
  Plug,
  Search,
  Settings,
  Shield,
  Tag,
  Users,
  X,
} from 'lucide-react';
import { lazy, Suspense,useState } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';

import { useAuth } from '../../hooks/useAuth';
import { cn } from '../../lib/utils';
import { AIProvider, useAI } from '../ai/AIProvider';
import { AIToggleButton } from '../ai/AIToggleButton';
import { Button } from '../ui/button';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '../ui/collapsible';
import { Separator } from '../ui/separator';
import { Skeleton } from '../ui/skeleton';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../ui/tooltip';
import { UserNav } from './UserNav';

const AISidePanel = lazy(() =>
  import('../ai/AISidePanel').then((mod) => ({ default: mod.AISidePanel }))
);
const AIFloating = lazy(() =>
  import('../ai/AIFloating').then((mod) => ({ default: mod.AIFloating }))
);
const AIFullScreen = lazy(() =>
  import('../ai/AIFullScreen').then((mod) => ({ default: mod.AIFullScreen }))
);

interface NavItem {
  label: string;
  href: string;
  icon: LucideIcon;
}

const navItems: NavItem[] = [
  { label: '홈', href: '/', icon: Home },
];

const dataNavItems: NavItem[] = [
  { label: '데이터셋', href: '/data/datasets', icon: Database },
  { label: '카테고리', href: '/data/categories', icon: Tag },
];

const analyticsNavItems: NavItem[] = [
  { label: '쿼리', href: '/analytics/queries', icon: Search },
  { label: '차트', href: '/analytics/charts', icon: BarChart3 },
  { label: '대시보드', href: '/analytics/dashboards', icon: LayoutDashboard },
];

const automationNavItems: NavItem[] = [
  { label: '파이프라인', href: '/pipelines', icon: GitBranch },
  { label: 'API 연결', href: '/admin/api-connections', icon: Plug },
];

const adminNavItems: NavItem[] = [
  { label: '사용자 관리', href: '/admin/users', icon: Users },
  { label: '역할 관리', href: '/admin/roles', icon: Shield },
  { label: '감사 로그', href: '/admin/audit-logs', icon: FileText },
  { label: 'AI 설정', href: '/admin/ai-settings', icon: Bot },
  { label: '설정', href: '/admin/settings', icon: Settings },
];

function PageSkeleton() {
  return (
    <div className="space-y-6 p-6">
      <Skeleton className="h-8 w-64" />
      <Skeleton className="h-96 w-full" />
    </div>
  );
}

function NavItemLink({
  item,
  active,
  collapsed,
  onClick,
}: {
  item: NavItem;
  active: boolean;
  collapsed: boolean;
  onClick: () => void;
}) {
  const Icon = item.icon;

  const link = (
    <Link
      to={item.href}
      onClick={onClick}
      className={cn(
        'flex items-center rounded-md text-[13px] font-medium transition-colors',
        active
          ? 'bg-accent text-accent-foreground [&_svg]:text-primary'
          : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground',
        collapsed ? 'justify-center px-2 py-2.5 mx-1' : 'gap-3 px-3 py-1.5'
      )}
    >
      <Icon className={cn('shrink-0', collapsed ? 'h-5 w-5' : 'h-4 w-4')} />
      {!collapsed && <span>{item.label}</span>}
    </Link>
  );

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{link}</TooltipTrigger>
        <TooltipContent side="right" sideOffset={8}>
          {item.label}
        </TooltipContent>
      </Tooltip>
    );
  }

  return link;
}

function NavSection({
  label,
  items,
  isActive,
  collapsed,
  onClick,
  open,
  onOpenChange,
}: {
  label: string;
  items: NavItem[];
  isActive: (href: string) => boolean;
  collapsed: boolean;
  onClick: () => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  if (collapsed) {
    return (
      <>
        <Separator className="my-3 mx-auto w-6" />
        <div className="space-y-1">
          {items.map((item) => (
            <NavItemLink
              key={item.href}
              item={item}
              active={isActive(item.href)}
              collapsed={collapsed}
              onClick={onClick}
            />
          ))}
        </div>
      </>
    );
  }

  return (
    <Collapsible open={open} onOpenChange={onOpenChange} className="mt-4">
      <CollapsibleTrigger className="flex w-full items-center justify-between px-3 py-1.5 text-xs font-semibold tracking-wider text-muted-foreground hover:text-foreground transition-colors cursor-pointer">
        <span>{label}</span>
        {open ? (
          <ChevronDown className="h-3 w-3" />
        ) : (
          <ChevronRight className="h-3 w-3" />
        )}
      </CollapsibleTrigger>
      <CollapsibleContent className="overflow-hidden space-y-1">
        {items.map((item) => (
          <NavItemLink
            key={item.href}
            item={item}
            active={isActive(item.href)}
            collapsed={collapsed}
            onClick={onClick}
          />
        ))}
      </CollapsibleContent>
    </Collapsible>
  );
}

function AppLayoutInner() {
  const { isAdmin } = useAuth();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { isOpen: aiOpen, mode: aiMode, setMode: setAIMode } = useAI();
  const [collapsed, setCollapsed] = useState(() => {
    return localStorage.getItem('sidebar-collapsed') === 'true';
  });
  const [dataOpen, setDataOpen] = useState(true);
  const [analyticsOpen, setAnalyticsOpen] = useState(true);
  const [automationOpen, setAutomationOpen] = useState(true);
  const [adminOpen, setAdminOpen] = useState(true);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      localStorage.setItem('sidebar-collapsed', String(!prev));
      return !prev;
    });
  };

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
          'fixed inset-y-0 left-0 z-50 border-r bg-background transition-[width,transform] duration-200',
          'lg:static lg:translate-x-0',
          collapsed ? 'lg:w-[52px]' : 'lg:w-60',
          sidebarOpen ? 'translate-x-0 w-60' : '-translate-x-full'
        )}
      >
        <TooltipProvider delayDuration={0}>
          <div className="flex h-full flex-col">
            {/* Sidebar header */}
            <div
              className={cn(
                'flex h-14 shrink-0 items-center border-b',
                collapsed ? 'justify-center px-1' : 'justify-between px-4'
              )}
            >
              {collapsed ? (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={toggleCollapsed}
                      className="h-8 w-8"
                    >
                      <Flame className="h-5 w-5 text-primary" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent side="right" sideOffset={8}>
                    메뉴 펼치기
                  </TooltipContent>
                </Tooltip>
              ) : (
                <>
                  <Link
                    to="/"
                    className="flex items-center gap-2 text-base font-semibold"
                    onClick={handleNavClick}
                  >
                    <Flame className="h-5 w-5 shrink-0 text-primary" />
                    <span className="truncate">Smart Fire Hub</span>
                  </Link>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7 shrink-0 hidden lg:flex text-muted-foreground hover:text-foreground"
                    onClick={toggleCollapsed}
                  >
                    <ChevronsLeft className="h-4 w-4" />
                  </Button>
                </>
              )}
            </div>

            {/* Navigation */}
            <nav className="flex-1 overflow-y-auto py-2">
              <div className="space-y-1">
                {navItems.map((item) => (
                  <NavItemLink
                    key={item.href}
                    item={item}
                    active={isActive(item.href)}
                    collapsed={collapsed}
                    onClick={handleNavClick}
                  />
                ))}
              </div>

              <NavSection
                label="데이터"
                items={dataNavItems}
                isActive={isActive}
                collapsed={collapsed}
                onClick={handleNavClick}
                open={dataOpen}
                onOpenChange={setDataOpen}
              />

              <NavSection
                label="분석"
                items={analyticsNavItems}
                isActive={isActive}
                collapsed={collapsed}
                onClick={handleNavClick}
                open={analyticsOpen}
                onOpenChange={setAnalyticsOpen}
              />

              <NavSection
                label="자동화"
                items={automationNavItems}
                isActive={isActive}
                collapsed={collapsed}
                onClick={handleNavClick}
                open={automationOpen}
                onOpenChange={setAutomationOpen}
              />

              {isAdmin && (
                <NavSection
                  label="관리"
                  items={adminNavItems}
                  isActive={isActive}
                  collapsed={collapsed}
                  onClick={handleNavClick}
                  open={adminOpen}
                  onOpenChange={setAdminOpen}
                />
              )}
            </nav>

            {/* Bottom anchor: UserNav */}
            <div className="shrink-0 border-t">
              <UserNav collapsed={collapsed} />
            </div>
          </div>
        </TooltipProvider>
      </aside>

      {/* Main content */}
      <div className="flex flex-1 flex-col min-w-0 min-h-0">
        {/* Header */}
        <header className="sticky top-0 z-30 flex h-14 items-center border-b bg-background px-4">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            onClick={() => setSidebarOpen(!sidebarOpen)}
          >
            {sidebarOpen ? (
              <X className="h-5 w-5" />
            ) : (
              <Menu className="h-5 w-5" />
            )}
          </Button>
          <span className="ml-2 text-lg font-semibold lg:hidden">
            Smart Fire Hub
          </span>
          <div className="flex-1" />
          <div className="flex items-center gap-2">
            <AIToggleButton />
          </div>
        </header>

        {/* Page content + AI panel */}
        <div className="flex flex-1 min-h-0">
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
            <Suspense
              fallback={<div className="w-80 border-l bg-background" />}
            >
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
