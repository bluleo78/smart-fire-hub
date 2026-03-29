import { ExternalLink } from 'lucide-react';
import { cn } from '../../../lib/utils';
import type { WidgetShellProps } from './types';

const MAX_HEIGHT: Record<string, string> = {
  side: 'max-h-[250px]',
  floating: 'max-h-[250px]',
  fullscreen: 'max-h-[450px]',
  native: 'max-h-none',
};

export function WidgetShell({
  title, icon, subtitle, actions, navigateTo, onNavigate, displayMode, children,
}: WidgetShellProps) {
  return (
    <div className="my-1 overflow-hidden rounded-lg border border-border">
      <div className="flex items-center justify-between gap-2 border-b border-border bg-muted/50 px-3 py-1.5">
        <div className="flex items-center gap-2 min-w-0">
          <span className="shrink-0">{icon}</span>
          <span className="truncate font-medium text-sm">{title}</span>
          {subtitle && <span className="shrink-0 text-xs text-muted-foreground">{subtitle}</span>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {actions}
          {navigateTo && onNavigate && (
            <button
              onClick={() => onNavigate(navigateTo)}
              className="flex items-center gap-1 text-xs text-primary hover:underline"
            >
              상세 보기
              <ExternalLink className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>
      <div className={cn('overflow-auto', MAX_HEIGHT[displayMode] || MAX_HEIGHT.side)}>
        {children}
      </div>
    </div>
  );
}
