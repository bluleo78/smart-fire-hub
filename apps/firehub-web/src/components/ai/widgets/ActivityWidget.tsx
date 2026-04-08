import { useQuery } from '@tanstack/react-query';

import { dashboardApi } from '../../../api/dashboard';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface ShowActivityInput {
  size?: number;
}

type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

/* 심각도별 도트 색상 — 시맨틱 토큰 사용 */
const SEVERITY_DOT_CLASS: Record<Severity, string> = {
  INFO: 'bg-info',
  WARNING: 'bg-warning',
  CRITICAL: 'bg-destructive',
};

const ENTITY_ICON: Record<string, string> = {
  PIPELINE: '⚙️',
  DATASET: '📦',
};

function formatRelativeTime(dateStr: string): string {
  const diff = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000);
  if (diff < 60) return `${diff}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86400)}일 전`;
}

function getEntityPath(entityType: string, entityId: number): string {
  if (entityType === 'PIPELINE') return `/pipelines/${entityId}`;
  if (entityType === 'DATASET') return `/data/datasets/${entityId}`;
  return '/';
}

export default function ActivityWidget({ input, onNavigate, displayMode }: WidgetProps<ShowActivityInput>) {
  const size = input.size ?? 10;

  const { data, isLoading, isError } = useQuery({
    queryKey: ['dashboard-activity', size],
    queryFn: () => dashboardApi.getActivity({ size }).then(r => r.data),
    staleTime: 15_000,
  });

  if (isLoading) {
    return (
      <WidgetShell title="최근 활동" icon="🕐" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">로딩 중...</div>
      </WidgetShell>
    );
  }

  if (isError || !data) {
    return (
      <WidgetShell title="최근 활동" icon="🕐" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-destructive">데이터를 불러올 수 없습니다</div>
      </WidgetShell>
    );
  }

  const items = data.items ?? [];

  return (
    <WidgetShell title="최근 활동" icon="🕐" displayMode={displayMode} onNavigate={onNavigate}>
      {items.length === 0 ? (
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">활동 내역이 없습니다.</div>
      ) : (
        <div className="divide-y divide-border/50">
          {items.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate?.(getEntityPath(item.entityType, item.entityId))}
              className="w-full text-left px-3 py-2 hover:bg-muted/20 transition-colors duration-150"
            >
              <div className="flex items-start gap-2">
                <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${SEVERITY_DOT_CLASS[item.severity as Severity] ?? 'bg-muted-foreground'}`} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1">
                    <span className="shrink-0">{ENTITY_ICON[item.entityType] ?? ''}</span>
                    <span className="truncate text-xs font-medium">{item.title}</span>
                  </div>
                  {item.description && (
                    <p className="truncate text-xs text-muted-foreground">{item.description}</p>
                  )}
                </div>
                <span className="shrink-0 text-xs text-muted-foreground">{formatRelativeTime(item.occurredAt)}</span>
              </div>
            </button>
          ))}
        </div>
      )}
    </WidgetShell>
  );
}
