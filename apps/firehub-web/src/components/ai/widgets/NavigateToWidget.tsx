import { ExternalLink } from 'lucide-react';
import { useEffect, useRef } from 'react';

import { useAI } from '../AIProvider';
import { NAVIGABLE_ROUTES, resolveNavigationPath } from './routes';
import type { WidgetProps } from './types';

interface NavigateToInput {
  type: string;
  id?: number;
  label: string;
}

/** 페이지 이동 위젯 — 스트리밍 중에만 자동 이동, 히스토리 로드 시에는 클릭으로만 이동 */
export default function NavigateToWidget({ input, onNavigate }: WidgetProps<NavigateToInput>) {
  const { type, id, label } = input;
  const route = NAVIGABLE_ROUTES.find(r => r.type === type);
  const path = resolveNavigationPath(type, id);
  const navigated = useRef(false);
  const { isStreaming } = useAI();

  useEffect(() => {
    if (path && onNavigate && isStreaming && !navigated.current) {
      navigated.current = true;
      onNavigate(path);
    }
  }, [path, onNavigate, isStreaming]);

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
      <span className="text-xs text-muted-foreground">페이지로 이동</span>
    </div>
  );
}
