import { useQuery } from '@tanstack/react-query';

import { dashboardApi } from '../../../api/dashboard';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
interface ShowDashboardSummaryInput {}

export default function DashboardWidget({ onNavigate, displayMode }: WidgetProps<ShowDashboardSummaryInput>) {
  const { data: health, isLoading: healthLoading } = useQuery({
    queryKey: ['dashboard-health'],
    queryFn: () => dashboardApi.getHealth().then(r => r.data),
    staleTime: 30_000,
  });

  const { data: stats, isLoading: statsLoading } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: () => dashboardApi.getStats().then(r => r.data),
    staleTime: 30_000,
  });

  const { data: attention, isLoading: attentionLoading } = useQuery({
    queryKey: ['dashboard-attention'],
    queryFn: () => dashboardApi.getAttention().then(r => r.data),
    staleTime: 30_000,
  });

  const isLoading = healthLoading || statsLoading || attentionLoading;

  if (isLoading) {
    return (
      <WidgetShell title="시스템 현황" icon="📈" displayMode={displayMode} onNavigate={onNavigate} navigateTo="/">
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">로딩 중...</div>
      </WidgetShell>
    );
  }

  if (!stats || !health) {
    return (
      <WidgetShell title="시스템 현황" icon="📈" displayMode={displayMode} onNavigate={onNavigate} navigateTo="/">
        <div className="flex items-center justify-center py-6 text-sm text-destructive">데이터를 불러올 수 없습니다</div>
      </WidgetShell>
    );
  }

  const attentionCount = attention?.length ?? 0;
  const hasCritical = attention?.some(a => a.severity === 'CRITICAL') ?? false;
  const totalPipelines = health.pipelineHealth.total;
  const healthyPipelines = health.pipelineHealth.healthy;
  const successRate = totalPipelines > 0 ? Math.round((healthyPipelines / totalPipelines) * 100) : 0;

  const kpis = [
    {
      label: '데이터셋 수',
      value: stats.totalDatasets,
      colorClass: 'text-dataset',
    },
    {
      label: '파이프라인 수',
      value: stats.totalPipelines,
      colorClass: 'text-pipeline',
    },
    {
      label: '주의 필요',
      value: attentionCount,
      colorClass: attentionCount === 0 ? 'text-muted-foreground' : hasCritical ? 'text-destructive' : 'text-warning',
    },
    {
      label: '성공률',
      value: `${successRate}%`,
      colorClass: 'text-foreground',
    },
  ];

  return (
    <WidgetShell
      title="시스템 현황"
      icon="📈"
      navigateTo="/"
      onNavigate={onNavigate}
      displayMode={displayMode}
    >
      <div className="grid grid-cols-2 gap-px bg-border">
        {kpis.map((kpi) => (
          <div key={kpi.label} className="flex flex-col items-center justify-center gap-0.5 bg-background px-3 py-3">
            <span className={`text-2xl font-bold tabular-nums ${kpi.colorClass}`}>{kpi.value}</span>
            <span className="text-xs text-muted-foreground">{kpi.label}</span>
          </div>
        ))}
      </div>
    </WidgetShell>
  );
}
