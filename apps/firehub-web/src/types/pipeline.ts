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
  /** 조회자가 볼 수 없는 출력 데이터셋이면 null(보안 등급 — id 는 그대로 온다, 편집기는 받은 id 를 저장 시 되돌려 보낸다) */
  outputDatasetName: string | null;
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
  /**
   * 기존 스텝 id(WD-108) — 수정 저장 시 같은 스텝을 가리킨다. 보내면 서버가 그 스텝을 제자리 갱신(id 유지)해
   * 과거 실행 기록이 이어지고 이름을 바꿔도 같은 스텝으로 남는다. 새 스텝은 생략한다.
   */
  id?: number;
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
  /**
   * 사용자가 실행 취소를 요청한 시각(WD-153). 값이 있는데 status 가 아직 RUNNING 이면 "취소 요청됨" 상태다 —
   * 실행기가 다음 점검 지점에서 멈추면 CANCELLED 가 된다. 구 API 응답에는 없으므로 선택 필드.
   */
  cancelRequestedAt?: string | null;
}

/**
 * 실행 취소 요청 응답(WD-153, 202).
 * - CANCELLED: 아직 PENDING 이라 즉시 취소됐다.
 * - RUNNING: 취소 요청만 기록됐고 실행기가 다음 점검 지점에서 멈춘다(곧 CANCELLED 로 바뀌므로 계속 폴링).
 */
export interface CancelExecutionResponse {
  executionId: number;
  status: 'CANCELLED' | 'RUNNING';
  cancelRequestedAt: string;
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
  /**
   * 실행 단위 errorMessage 가 원문 대신 가림 문구인지(WD-27 — 조회자가 관련 데이터를 볼 수 없음).
   * 가림 문구 문자열을 비교하지 않고 이 값으로 "아래 오류 정보를 참고" 안내를 숨긴다. 구 API 응답에는 없으므로 선택 필드.
   */
  errorMasked?: boolean;
  /** 실행 취소를 요청한 시각(WD-153) — 취소 정보는 가림(WD-27) 대상인 errorMessage 대신 이 값으로 보여 준다. 구 응답에는 없다. */
  cancelRequestedAt?: string | null;
  /** 실행 취소를 요청한 사용자 표시 이름(WD-153). 구 응답에는 없다. */
  cancelRequestedBy?: string | null;
}

export interface StepExecutionResponse {
  id: number;
  /** 현재 정의의 스텝 id. null 이면 그 스텝이 실행 이후 정의에서 삭제됐다는 뜻이다(WD-108 — 기록은 남는다). */
  stepId: number | null;
  /** 실행 당시 스텝 이름(스냅샷) — 이후 스텝 이름이 바뀌거나 삭제돼도 그 실행 기준 이름이 온다. */
  stepName: string;
  /** CANCELLED = 취소가 닿았을 때 진행 중이던 스텝(WD-153). 아직 실행 전이던 스텝은 SKIPPED 로 남는다. */
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';
  outputRows: number | null;
  log: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
  /** errorMessage 가 원문 대신 가림 문구인지(WD-27) — 참이면 스텝 설정 확인 안내를 숨긴다. 구 API 응답에는 없으므로 선택 필드. */
  errorMasked?: boolean;
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
  /**
   * 연쇄 트리거의 선행 파이프라인이 없는지(삭제됨 등) — 서버가 조회 시 계산한다(WD-218). 옛 서버는 보내지 않으므로 선택값이고, 없으면 false 로 본다.
   */
  upstreamMissing?: boolean;
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

/**
 * 파이프라인 삭제 영향(WD-170) — 삭제하면 함께 지워질 자동 생성 임시 데이터셋 개수.
 * 다른 파이프라인 스텝이 출력으로 쓰고 있어 남는 임시 데이터셋은 세지 않는다(서버의 실제 삭제와 같은 판정).
 */
export interface PipelineDeleteImpact {
  tempDatasetCount: number;
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
