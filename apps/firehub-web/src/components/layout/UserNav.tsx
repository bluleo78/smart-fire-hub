import { Bell, ChevronsUpDown, LogOut, Monitor, Moon, Sun, User } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../../hooks/useAuth';
import { useThemeColor } from '../../hooks/useThemeColor';
import { cn } from '../../lib/utils';
import { Avatar, AvatarFallback } from '../ui/avatar';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../ui/select';

function getInitials(name: string): string {
  return name.charAt(0).toUpperCase();
}

const THEME_COLORS = [
  { value: 'indigo', label: 'Indigo', light: '#eef2ff', dark: '#1a1a40' },
  { value: 'ocean', label: 'Ocean', light: '#ecfeff', dark: '#0c1a2a' },
  { value: 'sunset', label: 'Sunset', light: '#fff7ed', dark: '#1f1510' },
] as const;

function ThemeColorSwatch({ light, dark }: { light: string; dark: string }) {
  return (
    <span className="inline-flex w-4 h-4 rounded-full overflow-hidden border border-border shrink-0">
      <span className="w-1/2 h-full" style={{ background: light }} />
      <span className="w-1/2 h-full" style={{ background: dark }} />
    </span>
  );
}

interface UserNavProps {
  collapsed?: boolean;
}

export function UserNav({ collapsed = false }: UserNavProps) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  // theme = 사용자가 고른 설정('light'|'dark'|'system'), resolvedTheme = 실제 적용된 모드.
  // #369: 선택 상태는 '설정'을 표시해야 하므로 theme을 쓴다. (resolvedTheme은 'system'을 절대
  // 반환하지 않아, 시스템 버튼이 영영 선택 표시되지 않는 결함이 있었다)
  const { theme, setTheme } = useTheme();
  const { themeColor, setThemeColor } = useThemeColor();

  if (!user) {
    return null;
  }

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          'flex w-full items-center gap-2 rounded-md p-2 text-sm transition-colors',
          'hover:bg-accent/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          collapsed ? 'justify-center' : 'px-3'
        )}
      >
        <div className="relative status-online">
          <Avatar className={cn('shrink-0', collapsed ? 'h-7 w-7' : 'h-8 w-8')}>
            <AvatarFallback className="text-xs bg-primary/10 text-primary font-semibold">
              {getInitials(user.name || 'U')}
            </AvatarFallback>
          </Avatar>
        </div>
        {!collapsed && (
          <>
            <div className="flex-1 text-left min-w-0">
              <p className="truncate font-medium leading-tight">{user.name}</p>
              <p className="truncate text-xs text-muted-foreground leading-tight">
                {user.email || user.username}
              </p>
            </div>
            <ChevronsUpDown className="h-4 w-4 shrink-0 text-muted-foreground" />
          </>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent
        side={collapsed ? 'right' : 'top'}
        align="start"
        className="w-56"
      >
        <DropdownMenuLabel>
          <div className="flex flex-col space-y-1">
            <p className="font-bold">{user.name}</p>
            <p className="text-xs text-muted-foreground">
              {user.email || user.username}
            </p>
          </div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => navigate('/profile')}>
          <User />
          프로필
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => navigate('/settings/channels')}>
          <Bell />
          알림 채널
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        {/* Theme section */}
        <div className="px-2 py-1.5">
          <div className="flex items-center gap-2 mb-1.5">
            <span className="text-[10px] font-semibold text-muted-foreground w-7">테마</span>
            <Select value={themeColor} onValueChange={(v) => setThemeColor(v as 'indigo' | 'ocean' | 'sunset')}>
              <SelectTrigger className="h-7 flex-1 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {THEME_COLORS.map((t) => (
                  <SelectItem key={t.value} value={t.value}>
                    <div className="flex items-center gap-2">
                      <ThemeColorSwatch light={t.light} dark={t.dark} />
                      {t.label}
                    </div>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-semibold text-muted-foreground w-7">모드</span>
            <div className="flex-1 flex bg-muted rounded-md p-0.5" role="group" aria-label="테마 모드">
              <button
                type="button"
                onClick={() => setTheme('light')}
                aria-label="라이트 모드"
                aria-pressed={theme === 'light'}
                className={cn(
                  'flex-1 flex items-center justify-center gap-1 rounded py-1 text-[10px] font-semibold transition-colors cursor-pointer',
                  theme === 'light'
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                <Sun className="h-3 w-3" aria-hidden="true" />
              </button>
              <button
                type="button"
                onClick={() => setTheme('dark')}
                aria-label="다크 모드"
                aria-pressed={theme === 'dark'}
                className={cn(
                  'flex-1 flex items-center justify-center gap-1 rounded py-1 text-[10px] font-semibold transition-colors cursor-pointer',
                  theme === 'dark'
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                <Moon className="h-3 w-3" aria-hidden="true" />
              </button>
              <button
                type="button"
                onClick={() => setTheme('system')}
                aria-label="시스템 설정 따르기"
                aria-pressed={theme === 'system'}
                className={cn(
                  'flex-1 flex items-center justify-center gap-1 rounded py-1 text-[10px] font-semibold transition-colors cursor-pointer',
                  theme === 'system'
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                <Monitor className="h-3 w-3" aria-hidden="true" />
              </button>
            </div>
          </div>
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive" onClick={handleLogout}>
          <LogOut />
          로그아웃
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
