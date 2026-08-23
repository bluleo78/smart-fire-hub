import { Flame } from 'lucide-react';
import { NavLink, Outlet } from 'react-router-dom';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useAuth } from '@/hooks/useAuth';
import { cn } from '@/lib/utils';

/**
 * 운영자 콘솔 셸 — 사이드바가 아니라 상단 바다(D-5).
 *
 * 목적지가 2개뿐이라 240px 사이드바는 링크 두 개와 여백이 되고, 그 하단의 테넌트 스위처는
 * 운영자 토큰에 테넌트가 없어 **구조적으로 렌더될 수 없다**. 두 앱을 나란히 열었을 때
 * 셸 형태가 다르다는 것 자체가 평면 칩보다 강한 구분 신호이기도 하다.
 */
export function AdminShell() {
  const { me, logout, hasPermission } = useAuth();

  const destinations = [
    { to: '/tenants', label: '테넌트', permission: 'platform:tenant:read' },
    { to: '/settings', label: '플랫폼 설정', permission: 'platform:settings:read' },
  ].filter((d) => hasPermission(d.permission));

  return (
    <div className="min-h-screen">
      <header className="flex h-14 items-center gap-6 border-b px-6">
        <div className="flex items-center gap-2">
          <Flame className="h-5 w-5" aria-hidden />
          <span className="font-semibold">Smart Fire Hub</span>
          {/* 평면 칩: 항상 헤더에 있고 색을 쓰지 않는다(D-4). 경고가 아니라 "어느 앱인가"의 답이다. */}
          <Badge variant="outline">운영자 콘솔</Badge>
        </div>

        <nav className="flex items-center gap-1" aria-label="주요 메뉴">
          {destinations.map((d) => (
            <NavLink
              key={d.to}
              to={d.to}
              // NavLink 는 활성 시 aria-current="page" 를 **기본으로** 붙인다.
              // aria-current prop 을 넘기면 그 기본이 지워지므로 넘기지 않는다.
              className={({ isActive }) =>
                cn(
                  'rounded-md px-3 py-1.5 text-sm transition-colors hover:bg-accent hover:text-accent-foreground',
                  isActive && 'bg-accent text-accent-foreground',
                )
              }
            >
              {d.label}
            </NavLink>
          ))}
        </nav>

        <div className="ml-auto">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="sm">
                {me?.name ?? me?.username ?? ''}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => void logout()}>로그아웃</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      <main className="mx-auto max-w-5xl p-6 pt-10">
        {destinations.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            이 콘솔에서 접근 가능한 메뉴가 없습니다. 플랫폼 관리자에게 권한을 요청하세요.
          </p>
        ) : (
          <Outlet />
        )}
      </main>
    </div>
  );
}
