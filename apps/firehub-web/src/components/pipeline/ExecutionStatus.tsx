import { useState, useEffect } from 'react';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card } from '../ui/card';
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { ExecutionDetailResponse } from '../../types/pipeline';

interface ExecutionStatusProps {
  execution: ExecutionDetailResponse;
}

export function ExecutionStatus({ execution }: ExecutionStatusProps) {
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set());
  const [currentTime, setCurrentTime] = useState<number>(() => Date.now());

  useEffect(() => {
    const isRunning = execution.status === 'RUNNING' ||
                      execution.stepExecutions.some(s => s.status === 'RUNNING');
    if (!isRunning) return;

    const interval = setInterval(() => {
      setCurrentTime(Date.now());
    }, 1000);

    return () => clearInterval(interval);
  }, [execution.status, execution.stepExecutions]);

  const toggleStep = (stepId: number) => {
    setExpandedSteps(prev => {
      const next = new Set(prev);
      if (next.has(stepId)) {
        next.delete(stepId);
      } else {
        next.add(stepId);
      }
      return next;
    });
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
      PENDING: 'outline',
      RUNNING: 'default',
      COMPLETED: 'secondary',
      FAILED: 'destructive',
      SKIPPED: 'outline',
      CANCELLED: 'outline',
    };
    const labels: Record<string, string> = {
      PENDING: '대기',
      RUNNING: '실행중',
      COMPLETED: '완료',
      FAILED: '실패',
      SKIPPED: '건너뜀',
      CANCELLED: '취소됨',
    };
    return <Badge variant={variants[status] || 'outline'}>{labels[status] || status}</Badge>;
  };

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString('ko-KR');
  };

  const formatDuration = (start: string | null, end: string | null) => {
    if (!start) return '-';
    const startTime = new Date(start).getTime();
    const endTime = end ? new Date(end).getTime() : currentTime;
    const duration = Math.floor((endTime - startTime) / 1000);
    if (duration < 60) return `${duration}초`;
    const minutes = Math.floor(duration / 60);
    const seconds = duration % 60;
    return `${minutes}분 ${seconds}초`;
  };

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold">실행 상태</h3>
            {getStatusBadge(execution.status)}
          </div>
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <span className="text-muted-foreground">실행자:</span> {execution.executedBy}
            </div>
            <div>
              <span className="text-muted-foreground">시작:</span> {formatDate(execution.startedAt)}
            </div>
            <div>
              <span className="text-muted-foreground">완료:</span> {formatDate(execution.completedAt)}
            </div>
            <div>
              <span className="text-muted-foreground">소요시간:</span> {formatDuration(execution.startedAt, execution.completedAt)}
            </div>
          </div>
        </div>
      </Card>

      <div className="space-y-2">
        <h3 className="text-lg font-semibold">스텝 실행 상태</h3>
        {execution.stepExecutions.map((step) => (
          <Card key={step.id} className="p-4">
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => toggleStep(step.id)}
                    className="h-6 w-6 p-0"
                  >
                    {expandedSteps.has(step.id) ? (
                      <ChevronDown className="h-4 w-4" />
                    ) : (
                      <ChevronRight className="h-4 w-4" />
                    )}
                  </Button>
                  <span className="font-medium">{step.stepName}</span>
                </div>
                {getStatusBadge(step.status)}
              </div>

              <div className="grid grid-cols-3 gap-4 text-sm ml-8">
                <div>
                  <span className="text-muted-foreground">출력 행 수:</span> {step.outputRows ?? '-'}
                </div>
                <div>
                  <span className="text-muted-foreground">시작:</span> {formatDate(step.startedAt)}
                </div>
                <div>
                  <span className="text-muted-foreground">소요시간:</span> {formatDuration(step.startedAt, step.completedAt)}
                </div>
              </div>

              {expandedSteps.has(step.id) && (
                <div className="ml-8 space-y-2 mt-2">
                  {step.log && (
                    <div className="space-y-1">
                      <span className="text-sm font-medium">로그:</span>
                      <pre className="text-xs bg-muted p-2 rounded overflow-x-auto">{step.log}</pre>
                    </div>
                  )}
                  {step.errorMessage && (
                    <div className="space-y-1">
                      <span className="text-sm font-medium text-destructive">에러:</span>
                      <pre className="text-xs bg-destructive/10 text-destructive p-2 rounded overflow-x-auto">{step.errorMessage}</pre>
                    </div>
                  )}
                </div>
              )}
            </div>
          </Card>
        ))}
      </div>
    </div>
  );
}
