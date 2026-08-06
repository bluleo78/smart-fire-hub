import { CheckCircle2, Clock,Loader2, XCircle } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';
import type { ImportProgress } from '@/hooks/queries/useImportProgress';
import { getFailedPhase } from '@/hooks/queries/useImportProgress';
import type { ValidationErrorDetail } from '@/types/dataImport';

import { ValidationErrorTable } from './ValidationErrorTable';

interface ImportProgressViewProps {
  progress: ImportProgress | null;
  onClose: () => void;
  /** FAILED 시 이력(ImportResponse)에서 조회해 전달하는 검증 오류 상세 — 없으면 테이블을 렌더하지 않는다 */
  failedErrors?: ValidationErrorDetail[] | null;
}

type Stage = ImportProgress['stage'];

const STAGES: Stage[] = ['PARSING', 'VALIDATING', 'INSERTING', 'COMPLETED'];

const STAGE_LABELS: Record<Stage, string> = {
  PENDING: '대기',
  PARSING: '파싱',
  VALIDATING: '검증',
  INSERTING: '삽입',
  COMPLETED: '완료',
  FAILED: '실패',
};

// FAILED는 STAGES 목록에 없는 종단 상태라 그 자체로는 "몇 번째 단계"인지 알 수 없다.
// 백엔드가 실패 시점의 progress를 보존해서 보내주므로(getFailedPhase), 이를 근거로
// 실제로 실패한 단계(VALIDATING 또는 INSERTING)를 역산해 스텝퍼에 정확히 표기한다.
function getStageIndex(stage: Stage, failedProgress?: number): number {
  if (stage === 'FAILED') {
    return STAGES.indexOf(getFailedPhase(failedProgress ?? 0));
  }
  return STAGES.indexOf(stage);
}

interface StepIndicatorProps {
  stage: Stage;
  label: string;
  currentStage: Stage;
  failedProgress?: number;
}

function StepIndicator({ stage, label, currentStage, failedProgress }: StepIndicatorProps) {
  const currentIdx = getStageIndex(currentStage, failedProgress);
  const stageIdx = getStageIndex(stage, failedProgress);
  const isFailed = currentStage === 'FAILED';

  let state: 'done' | 'active' | 'pending';
  if (currentStage === 'COMPLETED') {
    state = 'done';
  } else if (isFailed) {
    state = stageIdx < currentIdx ? 'done' : stageIdx === currentIdx ? 'active' : 'pending';
  } else if (stageIdx < currentIdx) {
    state = 'done';
  } else if (stageIdx === currentIdx) {
    state = 'active';
  } else {
    state = 'pending';
  }

  return (
    <div className="flex flex-col items-center gap-1">
      <div
        className={[
          'w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0',
          state === 'done' ? 'bg-success-subtle text-success' : '',
          state === 'active' && !isFailed ? 'bg-info-subtle text-info' : '',
          state === 'active' && isFailed ? 'bg-destructive/10 text-destructive' : '',
          state === 'pending' ? 'bg-muted text-muted-foreground' : '',
        ].join(' ')}
      >
        {state === 'done' && <CheckCircle2 className="w-5 h-5" />}
        {state === 'active' && !isFailed && <Loader2 className="w-4 h-4 animate-spin" />}
        {state === 'active' && isFailed && <XCircle className="w-5 h-5" />}
        {state === 'pending' && <Clock className="w-4 h-4" />}
      </div>
      <span className={[
        'text-xs',
        state === 'done' ? 'text-success font-medium' : '',
        state === 'active' && !isFailed ? 'text-info font-medium' : '',
        state === 'active' && isFailed ? 'text-destructive font-medium' : '',
        state === 'pending' ? 'text-muted-foreground' : '',
      ].join(' ')}>
        {label}
      </span>
    </div>
  );
}

interface ProgressBarProps {
  value: number;
}

function ProgressBar({ value }: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, value));
  return (
    <div className="w-full bg-muted rounded-full h-2.5 overflow-hidden">
      <div
        className="bg-info h-2.5 rounded-full transition-all duration-300"
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}

