export interface PipelineResponse {
  id: number;
  name: string;
  description: string | null;
  isActive: boolean;
  createdBy: string;
  stepCount: number;
  triggerCount: number;
  createdAt: string;
}

export interface AiClassifyConfig {
  prompt: string;
  outputColumns: Array<{ name: string; type: string }>;
  inputColumns?: string[];
  batchSize?: number;
  onError?: 'CONTINUE' | 'RETRY_BATCH' | 'FAIL_STEP';
}

export interface PythonStepConfig {
  outputColumns?: Array<{ name: string; type: string }>;
}

export interface PipelineStepResponse {
  id: number;
  name: string;
  description: string | null;
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL' | 'AI_CLASSIFY';
  scriptContent: string;
  outputDatasetId: number;
  outputDatasetName: string;
  inputDatasetIds: number[];
  dependsOnStepNames: string[];
  stepOrder: number;
  loadStrategy: string;
  apiConfig: Record<string, unknown> | null;
  aiConfig?: Record<string, unknown>;
  pythonConfig?: PythonStepConfig;
  apiConnectionId: number | null;
  /** 증분 처리 책갈피 — 이 스텝이 마지막으로 성공 실행된 시각. 증분 대상이 아니면 null */
  lastRunAt: string | null;
  /** 다음 실행 시 전체 재생성(책갈피 무시)이 예약돼 있는지 여부 */
  fullRebuildPending: boolean;
  /** 저장 시 백엔드가 계산한 안내성 경고 목록 — 저장을 막지 않는다 */
  warnings: string[];
  /**
   * 전체 재생성 예약 시 실제로 벌어지는 동작.
   * REBUILD_OUTPUT: 출력 데이터셋을 비우고 원천 전체로 다시 만듦 (SELECT 스텝)
   * READ_ALL: 출력은 그대로 두고 원천만 전체 재조회 (사용자 DML 스텝) — 출력 재생성 아님
   * null: 이 스텝은 증분 처리 대상이 아니어서 재생성 예약을 제공하지 않음
   */
  fullRebuildMode: 'REBUILD_OUTPUT' | 'READ_ALL' | null;
}

export interface PipelineDetailResponse {
  id: number;
  name: string;
  description: string | null;
  isActive: boolean;
  createdBy: string;
  steps: PipelineStepResponse[];
  createdAt: string;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreatePipelineRequest {
  name: string;
  description?: string;
  steps: PipelineStepRequest[];
}

export interface PipelineStepRequest {
  name: string;
  description?: string;
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL' | 'AI_CLASSIFY';
  scriptContent?: string;
  outputDatasetId: number | null;
  inputDatasetIds: number[];
  dependsOnStepNames: string[];
  loadStrategy?: string;
  apiConfig?: Record<string, unknown>;
  aiConfig?: AiClassifyConfig;
  pythonConfig?: PythonStepConfig;
  apiConnectionId?: number | null;
}

export interface UpdatePipelineRequest {
  name: string;
  description?: string;
  isActive?: boolean;
  steps: PipelineStepRequest[];
}

export interface PipelineExecutionResponse {
  id: number;
  pipelineId: number;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  executedBy: string;
  triggeredBy: string;
  triggerName: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface ExecutionDetailResponse {
  id: number;
  pipelineId: number;
  pipelineName: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  executedBy: string;
  stepExecutions: StepExecutionResponse[];
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  /**
   * 스텝 실행 레코드가 하나도 생성되기 전에 발생한 최상위 예외 메시지 (#517).
   * 스텝 레벨 오류가 아닌 파이프라인 실행 자체의 실패 원인(토폴로지 정렬 실패, DB 오류 등)이며,
   * 정상 완료되었거나 스텝 레벨에서 실패한 경우 null이다.
   */
  errorMessage: string | null;
}

export interface StepExecutionResponse {
  id: number;
  stepId: number;
  stepName: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  outputRows: number | null;
  log: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

// --- Trigger types ---

export type TriggerType = 'SCHEDULE' | 'API' | 'PIPELINE_CHAIN' | 'WEBHOOK' | 'DATASET_CHANGE';
export type ConcurrencyPolicy = 'SKIP' | 'ALLOW';
export type TriggerCondition = 'SUCCESS' | 'FAILURE' | 'ANY';

export interface TriggerResponse {
  id: number;
  pipelineId: number;
  triggerType: TriggerType;
  name: string;
  description: string | null;
  isEnabled: boolean;
  config: Record<string, unknown>;
  nextFireTime: string | null;
  createdAt: string;
}

export interface TriggerEventResponse {
  id: number;
  triggerId: number;
  triggerName: string;
  eventType: 'FIRED' | 'SKIPPED' | 'ERROR' | 'MISSED';
  executionId: number | null;
  detail: Record<string, unknown>;
  createdAt: string;
}

export interface CreateTriggerRequest {
  name: string;
  triggerType: TriggerType;
  description?: string;
  config: Record<string, unknown>;
}

export interface UpdateTriggerRequest {
  name?: string;
  isEnabled?: boolean;
  description?: string;
  config?: Record<string, unknown>;
}
