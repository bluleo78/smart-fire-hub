import { useEffect, useRef } from 'react';
import { ExternalLink } from 'lucide-react';
import type { WidgetProps } from './types';
import { NAVIGABLE_ROUTES, resolveNavigationPath } from './routes';

interface NavigateToInput {
  type: string;
  id?: number;
  label: string;
}

export default function NavigateToWidget({ input, onNavigate }: WidgetProps<NavigateToInput>) {
  const { type, id, label } = input;
  const route = NAVIGABLE_ROUTES.find(r => r.type === type);
  const path = resolveNavigationPath(type, id);
  const navigated = useRef(false);

  useEffect(() => {
    if (path && onNavigate && !navigated.current) {
      navigated.current = true;
      onNavigate(path);
    }
  }, [path, onNavigate]);

  if (!path) return null;

  return (
    <div className="my-1 flex items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2 text-sm">
      <span>{route?.icon || '🔗'}</span>
      <span className="text-muted-foreground">
        {route?.label || type}
      </span>
      <button
        onClick={() => onNavigate?.(path)}
        className="flex items-center gap-1 font-medium text-primary hover:underline"
      >
        {label}
        <ExternalLink className="h-3 w-3" />
      </button>
      <span className="text-xs text-muted-foreground">으로 이동했습니다</span>
    </div>
  );
}
