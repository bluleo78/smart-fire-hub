export interface JobProgress {
  jobId: string;
  jobType: string;
  stage: string;
  progress: number;
  message?: string;
  metadata?: Record<string, unknown>;
  errorMessage?: string;
}

export interface JobStatusResponse {
  jobId: string;
  jobType: string;
  stage: string;
  progress: number;
  message?: string;
  metadata?: Record<string, unknown>;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}
