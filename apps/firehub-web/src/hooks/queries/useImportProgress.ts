import { useJobProgress } from './useJobProgress';

export interface ImportProgress {
  jobId: string;
  stage: 'PENDING' | 'PARSING' | 'VALIDATING' | 'INSERTING' | 'COMPLETED' | 'FAILED';
  progress: number;
  totalRows?: number;
  processedRows?: number;
  successRows?: number;
  errorRows?: number;
  message?: string;
  errorMessage?: string;
}

// 백엔드 AsyncJobService.failJob()은 실패 시 stage='FAILED'와 함께 "마지막으로 기록된 progress"를
// 그대로 보존해서 보낸다(어느 단계에서 실패했는지 구분할 방법이 stage 자체엔 없음).
// VALIDATING은 분모가 없어 progress를 고정값 20으로, INSERTING은 40~100으로 보내므로
// 이 경계값(40)으로 "검증 중 실패"와 "삽입 중 실패"를 구분한다.
const INSERTING_PROGRESS_THRESHOLD = 40;

export function getFailedPhase(progress: number): 'VALIDATING' | 'INSERTING' {
  return progress < INSERTING_PROGRESS_THRESHOLD ? 'VALIDATING' : 'INSERTING';
}

export function useImportProgress(jobId: string | null): ImportProgress | null {
  const jobProgress = useJobProgress(jobId);

  if (!jobProgress) return null;

  const meta = jobProgress.metadata ?? {};

  return {
    jobId: jobProgress.jobId,
    stage: jobProgress.stage as ImportProgress['stage'],
    progress: jobProgress.progress,
    totalRows: typeof meta.totalRows === 'number' ? meta.totalRows : undefined,
    processedRows: typeof meta.processedRows === 'number' ? meta.processedRows : undefined,
    successRows: typeof meta.successRows === 'number' ? meta.successRows : undefined,
    errorRows: typeof meta.errorRows === 'number' ? meta.errorRows : undefined,
    message: jobProgress.message,
    errorMessage: jobProgress.errorMessage,
  };
}
