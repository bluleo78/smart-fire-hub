import { useEffect, useRef } from 'react';
import { ExternalLink } from 'lucide-react';
import type { WidgetProps } from './types';

interface NavigateToInput {
  type: 'dataset' | 'pipeline' | 'dashboard';
  id: number;
  label: string;
}

const ROUTE_MAP: Record<string, (id: number) => string> = {
  dataset: (id) => `/data/datasets/${id}`,
  pipeline: (id) => `/pipelines/${id}`,
  dashboard: (id) => `/analytics/dashboards/${id}`,
};

const TYPE_LABELS: Record<string, string> = {
  dataset: '데이터셋',
  pipeline: '파이프라인',
  dashboard: '대시보드',
};

const TYPE_ICONS: Record<string, string> = {
  dataset: '📦',
  pipeline: '⚙️',
  dashboard: '📊',
};

export default function NavigateToWidget({ input, onNavigate }: WidgetProps<NavigateToInput>) {
  const { type, id, label } = input;
  const path = ROUTE_MAP[type]?.(id);
  const navigated = useRef(false);

  // Auto-navigate on mount (once)
  useEffect(() => {
    if (path && onNavigate && !navigated.current) {
      navigated.current = true;
      onNavigate(path);
    }
  }, [path, onNavigate]);

  if (!path) return null;

  return (
    <div className="my-1 flex items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2 text-sm">
      <span>{TYPE_ICONS[type] || '🔗'}</span>
      <span className="text-muted-foreground">
        {TYPE_LABELS[type] || type}
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
