import { X } from 'lucide-react';
import { useState } from 'react';

import { DurationText } from '@/components/pipeline/DurationText';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { RestrictedNotice } from '@/components/ui/restricted-notice';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import type { ExecutionDetailResponse, StepExecutionResponse } from '@/types/pipeline';

import { deletedStepExecutions, findStepExecution } from '../utils/step-execution';

interface ExecutionStepPanelProps {
  execution: ExecutionDetailResponse;
  /**
   * DAG 에서 선택한 스텝(없으면 요약 화면). name 은 헤더 표시용, id 는 실행 기록을 찾는 서버 스텝 id 다(WD-108) —
   * 저장 전 새 스텝은 id 가 없다.
   */
  selectedStep?: { id?: number; name: string };
  onClose: () => void;
}

/**
 * 실행 기록 오류 표시 — 원문이 가려졌으면(WD-27, 서버 errorMasked) 제한 상태 블록, 아니면 빨간 원문 블록.
 *
 * 왜 나누는가: 빨간 고정폭 <pre> 는 "기계가 뱉은 오류 원문"의 시각 언어라, 권한 안내를 거기에 넣으면 또 하나의 시스템 오류처럼 읽혀
 * 재실행·문의로 이어진다(디자인 검토). 실패 신호는 상태 배지와 안내 문장이 이미 담당하므로, 가림 문구는 다른 제한 상태(task-3 "열람 권한
 * 없음")와 같은 muted + Lock 으로 보여 준다 — 오류색·role=alert·재시도 없음.
 */
function ExecutionErrorBody({ errorMessage, errorMasked }: { errorMessage: string; errorMasked?: boolean }) {
  if (errorMasked) {
    return <RestrictedNotice message={errorMessage} />;
  }
  // 개발자 디버깅용 기술적 원문 — 최대 높이 제한 + 스크롤
  return (
    <pre className="bg-destructive/10 text-destructive p-3 rounded text-xs overflow-auto max-h-[200px] whitespace-pre-wrap break-words">
      {errorMessage}
    </pre>
  );
}

/**
 * 건너뜀(SKIPPED) 스텝 안내(WD-111) — 오류가 아니라 실행하지 않은 이유이므로 오류 문구·빨간 원문 블록을 쓰지 않고 중립 문장 하나로 보여 준다.
 *
 * 저장된 문구는 쓰지 않는다: 옛 실행 기록에는 영어 "Dependency failed or skipped" 가 남아 있고(마이그레이션으로 바꾸지 않음), 원문이 가려진
 * 조회자(WD-27)에게는 가림 문구가 온다. 어느 쪽이든 SKIPPED 의 뜻은 하나라 상태만으로 같은 안내를 낸다.
 *
 * 첫 문장은 서버 `PipelineSqlErrorMessages.SKIPPED_MESSAGE`(firehub-api)와 같다 — 문구를 바꾸면 함께 고칠 것.
 */
function SkippedStepNotice() {
  return (
    <>
      <Separator />
      <div className="space-y-2" data-testid="skipped-step-notice">
        <p className="text-muted-foreground text-xs font-medium">건너뛴 이유</p>
        <p className="text-xs text-muted-foreground">
          선행 스텝이 실패하거나 건너뛰어져 이 스텝은 실행하지 않았습니다. 선행 스텝의 오류를 해결한 뒤 다시 실행하세요.
        </p>
      </div>
    </>
  );
}