export function ImportProgressView({ progress, onClose, failedErrors }: ImportProgressViewProps) {
  const stage = progress?.stage ?? 'PENDING';
  const pct = progress?.progress ?? 0;
  const isTerminal = stage === 'COMPLETED' || stage === 'FAILED';
  const isPending = stage === 'PENDING';
  // VALIDATING은 분모(totalRows)가 없어 진행률을 계산할 수 없다 — % 바 대신 스피너로 표시(indeterminate)
  const isValidatingIndeterminate = stage === 'VALIDATING';

  const handleClose = () => {
    if (!isTerminal && !isPending) {
      const confirmed = window.confirm('임포트가 백그라운드에서 계속 실행됩니다. 닫으시겠습니까?');
      if (!confirmed) return;
    }
    onClose();
  };

  if (isPending || !progress) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-10">
        <Loader2 className="w-10 h-10 animate-spin text-info" />
        <p className="text-sm text-muted-foreground">작업 대기 중...</p>
        <Button variant="outline" size="sm" onClick={onClose}>
          닫기
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6 py-2">
      {/* Stage stepper */}
      <div className="flex items-start justify-between gap-1">
        {STAGES.map((s, idx) => (
          <div key={s} className="flex items-center flex-1">
            <StepIndicator stage={s} label={STAGE_LABELS[s]} currentStage={stage} failedProgress={pct} />
            {idx < STAGES.length - 1 && (
              <div className="flex-1 h-px bg-border mx-1 mt-[-12px]" />
            )}
          </div>
        ))}
      </div>

      {/* Progress bar — VALIDATING은 분모가 없어 스피너(indeterminate), INSERTING은 % 바 */}
      {!isTerminal && isValidatingIndeterminate && (
        <div className="flex flex-col items-center gap-2 py-2">
          <Loader2 className="w-6 h-6 animate-spin text-info" />
          <p className="text-sm text-muted-foreground">검증 중…</p>
          {progress.processedRows !== undefined && (
            <p className="text-xs text-muted-foreground">{progress.processedRows.toLocaleString()}행 검사됨</p>
          )}
        </div>
      )}
      {!isTerminal && !isValidatingIndeterminate && (
        <div className="space-y-1.5">
          <ProgressBar value={pct} />
          <div className="flex justify-end">
            <span className="text-xs text-muted-foreground">{pct}%</span>
          </div>
        </div>
      )}

      {/* Status message */}
      {stage === 'COMPLETED' && (
        <InlineBanner variant="success" icon={<CheckCircle2 />} title="임포트 완료">
          <div className="text-muted-foreground space-y-1">
            {progress.totalRows !== undefined && (
              <p>전체: {progress.totalRows.toLocaleString()}행</p>
            )}
            {progress.successRows !== undefined && (
              <p>성공: <span className="text-success font-medium">{progress.successRows.toLocaleString()}행</span></p>
            )}
            {progress.errorRows !== undefined && progress.errorRows > 0 && (
              <p>오류: <span className="text-destructive font-medium">{progress.errorRows.toLocaleString()}행</span></p>
            )}
          </div>
        </InlineBanner>
      )}

      {stage === 'FAILED' && (
        <div className="rounded-md border border-destructive/20 bg-destructive/5 p-4 space-y-2">
          <div className="flex items-center gap-2 text-destructive font-medium">
            <XCircle className="w-5 h-5" />
            <span>임포트 실패</span>
          </div>
          {progress.errorMessage && (
            <p className="text-sm text-destructive pl-7">{progress.errorMessage}</p>
          )}
          {/* 실패 시점의 상세 오류(행/컬럼/값/오류)는 진행 이벤트엔 없고 이력(ImportResponse)에서 조회해 전달받는다 */}
          {failedErrors && failedErrors.length > 0 && (
            <div className="pl-7">
              <ValidationErrorTable errors={failedErrors} />
            </div>
          )}
        </div>
      )}

      {!isTerminal && !isValidatingIndeterminate && (
        <div className="space-y-1">
          <p className="text-sm text-center text-muted-foreground">
            {stage === 'INSERTING' && progress.processedRows !== undefined && progress.totalRows !== undefined
              ? `${progress.processedRows.toLocaleString()} / ${progress.totalRows.toLocaleString()} 행 처리 중...`
              : progress.message || `${STAGE_LABELS[stage]} 중...`}
          </p>
        </div>
      )}

      {/* Action button */}
      <div className="flex justify-end pt-2 border-t">
        <Button
          variant={isTerminal ? 'default' : 'outline'}
          size="sm"
          onClick={handleClose}
        >
          {isTerminal ? '확인' : '닫기'}
        </Button>
      </div>
    </div>
  );
}
