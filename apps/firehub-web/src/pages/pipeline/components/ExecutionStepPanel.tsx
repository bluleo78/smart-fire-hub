import { X } from 'lucide-react';
import { useEffect, useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import type { ExecutionDetailResponse, StepExecutionResponse } from '@/types/pipeline';

interface ExecutionStepPanelProps {
  execution: ExecutionDetailResponse;
  selectedStepName: string | null;
  onClose: () => void;
}

function formatDuration(startedAt: string | null, completedAt: string | null, elapsed?: number): string {
  if (!startedAt) return '-';
  const totalSeconds = completedAt
    ? Math.floor((new Date(completedAt).getTime() - new Date(startedAt).getTime()) / 1000)
    : elapsed ?? 0;
  if (totalSeconds < 0) return '-';
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

function StepDetails({
  step,
}: {
  step: StepExecutionResponse;
}) {
  const isRunning = step.status === 'RUNNING' || step.status === 'PENDING';
  const [elapsed, setElapsed] = useState<number>(0);

  useEffect(() => {
    if (!isRunning || !step.startedAt) return;
    const start = new Date(step.startedAt).getTime();
    const update = () => setElapsed(Math.floor((Date.now() - start) / 1000));
    update();
    const id = setInterval(update, 1000);
    return () => clearInterval(id);
  }, [isRunning, step.startedAt]);

  return (
    <div className="flex-1 min-h-0">
      <ScrollArea className="h-full">
        <div className="p-4 space-y-3 text-sm">
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground w-16 shrink-0">상태</span>
          <Badge variant={getStatusBadgeVariant(step.status)}>
            {getStatusLabel(step.status)}
          </Badge>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-muted-foreground w-16 shrink-0">시작</span>
          <span>{formatDate(step.startedAt)}</span>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-muted-foreground w-16 shrink-0">소요</span>
          <span>
            {isRunning
              ? formatDuration(step.startedAt, null, elapsed)
              : formatDuration(step.startedAt, step.completedAt)}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-muted-foreground w-16 shrink-0">출력행</span>
          <span>{step.outputRows != null ? step.outputRows.toLocaleString() : '-'}</span>
        </div>

        {step.errorMessage && (
          <>
            <Separator />
            <div className="space-y-1">
              <p className="text-muted-foreground text-xs font-medium">에러</p>
              <pre className="bg-destructive/10 text-destructive p-3 rounded text-xs overflow-auto max-h-[200px] whitespace-pre-wrap break-words">
                {step.errorMessage}
              </pre>
            </div>
          </>
        )}

        {step.log && (
          <>
            <Separator />
            <div className="space-y-1">
              <p className="text-muted-foreground text-xs font-medium">로그</p>
              <pre className="bg-muted p-3 rounded text-xs overflow-auto max-h-[200px] whitespace-pre-wrap break-words">
                {step.log}
              </pre>
            </div>
          </>
        )}
        </div>
      </ScrollArea>
    </div>
  );
}

function ExecutionSummary({ execution }: { execution: ExecutionDetailResponse }) {
  const total = execution.stepExecutions.length;
  const completed = execution.stepExecutions.filter(s => s.status === 'COMPLETED').length;
  const failed = execution.stepExecutions.filter(s => s.status === 'FAILED').length;
  const running = execution.stepExecutions.filter(s => s.status === 'RUNNING').length;
  const pending = execution.stepExecutions.filter(s => s.status === 'PENDING').length;
  const skipped = execution.stepExecutions.filter(s => s.status === 'SKIPPED').length;

  return (
    <div className="flex-1 min-h-0">
      <ScrollArea className="h-full">
        <div className="p-4 space-y-3 text-sm">
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-16 shrink-0">상태</span>
            <Badge variant={getStatusBadgeVariant(execution.status)}>
              {getStatusLabel(execution.status)}
            </Badge>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-16 shrink-0">실행자</span>
            <span>{execution.executedBy}</span>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-16 shrink-0">시작</span>
            <span>{formatDate(execution.startedAt)}</span>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-16 shrink-0">소요</span>
            <span>{formatDuration(execution.startedAt, execution.completedAt)}</span>
          </div>

          <Separator />

          <div className="space-y-2">
            <p className="text-muted-foreground text-xs font-medium">스텝 현황</p>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-gray-900" />
                <span>완료 {completed}/{total}</span>
              </div>
              {failed > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-gray-500" />
                  <span>실패 {failed}</span>
                </div>
              )}
              {running > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-gray-700" />
                  <span>실행 중 {running}</span>
                </div>
              )}
              {pending > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-gray-300" />
                  <span>대기 {pending}</span>
                </div>
              )}
              {skipped > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-gray-400" />
                  <span>건너뜀 {skipped}</span>
                </div>
              )}
            </div>
          </div>

          <Separator />

          <p className="text-muted-foreground text-xs">
            DAG에서 스텝을 클릭하면 상세 정보를 확인할 수 있습니다.
          </p>
        </div>
      </ScrollArea>
    </div>
  );
}

export function ExecutionStepPanel({
  execution,
  selectedStepName,
  onClose,
}: ExecutionStepPanelProps) {
  const step = execution.stepExecutions.find(se => se.stepName === selectedStepName) ?? null;

  return (
    <div className="w-[400px] border-l h-full flex flex-col overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b shrink-0">
        <span className="text-sm font-medium truncate">
          {selectedStepName ? `스텝: ${selectedStepName}` : '실행 정보'}
        </span>
        {selectedStepName && (
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onClose}>
            <X className="h-4 w-4" />
          </Button>
        )}
      </div>

      {step === null ? (
        <ExecutionSummary execution={execution} />
      ) : (
        <StepDetails step={step} />
      )}
    </div>
  );
}
