/**
 * 파이프라인 도메인 모킹 데이터 팩토리
 * src/types/pipeline.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type {
  ExecutionDetailResponse,
  PipelineDetailResponse,
  PipelineExecutionResponse,
  PipelineResponse,
  PipelineStepResponse,
  StepExecutionResponse,
  TriggerResponse,
} from '@/types/pipeline';

/** 파이프라인 목록용 응답 객체 생성 */
export function createPipeline(overrides?: Partial<PipelineResponse>): PipelineResponse {
  return {
    id: 1,
    name: '테스트 파이프라인',
    description: '테스트용 ETL 파이프라인',
    isActive: true,
    createdBy: 'testuser',
    stepCount: 2,
    triggerCount: 1,
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 파이프라인 단계(Step) 응답 객체 생성 */
export function createStep(overrides?: Partial<PipelineStepResponse>): PipelineStepResponse {
  return {
    id: 1,
    name: '데이터 추출',
    description: 'SQL로 원본 데이터 추출',
    scriptType: 'SQL',
    scriptContent: 'SELECT * FROM source_table',
    outputDatasetId: 1,
    outputDatasetName: '출력 데이터셋',
    inputDatasetIds: [],
    dependsOnStepNames: [],
    stepOrder: 0,
    loadStrategy: 'REPLACE',
    apiConfig: null,
    aiConfig: undefined,
    pythonConfig: undefined,
    apiConnectionId: null,
    ...overrides,
  };
}

/** 단계 목록을 포함한 파이프라인 상세 응답 객체 생성 */
export function createPipelineDetail(overrides?: Partial<PipelineDetailResponse>): PipelineDetailResponse {
  return {
    id: 1,
    name: '테스트 파이프라인',
    description: '테스트용 ETL 파이프라인',
    isActive: true,
    createdBy: 'testuser',
    steps: [
      createStep(),
      createStep({ id: 2, name: '데이터 변환', stepOrder: 1, dependsOnStepNames: ['데이터 추출'] }),
    ],
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: null,
    updatedBy: null,
    ...overrides,
  };
}

/** 파이프라인 실행 응답 객체 생성 */
export function createExecution(overrides?: Partial<PipelineExecutionResponse>): PipelineExecutionResponse {
  return {
    id: 1,
    pipelineId: 1,
    status: 'COMPLETED',
    executedBy: 'testuser',
    triggeredBy: 'MANUAL',
    triggerName: null,
    startedAt: '2024-01-01T00:00:00Z',
    completedAt: '2024-01-01T00:01:00Z',
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 단계 실행(StepExecution) 응답 객체 생성 */
export function createStepExecution(overrides?: Partial<StepExecutionResponse>): StepExecutionResponse {
  return {
    id: 1,
    stepId: 1,
    stepName: '데이터 추출',
    status: 'COMPLETED',
    outputRows: 100,
    log: '실행 완료',
    errorMessage: null,
    startedAt: '2024-01-01T00:00:00Z',
    completedAt: '2024-01-01T00:00:30Z',
    ...overrides,
  };
}

/** 단계 실행 목록을 포함한 실행 상세 응답 객체 생성 */
export function createExecutionDetail(overrides?: Partial<ExecutionDetailResponse>): ExecutionDetailResponse {
  return {
    id: 1,
    pipelineId: 1,
    pipelineName: '테스트 파이프라인',
    status: 'COMPLETED',
    executedBy: 'testuser',
    stepExecutions: [
      createStepExecution(),
      createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환' }),
    ],
    startedAt: '2024-01-01T00:00:00Z',
    completedAt: '2024-01-01T00:01:00Z',
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 트리거 응답 객체 생성 */
export function createTrigger(overrides?: Partial<TriggerResponse>): TriggerResponse {
  return {
    id: 1,
    pipelineId: 1,
    triggerType: 'SCHEDULE',
    name: '매일 실행',
    description: '매일 자정에 실행되는 스케줄 트리거',
    isEnabled: true,
    config: { cronExpression: '0 0 * * *' },
    nextFireTime: '2024-01-02T00:00:00Z',
    createdAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** PipelineResponse 여러 개를 한 번에 생성 */
export function createPipelines(count: number): PipelineResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createPipeline({
      id: i + 1,
      name: `파이프라인 ${i + 1}`,
    }),
  );
}
