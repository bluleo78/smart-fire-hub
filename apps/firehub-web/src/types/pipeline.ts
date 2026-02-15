export interface PipelineResponse {
  id: number;
  name: string;
  description: string | null;
  isActive: boolean;
  createdBy: string;
  stepCount: number;
  createdAt: string;
}

export interface PipelineStepResponse {
  id: number;
  name: string;
  description: string | null;
  scriptType: 'SQL' | 'PYTHON';
  scriptContent: string;
  outputDatasetId: number;
  outputDatasetName: string;
  inputDatasetIds: number[];
  dependsOnStepNames: string[];
  stepOrder: number;
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
}

export interface CreatePipelineRequest {
  name: string;
  description?: string;
  steps: PipelineStepRequest[];
}

export interface PipelineStepRequest {
  name: string;
  description?: string;
  scriptType: 'SQL' | 'PYTHON';
  scriptContent: string;
  outputDatasetId: number;
  inputDatasetIds: number[];
  dependsOnStepNames: string[];
}

export interface PipelineExecutionResponse {
  id: number;
  pipelineId: number;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  executedBy: string;
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
