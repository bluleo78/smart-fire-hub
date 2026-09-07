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
  if (totalSeconds === 0) return '< 1s';
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
            {/* 에러 섹션: 사용자 친화적 안내 + 기술적 원문을 스크롤 영역에 표시 */}
            <div className="space-y-2">
              <p className="text-muted-foreground text-xs font-medium">오류 상세</p>
              {/* 일반 사용자를 위한 친화적 안내 메시지 */}
              <p className="text-xs text-muted-foreground">
                스텝 실행 중 오류가 발생했습니다. 아래 오류 정보를 참고하여 스텝 설정을 확인하세요.
              </p>
              {/* 개발자 디버깅용 기술적 원문 — 최대 높이 제한 + 스크롤 */}
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

/**
 * 파이프라인 실행 레벨 오류 메시지 표시 영역.
 *
 * 스텝 실행 레코드가 하나도 생성되기 전에 발생한 최상위 예외(토폴로지 정렬 실패, DB 오류 등)는
 * 스텝별 errorMessage에는 남지 않고 execution.errorMessage에만 남는다(#517). 이 값이 있으면
 * "스텝 실행 전 실패"임을 명확히 보여준다.
 */
function ExecutionErrorMessage({ errorMessage }: { errorMessage: string }) {
  return (
    <>
      <Separator />
      <div className="space-y-2">
        <p className="text-muted-foreground text-xs font-medium">오류 상세</p>
        <p className="text-xs text-muted-foreground">
          스텝이 실행되기 전 파이프라인 실행 자체가 실패했습니다. 아래 오류 정보를 참고하세요.
        </p>
        <pre className="bg-destructive/10 text-destructive p-3 rounded text-xs overflow-auto max-h-[200px] whitespace-pre-wrap break-words">
          {errorMessage}
        </pre>
      </div>
    </>
  );
}

/**
 * 헤더에는 선택한 스텝명이 표시되지만 실제로는 해당 스텝의 실행 레코드가 존재하지 않는 경우
 * (예: 스텝 실행 전 파이프라인 전체가 실패)를 위한 빈 상태 컴포넌트(#517).
 * 기존에는 이 경우도 ExecutionSummary가 그대로 렌더링되어 "스텝 상세를 보고 있다"는 착각을 줬다.
 */
function StepNotExecuted({ execution }: { execution: ExecutionDetailResponse }) {
  return (
    <div className="flex-1 min-h-0">
      <ScrollArea className="h-full">
        <div className="p-4 space-y-3 text-sm">
          <p className="text-muted-foreground text-xs">
            이 스텝은 실행되지 않았습니다. 스텝이 실행되기 전 파이프라인 실행이 중단됐을 수 있습니다.
          </p>
          {execution.errorMessage && <ExecutionErrorMessage errorMessage={execution.errorMessage} />}
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

          {execution.errorMessage && <ExecutionErrorMessage errorMessage={execution.errorMessage} />}

          <Separator />

          <div className="space-y-2">
            <p className="text-muted-foreground text-xs font-medium">스텝 현황</p>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-foreground" />
                <span>완료 {completed}/{total}</span>
              </div>
              {failed > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-muted-foreground" />
                  <span>실패 {failed}</span>
                </div>
              )}
              {running > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-muted-foreground/80" />
                  <span>실행 중 {running}</span>
                </div>
              )}
              {pending > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-muted-foreground/40" />
                  <span>대기 {pending}</span>
                </div>
              )}
              {skipped > 0 && (
                <div className="flex items-center gap-1.5">
                  <span className="h-2 w-2 rounded-full bg-muted-foreground/60" />
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
  // 스텝을 선택하지 않은 경우(null)와, 선택했지만 해당 스텝의 실행 레코드가 아예 없는 경우
  // (undefined — 스텝 실행 전 파이프라인이 실패한 경우 등)를 명확히 구분한다(#517).
  // 이전에는 둘 다 "요약 화면"으로 뭉뚱그려져, 헤더는 "스텝: {name}"인데 본문은 전체 요약이
  // 반복 표시되는 모순된 UX였다.
  const step = execution.stepExecutions.find(se => se.stepName === selectedStepName);

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

      {selectedStepName === null ? (
        <ExecutionSummary execution={execution} />
      ) : step ? (
        <StepDetails step={step} />
      ) : (
        <StepNotExecuted execution={execution} />
      )}
    </div>
  );
}
