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
