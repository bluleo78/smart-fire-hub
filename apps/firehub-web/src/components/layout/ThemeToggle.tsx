import { Moon, Sun } from 'lucide-react';
import { useTheme } from 'next-themes';

import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '../ui/tooltip';

interface ThemeToggleProps {
  collapsed?: boolean;
}

export function ThemeToggle({ collapsed = false }: ThemeToggleProps) {
  const { resolvedTheme, setTheme } = useTheme();

  const toggle = () => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark');

  const button = (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggle}
      className={cn(
        'relative transition-colors text-muted-foreground hover:text-foreground',
        collapsed ? 'h-8 w-8 mx-auto' : 'h-8 w-8'
      )}
      aria-label="테마 전환"
    >
      <Sun className="h-4 w-4 rotate-0 scale-100 transition-all dark:-rotate-90 dark:scale-0" />
      <Moon className="absolute h-4 w-4 rotate-90 scale-0 transition-all dark:rotate-0 dark:scale-100" />
    </Button>
  );

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{button}</TooltipTrigger>
        <TooltipContent side="right" sideOffset={8}>
          테마 전환
        </TooltipContent>
      </Tooltip>
    );
  }

  return (
    <div className="flex items-center justify-between px-3 py-1">
      <span className="text-xs text-muted-foreground">테마</span>
      {button}
    </div>
  );
}