function StepDetails({
  step,
}: {
  step: StepExecutionResponse;
}) {
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
            <DurationText
              startedAt={step.startedAt}
              completedAt={step.completedAt}
              running={step.status === 'RUNNING'}
            />
          </span>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-muted-foreground w-16 shrink-0">출력행</span>
          <span>{step.outputRows != null ? step.outputRows.toLocaleString() : '-'}</span>
        </div>

        {step.status === 'SKIPPED' ? (
          <SkippedStepNotice />
        ) : step.errorMessage && (
          <>
            <Separator />
            {/* 에러 섹션: 사용자 친화적 안내 + 기술적 원문을 스크롤 영역에 표시 */}
            <div className="space-y-2">
              <p className="text-muted-foreground text-xs font-medium">오류 상세</p>
              {/* 일반 사용자를 위한 친화적 안내 메시지 — 원문이 가려졌으면(WD-27) 아래에 참고할 오류 정보가 없으므로
                  "아래 오류 정보를 참고…" 안내는 빼고 실패 사실만 알린다(실행 단위 오류와 같은 규칙) */}
              <p className="text-xs text-muted-foreground">
                {step.errorMasked
                  ? '스텝 실행 중 오류가 발생했습니다.'
                  : '스텝 실행 중 오류가 발생했습니다. 아래 오류 정보를 참고하여 스텝 설정을 확인하세요.'}
              </p>
              <ExecutionErrorBody errorMessage={step.errorMessage} errorMasked={step.errorMasked} />
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
function ExecutionErrorMessage({ errorMessage, errorMasked }: { errorMessage: string; errorMasked?: boolean }) {
  return (
    <>
      <Separator />
      <div className="space-y-2">
        <p className="text-muted-foreground text-xs font-medium">오류 상세</p>
        {/* 원문이 가려졌으면(WD-27) 참고할 오류 정보가 없으므로 "아래 오류 정보를 참고하세요" 는 빼고 실패 사실만 알린다 */}
        <p className="text-xs text-muted-foreground">
          {errorMasked
            ? '스텝이 실행되기 전 파이프라인 실행 자체가 실패했습니다.'
            : '스텝이 실행되기 전 파이프라인 실행 자체가 실패했습니다. 아래 오류 정보를 참고하세요.'}
        </p>
        <ExecutionErrorBody errorMessage={errorMessage} errorMasked={errorMasked} />
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
            이 스텝은 실행되지 않았습니다. 스텝이 실행되기 전 파이프라인 실행이 중단됐거나, 이 실행 이후에 추가된 스텝일 수 있습니다.
          </p>
          {execution.errorMessage && (
            <ExecutionErrorMessage errorMessage={execution.errorMessage} errorMasked={execution.errorMasked} />
          )}
        </div>
      </ScrollArea>
    </div>
  );
}

/**
 * 실행 이후 파이프라인 정의에서 삭제된 스텝의 기록 목록(WD-108).
 *
 * DAG 는 현재 정의의 스텝만 그리므로, 이 실행 때 있었지만 지금은 지워진 스텝은 노드가 없다. 그 기록(상태·오류·출력행)이
 * 사라진 것처럼 보이지 않도록 요약 화면에 이름·「삭제된 스텝」 표시·상태와 함께 나열하고, 누르면 상세를 보여 준다.
 */
function DeletedStepList({
  steps,
  onSelect,
}: {
  steps: StepExecutionResponse[];
  onSelect: (stepExecutionId: number) => void;
}) {
  if (steps.length === 0) return null;
  return (
    <>
      <Separator />
      <div className="space-y-2">
        <p className="text-muted-foreground text-xs font-medium">삭제된 스텝</p>
        <p className="text-xs text-muted-foreground">
          이 실행 이후 파이프라인에서 삭제된 스텝입니다. 실행 당시 기록은 그대로 남아 있습니다.
        </p>
        <ul className="space-y-1">
          {steps.map((se) => (
            <li key={se.id}>
              <button
                type="button"
                className="w-full flex items-center justify-between gap-2 rounded px-2 py-1.5 text-left text-xs hover:bg-muted"
                onClick={() => onSelect(se.id)}
              >
                <span className="flex items-center gap-1.5 min-w-0">
                  <span className="truncate">{se.stepName}</span>
                  <Badge variant="outline" className="shrink-0">삭제된 스텝</Badge>
                </span>
                <Badge variant={getStatusBadgeVariant(se.status)} className="shrink-0">
                  {getStatusLabel(se.status)}
                </Badge>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </>
  );
}

function ExecutionSummary({
  execution,
  onSelectDeleted,
}: {
  execution: ExecutionDetailResponse;
  onSelectDeleted: (stepExecutionId: number) => void;
}) {
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
            <span>
              <DurationText
                startedAt={execution.startedAt}
                completedAt={execution.completedAt}
                running={execution.status === 'RUNNING'}
              />
            </span>
          </div>

          {execution.errorMessage && (
            <ExecutionErrorMessage errorMessage={execution.errorMessage} errorMasked={execution.errorMasked} />
          )}

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

          <DeletedStepList steps={deletedStepExecutions(execution.stepExecutions)} onSelect={onSelectDeleted} />

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
  selectedStep,
  onClose,
}: ExecutionStepPanelProps) {
  // 스텝을 선택하지 않은 경우(null)와, 선택했지만 해당 스텝의 실행 레코드가 아예 없는 경우
  // (undefined — 스텝 실행 전 파이프라인이 실패했거나, 이 실행 이후 새로 추가된 스텝)를 명확히 구분한다(#517).
  // 이전에는 둘 다 "요약 화면"으로 뭉뚱그려져, 헤더는 "스텝: {name}"인데 본문은 전체 요약이
  // 반복 표시되는 모순된 UX였다. 기록은 스텝 id 로 찾는다(WD-108 — 이름이 바뀌어도 같은 스텝, 삭제된 동명 스텝과 섞이지 않음).
  const step = findStepExecution(execution.stepExecutions, selectedStep?.id);

  // 요약의 「삭제된 스텝」 목록에서 고른 기록(DAG 노드가 없어 편집기 선택 상태로는 표현할 수 없다). DAG 선택이 우선한다.
  const [deletedSelectionId, setDeletedSelectionId] = useState<number | null>(null);
  const deletedStep =
    selectedStep === undefined && deletedSelectionId !== null
      ? execution.stepExecutions.find((se) => se.id === deletedSelectionId && se.stepId === null)
      : undefined;

  const handleClose = () => {
    setDeletedSelectionId(null);
    onClose();
  };

  return (
    <div className="w-[400px] border-l h-full flex flex-col overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b shrink-0">
        <span className="text-sm font-medium truncate">
          {selectedStep?.name
            ? `스텝: ${selectedStep.name}`
            : deletedStep
              ? `스텝: ${deletedStep.stepName} (삭제된 스텝)`
              : '실행 정보'}
        </span>
        {(selectedStep?.name || deletedStep) && (
          <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="스텝 상세 닫기" onClick={handleClose}>
            <X className="h-4 w-4" />
          </Button>
        )}
      </div>

      {selectedStep === undefined ? (
        deletedStep ? (
          <StepDetails step={deletedStep} />
        ) : (
          <ExecutionSummary execution={execution} onSelectDeleted={setDeletedSelectionId} />
        )
      ) : step ? (
        <StepDetails step={step} />
      ) : (
        <StepNotExecuted execution={execution} />
      )}
    </div>
  );
}
