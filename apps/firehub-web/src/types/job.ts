export interface JobProgress {
  jobId: string;
  jobType: string;
  stage: string;
  progress: number;
  message?: string;
  metadata?: Record<string, unknown>;
  errorMessage?: string;
  /**
   * 작업 자체가 실패한 것이 아니라 "진행 상황을 더는 확인할 수 없어" 추적을 멈춘 경우 true (WD-203).
   * 서버가 400/403/404 로 답하면(작업 없음·남의 작업·권한 회수) 재시도해도 소용없으므로 훅이 stage='FAILED' 로
   * 종단 처리하되, 소비자가 「임포트 실패」처럼 작업 실패로 단정해 보이지 않도록 이 표시를 붙인다.
   */
  unobservable?: boolean;
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
