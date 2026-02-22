import { useNavigate } from 'react-router-dom';
import { User, LogOut, ChevronsUpDown } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { Avatar, AvatarFallback } from '../ui/avatar';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import { cn } from '../../lib/utils';

function getInitials(name: string): string {
  return name.charAt(0).toUpperCase();
}

interface UserNavProps {
  collapsed?: boolean;
}

export function UserNav({ collapsed = false }: UserNavProps) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

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
        <Avatar className={cn('shrink-0', collapsed ? 'h-7 w-7' : 'h-8 w-8')}>
          <AvatarFallback className="text-xs">
            {getInitials(user.name || 'U')}
          </AvatarFallback>
        </Avatar>
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
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive" onClick={handleLogout}>
          <LogOut />
          로그아웃
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
