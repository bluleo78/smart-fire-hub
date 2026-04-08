import { useQuery } from '@tanstack/react-query';

import { pipelinesApi } from '../../../api/pipelines';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface ShowPipelineInput {
  pipelineId: number;
}

type ExecutionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

const STATUS_LABEL: Record<ExecutionStatus, string> = {
  PENDING: '대기중',
  RUNNING: '실행중',
  COMPLETED: '성공',
  FAILED: '실패',
  CANCELLED: '취소됨',
};

/* 실행 상태별 배지 색상 — 시맨틱 토큰 사용 */
const STATUS_CLASS: Record<ExecutionStatus, string> = {
  PENDING: 'bg-warning/10 text-warning',
  RUNNING: 'bg-info/10 text-info',
  COMPLETED: 'bg-success/10 text-success',
  FAILED: 'bg-destructive/10 text-destructive',
  CANCELLED: 'bg-muted text-muted-foreground',
};


function formatRelativeTime(dateStr: string | null | undefined): string {
  if (!dateStr) return '';
  const diff = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000);
  if (diff < 60) return `${diff}초 전`;
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  return `${Math.floor(diff / 86400)}일 전`;
}

function formatDuration(startedAt: string | null, completedAt: string | null): string {
  if (!startedAt) return '';
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const ms = end - new Date(startedAt).getTime();
  return `${(ms / 1000).toFixed(1)}s`;
}

export default function PipelineStatusWidget({ input, onNavigate, displayMode }: WidgetProps<ShowPipelineInput>) {
  const pipelineId = Number(input.pipelineId);

  const { data: pipeline, isLoading: pipelineLoading, isError: pipelineError } = useQuery({
    queryKey: ['pipeline', pipelineId],
    queryFn: () => pipelinesApi.getPipelineById(pipelineId).then(r => r.data),
    staleTime: 15_000,
    enabled: !!pipelineId,
  });

  const { data: executions, isLoading: execLoading } = useQuery({
    queryKey: ['pipeline-executions', pipelineId],
    queryFn: () => pipelinesApi.getExecutions(pipelineId).then(r => r.data),
    staleTime: 15_000,
    enabled: !!pipelineId,
    refetchInterval: (query) => {
      const latest = query.state.data?.[0];
      return latest?.status === 'RUNNING' ? 5000 : false;
    },
  });

  const isLoading = pipelineLoading || execLoading;
  const latestExecution = executions?.[0];
  const status = latestExecution?.status as ExecutionStatus | undefined;

  if (isLoading) {
    return (
      <WidgetShell title="파이프라인 불러오는 중..." icon="⚙️" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">로딩 중...</div>
      </WidgetShell>
    );
  }

  if (pipelineError || !pipeline) {
    return (
      <WidgetShell title="파이프라인을 찾을 수 없음" icon="⚙️" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-destructive">데이터를 불러올 수 없습니다</div>
      </WidgetShell>
    );
  }

  return (
    <WidgetShell
      title={pipeline.name}
      icon="⚙️"
      navigateTo={`/pipelines/${pipeline.id}`}
      onNavigate={onNavigate}
      displayMode={displayMode}
    >
      {/* Status badge */}
      <div className="flex items-center gap-2 border-b border-border px-3 py-2">
        {status ? (
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CLASS[status]}`}>
            {STATUS_LABEL[status]}
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">실행 기록 없음</span>
        )}
      </div>

      {/* Step list */}
      {pipeline.steps.length > 0 && (
        <div className="divide-y divide-border/50">
          {pipeline.steps.map((step) => (
            <div key={step.id} className="flex items-center gap-2 px-3 py-2 hover:bg-muted/20 transition-colors duration-150">
              <span className="h-2 w-2 shrink-0 rounded-full bg-muted-foreground" />
              <span className="flex-1 truncate text-xs">{step.name}</span>
              <span className="text-xs text-muted-foreground">{step.scriptType}</span>
            </div>
          ))}
        </div>
      )}

      {/* Footer */}
      {latestExecution && (
        <div className="border-t border-border px-3 py-1.5 text-xs text-muted-foreground">
          마지막 실행: {formatRelativeTime(latestExecution.createdAt)}
          {latestExecution.startedAt && (
            <span> · 총 {formatDuration(latestExecution.startedAt, latestExecution.completedAt)}</span>
          )}
        </div>
      )}
    </WidgetShell>
  );
}
