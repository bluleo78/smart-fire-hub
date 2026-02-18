import { CheckCircle2, XCircle, Loader2, Clock } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { ImportProgress } from '@/hooks/queries/useImportProgress';

interface ImportProgressViewProps {
  progress: ImportProgress | null;
  onClose: () => void;
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

function getStageIndex(stage: Stage): number {
  return STAGES.indexOf(stage === 'FAILED' ? 'INSERTING' : stage);
}

interface StepIndicatorProps {
  stage: Stage;
  label: string;
  currentStage: Stage;
}

function StepIndicator({ stage, label, currentStage }: StepIndicatorProps) {
  const currentIdx = getStageIndex(currentStage);
  const stageIdx = getStageIndex(stage);
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
          state === 'done' ? 'bg-green-100 text-green-600' : '',
          state === 'active' && !isFailed ? 'bg-blue-100 text-blue-600' : '',
          state === 'active' && isFailed ? 'bg-red-100 text-red-600' : '',
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
        state === 'done' ? 'text-green-600 font-medium' : '',
        state === 'active' && !isFailed ? 'text-blue-600 font-medium' : '',
        state === 'active' && isFailed ? 'text-red-600 font-medium' : '',
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
        className="bg-blue-500 h-2.5 rounded-full transition-all duration-300"
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}

export function ImportProgressView({ progress, onClose }: ImportProgressViewProps) {
  const stage = progress?.stage ?? 'PENDING';
  const pct = progress?.progress ?? 0;
  const isTerminal = stage === 'COMPLETED' || stage === 'FAILED';
  const isPending = stage === 'PENDING';

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
        <Loader2 className="w-10 h-10 animate-spin text-blue-500" />
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
            <StepIndicator stage={s} label={STAGE_LABELS[s]} currentStage={stage} />
            {idx < STAGES.length - 1 && (
              <div className="flex-1 h-px bg-border mx-1 mt-[-12px]" />
            )}
          </div>
        ))}
      </div>

      {/* Progress bar (only when not terminal) */}
      {!isTerminal && (
        <div className="space-y-1.5">
          <ProgressBar value={pct} />
          <div className="flex justify-end">
            <span className="text-xs text-muted-foreground">{pct}%</span>
          </div>
        </div>
      )}

      {/* Status message */}
      {stage === 'COMPLETED' && (
        <div className="rounded-md border border-green-200 bg-green-50 p-4 space-y-2">
          <div className="flex items-center gap-2 text-green-700 font-medium">
            <CheckCircle2 className="w-5 h-5" />
            <span>임포트 완료</span>
          </div>
          <div className="text-sm text-muted-foreground space-y-1 pl-7">
            {progress.totalRows !== undefined && (
              <p>전체: {progress.totalRows.toLocaleString()}행</p>
            )}
            {progress.successRows !== undefined && (
              <p>성공: <span className="text-green-700 font-medium">{progress.successRows.toLocaleString()}행</span></p>
            )}
            {progress.errorRows !== undefined && progress.errorRows > 0 && (
              <p>오류: <span className="text-destructive font-medium">{progress.errorRows.toLocaleString()}행</span></p>
            )}
          </div>
        </div>
      )}

      {stage === 'FAILED' && (
        <div className="rounded-md border border-red-200 bg-red-50 p-4 space-y-2">
          <div className="flex items-center gap-2 text-red-700 font-medium">
            <XCircle className="w-5 h-5" />
            <span>임포트 실패</span>
          </div>
          {progress.errorMessage && (
            <p className="text-sm text-red-600 pl-7">{progress.errorMessage}</p>
          )}
        </div>
      )}

      {!isTerminal && (
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
