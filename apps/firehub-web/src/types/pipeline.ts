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

export interface PipelineStepResponse {
  id: number;
  name: string;
  description: string | null;
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL';
  scriptContent: string;
  outputDatasetId: number;
  outputDatasetName: string;
  inputDatasetIds: number[];
  dependsOnStepNames: string[];
  stepOrder: number;
  loadStrategy: string;
  apiConfig: Record<string, unknown> | null;
  apiConnectionId: number | null;
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
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL';
  scriptContent?: string;
  outputDatasetId: number | null;
  inputDatasetIds: number[];
  dependsOnStepNames: string[];
  loadStrategy?: string;
  apiConfig?: Record<string, unknown>;
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
