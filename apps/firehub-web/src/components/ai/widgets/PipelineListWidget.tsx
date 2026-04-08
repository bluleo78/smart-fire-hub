import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface PipelineListItem {
  id: number;
  name: string;
  isActive?: boolean;
  stepCount?: number;
  triggerCount?: number;
  lastStatus?: string;
}

interface ShowPipelineListInput {
  items: PipelineListItem[];
}

/* 실행 상태별 도트 색상 — 시맨틱 토큰 사용 */
const STATUS_DOT_CLASS: Record<string, string> = {
  COMPLETED: 'bg-success',
  FAILED: 'bg-destructive',
  RUNNING: 'bg-info animate-pulse',
  PENDING: 'bg-warning',
  CANCELLED: 'bg-muted-foreground',
};

export default function PipelineListWidget({ input, onNavigate, displayMode }: WidgetProps<ShowPipelineListInput>) {
  const items = input.items ?? [];

  return (
    <WidgetShell
      title={`파이프라인 ${items.length}건`}
      icon="⚙️"
      displayMode={displayMode}
      onNavigate={onNavigate}
    >
      {items.length === 0 ? (
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">파이프라인이 없습니다.</div>
      ) : (
        <div className="divide-y divide-border/50">
          {items.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate?.(`/pipelines/${item.id}`)}
              className="w-full text-left px-3 py-2 hover:bg-muted/20 transition-colors duration-150"
            >
              <div className="flex items-center gap-2">
                <span className="flex-1 truncate text-sm font-medium">{item.name}</span>
                <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${item.isActive ? 'bg-success/10 text-success' : 'bg-muted text-muted-foreground'}`}>
                  {item.isActive ? '활성' : '비활성'}
                </span>
              </div>
              <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                {item.stepCount != null && <span>스텝 {item.stepCount}개</span>}
                {item.triggerCount != null && <span>트리거 {item.triggerCount}개</span>}
                {item.lastStatus && (
                  <span className="flex items-center gap-1">
                    <span className={`h-1.5 w-1.5 rounded-full ${STATUS_DOT_CLASS[item.lastStatus] ?? 'bg-muted-foreground'}`} />
                    <span>{item.lastStatus === 'COMPLETED' ? '성공' : item.lastStatus === 'FAILED' ? '실패' : item.lastStatus}</span>
                  </span>
                )}
              </div>
            </button>
          ))}
        </div>
      )}
    </WidgetShell>
  );
}
